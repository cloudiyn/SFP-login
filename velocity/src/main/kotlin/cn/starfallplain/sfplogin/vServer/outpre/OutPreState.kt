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

package cn.starfallplain.sfplogin.vServer.outpre

/**
 * outpre 初始进入阶段的共享状态。
 *
 * 这份状态只描述“玩家当前是否还在初始认证/桥接窗口内”，
 * 不关心 struck 还是 waiting-area bridge 由谁驱动；两种模式会话都会复用它。
 */
internal data class OutPreState(
    var authTargetLabel: String,
    var returnTargetServerName: String? = null,
    var inAuthHold: Boolean = true,
    var hasConnectedToAuthServerOnce: Boolean = false,
    var verifiedExitPending: Boolean = false,
    var initialFlowPending: Boolean = false,
)
