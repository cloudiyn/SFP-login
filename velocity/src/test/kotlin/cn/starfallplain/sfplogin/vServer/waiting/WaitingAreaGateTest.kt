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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaitingAreaGateTest {

    private fun newState(
        initialFlowPending: Boolean = false,
        inAuthHold: Boolean = true,
        hasConnected: Boolean = false,
    ) = WaitingAreaSessionState(
        authTarget = "auth",
        initialFlowPending = initialFlowPending,
        inAuthHold = inAuthHold,
        hasConnectedToAuthServerOnce = hasConnected,
    )

    @Test
    fun `桥接等待区默认仍在扣留中`() {
        val state = newState()
        assertTrue(WaitingAreaGate.isHolding(state))
        assertFalse(WaitingAreaGate.isStruck(state))
        assertFalse(WaitingAreaGate.hasLeftHoldingPhase(state))
    }

    @Test
    fun `struck 直通在初始流程结束前仍算等待区`() {
        val state = newState(initialFlowPending = true, inAuthHold = false, hasConnected = true)
        assertTrue(WaitingAreaGate.isStruck(state))
        assertTrue(WaitingAreaGate.isHolding(state))

        WaitingAreaGate.markInitialFlowCompleted(state)
        assertFalse(WaitingAreaGate.isHolding(state))
        assertTrue(WaitingAreaGate.hasLeftHoldingPhase(state))
    }

    @Test
    fun `认证通过后解除扣留`() {
        val state = newState()
        WaitingAreaGate.markEnteredAuthServer(state)
        WaitingAreaGate.markVerified(state)
        WaitingAreaGate.markInitialFlowCompleted(state)
        assertFalse(state.inAuthHold)
        assertFalse(WaitingAreaGate.isHolding(state))
        assertTrue(WaitingAreaGate.hasLeftHoldingPhase(state))
    }

    @Test
    fun `未连上认证服时认证通过只挂起放行`() {
        val state = newState()
        WaitingAreaGate.markVerified(state)
        WaitingAreaGate.deferVerifiedExit(state)
        assertTrue(state.verifiedExitPending)

        assertTrue(WaitingAreaGate.consumeDeferredVerifiedExit(state))
        assertFalse(state.verifiedExitPending)
        assertFalse(WaitingAreaGate.consumeDeferredVerifiedExit(state))
    }

    @Test
    fun `struck 回退等待区会重新扣留`() {
        val state = newState(initialFlowPending = true, inAuthHold = false, hasConnected = true)
        WaitingAreaGate.deferVerifiedExit(state)

        WaitingAreaGate.resetToBridgeHold(state)

        assertTrue(state.inAuthHold)
        assertFalse(state.hasConnectedToAuthServerOnce)
        assertFalse(state.verifiedExitPending)
        assertTrue(WaitingAreaGate.isHolding(state))
    }

    @Test
    fun `仅在开启记忆时记录请求目标`() {
        val state = newState()
        WaitingAreaGate.rememberRequestedServer(state, "lobby", enabled = false)
        assertNull(state.returnTarget)

        WaitingAreaGate.rememberRequestedServer(state, "lobby", enabled = true)
        assertEquals("lobby", state.returnTarget)
    }

    @Test
    fun `放行目标排除空白与认证服自身`() {
        val state = newState()
        assertNull(WaitingAreaGate.resolveReturnTarget(state))

        state.returnTarget = "  "
        assertNull(WaitingAreaGate.resolveReturnTarget(state))

        state.returnTarget = "AUTH"
        assertNull(WaitingAreaGate.resolveReturnTarget(state))

        state.returnTarget = "lobby"
        assertEquals("lobby", WaitingAreaGate.resolveReturnTarget(state))
    }

    @Test
    fun `describe 覆盖全部状态字段`() {
        val state = newState(initialFlowPending = true)
        state.returnTarget = "lobby"
        val text = state.describe()
        assertTrue(text.contains("authTarget=auth"))
        assertTrue(text.contains("returnTarget=lobby"))
        assertTrue(text.contains("inAuthHold=true"))
        assertTrue(text.contains("hasConnectedToAuthServerOnce=false"))
        assertTrue(text.contains("initialFlowPending=true"))
        assertTrue(text.contains("verifiedExitPending=false"))
    }
}
