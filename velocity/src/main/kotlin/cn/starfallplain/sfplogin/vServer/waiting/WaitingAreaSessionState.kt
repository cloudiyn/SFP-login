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

package cn.starfallplain.sfplogin.vServer.waiting

/**
 * 等待区会话状态：描述"玩家当前是否仍在认证/等待窗口内"以及窗口两侧的目标服。
 *
 * 该状态与具体驱动方式（struck 直通、outpre 桥接等待区）解耦：
 * 两种模式会话共享同一份状态，由 [WaitingAreaGate] 负责状态迁移判定。
 *
 * 字段均以 `@Volatile` 暴露：写入通常发生在事件循环线程，
 * 而完成回调（认证服务异步返回）可能来自其它线程。
 */
class WaitingAreaSessionState(
    /** 认证服在代理内注册的名字，用于识别"是否还在认证窗口内"。 */
    val authTarget: String,
    /** 玩家在认证窗口内请求的目标服，认证完成后优先转发到这里。 */
    @Volatile var returnTarget: String? = null,
    /** 初始进入流程（struck / 桥接）是否仍在进行。 */
    @Volatile var initialFlowPending: Boolean = false,
    /** 是否仍被扣留在认证服（尚未通行的帧不转发给后端）。 */
    @Volatile var inAuthHold: Boolean = true,
    /** 是否已经成功连上认证服；未连上时不能直接放行。 */
    @Volatile var hasConnectedToAuthServerOnce: Boolean = false,
    /** 已认证但认证服尚未连上，等连上后立即放行。 */
    @Volatile var verifiedExitPending: Boolean = false,
) {

    /** 结构化描述，用于 trace 日志。 */
    fun describe(): String =
        "state(inAuthHold=$inAuthHold, hasConnectedToAuthServerOnce=$hasConnectedToAuthServerOnce, " +
            "verifiedExitPending=$verifiedExitPending, initialFlowPending=$initialFlowPending, " +
            "returnTarget=$returnTarget, authTarget=$authTarget)"
}
