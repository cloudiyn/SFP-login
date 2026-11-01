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
 * 等待区状态迁移与判定。
 *
 * 集中处理"是否还在等待区""是否处于 struck 直通""认证完成后能否立刻放行"等判断，
 * 避免各会话实现各自拼装状态字段组合。
 */
internal object WaitingAreaGate {

    /** 玩家仍在等待区：被扣留在认证服、尚未连上认证服，或初始流程未结束。 */
    fun isHolding(state: WaitingAreaSessionState): Boolean =
        state.inAuthHold || !state.hasConnectedToAuthServerOnce || state.initialFlowPending

    /** struck 直通：已经进入认证服且已解除扣留。 */
    fun isStruck(state: WaitingAreaSessionState): Boolean =
        !state.inAuthHold && state.hasConnectedToAuthServerOnce

    /** 玩家已进入游戏阶段（不再扣留、初始流程已结束）。 */
    fun hasLeftHoldingPhase(state: WaitingAreaSessionState): Boolean =
        !state.inAuthHold && !state.initialFlowPending

    /** 认证通过：解除扣留。 */
    fun markVerified(state: WaitingAreaSessionState) {
        state.inAuthHold = false
    }

    /** 初次连上认证服。 */
    fun markEnteredAuthServer(state: WaitingAreaSessionState) {
        state.hasConnectedToAuthServerOnce = true
    }

    /** 初始进入流程收尾。 */
    fun markInitialFlowCompleted(state: WaitingAreaSessionState) {
        state.initialFlowPending = false
    }

    /** 认证通过但认证服尚未连上：先挂起放行，等连上后补做。 */
    fun deferVerifiedExit(state: WaitingAreaSessionState) {
        state.verifiedExitPending = true
    }

    /** 消费挂起的放行请求；返回是否确实消费到了挂起请求。 */
    fun consumeDeferredVerifiedExit(state: WaitingAreaSessionState): Boolean {
        if (!state.verifiedExitPending) {
            return false
        }
        state.verifiedExitPending = false
        return true
    }

    /** 认证窗口内玩家请求的服：仅在开启"记住认证期间请求的服"时记录。 */
    fun rememberRequestedServer(state: WaitingAreaSessionState, serverName: String, enabled: Boolean) {
        if (enabled) {
            state.returnTarget = serverName
        }
    }

    /** struck 失败回退等待区桥接：重新扣留并清掉已进入/已放行标记。 */
    fun resetToBridgeHold(state: WaitingAreaSessionState) {
        state.inAuthHold = true
        state.hasConnectedToAuthServerOnce = false
        state.verifiedExitPending = false
    }

    /**
     * 解析认证完成后应转发的目标服：优先使用等待区期间记录的请求目标，
     * 但排除空值与认证服自身；没有可用目标时返回 null，由调用方回退到代理默认目标。
     */
    fun resolveReturnTarget(state: WaitingAreaSessionState): String? =
        state.returnTarget?.takeUnless { it.isBlank() || it.equals(state.authTarget, ignoreCase = true) }
}
