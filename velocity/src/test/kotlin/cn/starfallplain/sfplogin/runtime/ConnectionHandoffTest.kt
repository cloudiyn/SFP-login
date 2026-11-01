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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionHandoffTest {

    @Test
    fun `初始状态直接放行动作`() {
        val handoff = ConnectionHandoff("test")
        var executed = 0

        assertTrue(handoff.defer { executed++ })
        assertEquals(1, executed)
        assertEquals(HandoffState.JOIN, handoff.state())
        assertFalse(handoff.isHolding())
    }

    @Test
    fun `扣留期间按登记顺序排队`() {
        val handoff = ConnectionHandoff("test")
        val order = mutableListOf<Int>()

        assertTrue(handoff.beginHold())
        assertTrue(handoff.isHolding())
        handoff.defer { order.add(1) }
        handoff.defer { order.add(2) }
        handoff.defer { order.add(3) }
        assertTrue(order.isEmpty())

        handoff.markVerified()
        assertEquals(HandoffState.VERIFIED, handoff.state())
        handoff.release()

        assertEquals(listOf(1, 2, 3), order)
        assertEquals(HandoffState.RELEASED, handoff.state())
        assertTrue(handoff.isSettled())
    }

    @Test
    fun `只允许首次进入扣留`() {
        val handoff = ConnectionHandoff("test")
        assertTrue(handoff.beginHold())
        assertFalse(handoff.beginHold())

        handoff.markVerified()
        handoff.release()
        assertFalse(handoff.beginHold())
    }

    @Test
    fun `放行后新动作立即执行`() {
        val handoff = ConnectionHandoff("test")
        handoff.beginHold()
        handoff.release()

        var executed = 0
        assertTrue(handoff.defer { executed++ })
        assertEquals(1, executed)
    }

    @Test
    fun `失败会丢弃排队动作`() {
        val handoff = ConnectionHandoff("test")
        val executed = mutableListOf<String>()

        handoff.beginHold()
        handoff.defer { executed.add("queued") }
        handoff.markFailed()

        assertEquals(HandoffState.FAILED, handoff.state())
        handoff.release()
        assertTrue(executed.isEmpty())
        assertEquals(HandoffState.FAILED, handoff.state())
        assertTrue(handoff.isSettled())
    }

    @Test
    fun `未扣留直接放行时不做任何事`() {
        val handoff = ConnectionHandoff("test")
        handoff.release()
        assertEquals(HandoffState.RELEASED, handoff.state())
    }

    @Test
    fun `放行动作抛错不影响后续动作`() {
        val handoff = ConnectionHandoff("test")
        val executed = mutableListOf<String>()

        handoff.beginHold()
        handoff.defer { throw IllegalStateException("boom") }
        handoff.defer { executed.add("after") }
        handoff.markVerified()
        handoff.release()

        assertEquals(listOf("after"), executed)
    }

    @Test
    fun `重复标记失败幂等`() {
        val handoff = ConnectionHandoff("test")
        handoff.beginHold()
        handoff.markFailed()
        handoff.markFailed()
        assertEquals(HandoffState.FAILED, handoff.state())
    }
}
