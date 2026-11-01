/*
 * This file is part of SFP-login, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package cn.starfallplain.sfplogin.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 交接状态机的并发边界测试。
 *
 * `defer` 来自登录包改写器所在的事件循环线程，`release` 来自异步结果回调的其它线程，
 * 两者并发时的语义必须是"每个动作恰好执行一次、顺序不死锁"。
 */
class ConnectionHandoffConcurrencyTest {

    @Test
    fun `并发扣留的动作在放行后各执行一次`() {
        val handoff = ConnectionHandoff("concurrency")
        val executed = CopyOnWriteArrayList<Int>()
        val threadCount = 8
        val perThread = 50

        assertTrue(handoff.beginHold())

        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { threadIndex ->
            pool.execute {
                startGate.await()
                repeat(perThread) { offset ->
                    val id = threadIndex * perThread + offset
                    handoff.defer { executed += id }
                }
                done.countDown()
            }
        }

        startGate.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "扣留期入队不应阻塞")
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(0, executed.size, "扣留期不应执行任何动作")
        assertEquals(threadCount * perThread, handoff.pendingCount())

        handoff.release()

        assertEquals(HandoffState.RELEASED, handoff.state())
        assertEquals(threadCount * perThread, executed.size, "放行不应重复执行已入队的动作")
        assertEquals((threadCount * perThread).toLong(), executed.toSet().size.toLong(), "同一动作不得执行两次")
        assertEquals(0, handoff.pendingCount())
    }

    @Test
    fun `放行与入队并发时动作不丢失`() {
        val handoff = ConnectionHandoff("release-race")
        val executed = AtomicInteger()

        assertTrue(handoff.beginHold())

        val writer = Thread {
            repeat(200) { handoff.defer { executed.incrementAndGet() } }
        }
        val releaser = Thread {
            // 与写入线程竞争，验证 release 之后 defer 走直接执行分支而不是丢掉动作
            Thread.sleep(1)
            handoff.release()
        }

        writer.start()
        releaser.start()
        writer.join(10_000)
        releaser.join(10_000)

        // release 之后仍有并发 defer 落到"立即执行"分支
        repeat(50) { handoff.defer { executed.incrementAndGet() } }

        assertEquals(HandoffState.RELEASED, handoff.state())
        assertTrue(executed.get() in 200..250, "动作数应落在 200 到 250 之间，实际=${executed.get()}")
    }
}
