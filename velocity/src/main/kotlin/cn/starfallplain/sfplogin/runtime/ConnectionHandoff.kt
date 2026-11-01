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

/**
 * 连接交接阶段。
 *
 * 登录阶段的包改写需要等待异步结果（事件总线、皮肤解析），而 Netty 的出站/入站回调运行在
 * 连接自己的事件循环线程上，直接 `join()` 会阻塞该连接（以及同循环上的其它连接）的 I/O。
 * 这里用显式状态机把「等待」变成「挂起后续动作，结果就绪后按序放行」：
 *
 * - [JOIN]：尚未发起任何异步解析，出站写可以直接放行；
 * - [HELD]：异步解析进行中，期间收到的写操作一律排队；
 * - [VERIFIED]：异步解析成功结束，等待 [release] 放行排队的动作；
 * - [RELEASED]：已按序放行，后续动作立即执行；
 * - [FAILED]：解析失败，排队的动作被丢弃，连接应交给上层报错处理。
 *
 * 该类只做状态与队列管理，不持有线程；调用方负责把结果回调切回事件循环线程。
 */
class ConnectionHandoff(private val label: String) {

    private val lock = Any()
    private val deferred = ArrayDeque<() -> Unit>()
    private var currentState = HandoffState.JOIN

    fun state(): HandoffState = synchronized(lock) { currentState }

    /** 是否处于「异步解析进行中」的扣留状态。 */
    fun isHolding(): Boolean = synchronized(lock) { currentState == HandoffState.HELD }

    /** 是否已经结束扣留（放行成功或失败）。 */
    /** 当前仍在排队等待放行的动作数（诊断用）。 */
    fun pendingCount(): Int = synchronized(lock) { deferred.size }

    fun isSettled(): Boolean = synchronized(lock) {
        currentState == HandoffState.RELEASED || currentState == HandoffState.FAILED
    }

    /**
     * 进入扣留状态。
     *
     * @return 首次从 [HandoffState.JOIN] 进入时返回 true，由调用方发起异步解析；
     *         已经扣留或已经结束时返回 false，调用方只需把当前动作排队。
     */
    fun beginHold(): Boolean = synchronized(lock) {
        if (currentState != HandoffState.JOIN) {
            return false
        }
        currentState = HandoffState.HELD
        true
    }

    /** 异步解析成功结束，进入 [HandoffState.VERIFIED]，等待 [release]。 */
    fun markVerified() {
        synchronized(lock) {
            if (currentState == HandoffState.HELD) {
                currentState = HandoffState.VERIFIED
            }
        }
    }

    /** 异步解析失败，丢弃排队动作并进入 [HandoffState.FAILED]。 */
    fun markFailed() {
        synchronized(lock) {
            if (currentState == HandoffState.FAILED) {
                return
            }
            currentState = HandoffState.FAILED
            deferred.clear()
        }
    }

    /**
     * 追加一个动作。
     *
     * 处于扣留状态时排队；已经放行或尚未扣留时立即执行。
     *
     * @return 动作是否被立即执行
     */
    fun defer(action: () -> Unit): Boolean = synchronized(lock) {
        if (currentState == HandoffState.HELD) {
            deferred.addLast(action)
            return false
        }
        true
    }.also { runNow ->
        if (runNow) {
            action()
        }
    }

    /**
     * 结束扣留并按登记顺序放行动作。
     *
     * 若仍处于 [HandoffState.JOIN]（未发起过异步解析），则视为无需要等待的内容，直接放行。
     */
    fun release() {
        val actions = synchronized(lock) {
            if (currentState == HandoffState.FAILED) {
                deferred.clear()
                return
            }
            currentState = HandoffState.RELEASED
            val snapshot = deferred.toList()
            deferred.clear()
            snapshot
        }
        actions.forEach { action ->
            runCatching(action).onFailure { throwable ->
                logger.warn("[Handoff:{}] 放行动作执行失败: {}", label, throwable.message)
            }
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(ConnectionHandoff::class.java)
    }
}

/** 连接交接状态，见 [ConnectionHandoff]。 */
enum class HandoffState {
    JOIN,
    HELD,
    VERIFIED,
    RELEASED,
    FAILED,
}
