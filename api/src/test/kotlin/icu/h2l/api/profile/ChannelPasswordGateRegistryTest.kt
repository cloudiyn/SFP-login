/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.api.profile

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 渠道密码门路由回归测试。
 *
 * 重点验证：当注册了多个 gate，且目标渠道**不基于挂起状态**（`holdsPlayerByPendingState == false`）
 * 且玩家此时还没有已提交凭证时，`resolveGate` 仍能正确回退到 `supports` 命中的渠道，
 * 而不是因为"无状态渠道不参与第 1 步筛选"就直接返回 null。
 */
class ChannelPasswordGateRegistryTest {

    private val registered = mutableListOf<ChannelPasswordGate>()

    @AfterEach
    fun tearDown() {
        registered.forEach { ChannelPasswordGateRegistry.unregister(it.channelId) }
        registered.clear()
    }

    private fun register(gate: ChannelPasswordGate) {
        ChannelPasswordGateRegistry.register(gate)
        registered += gate
    }

    @Test
    fun `falls back to supports match when gate is stateless`() {
        // 模拟 floodgate：有状态（holdsPlayerByPendingState=true），但当前不拦该玩家
        register(FakeGate(channelId = "floodgate", priority = 10, holds = true, supported = false, status = null))
        // 模拟 offline：无状态兜底渠道，认领玩家且需注册
        val offline = FakeGate(
            channelId = "offline",
            priority = 0,
            holds = false,
            supported = true,
            status = ChannelPasswordGateStatus.NEEDS_REGISTER
        )
        register(offline)

        val player = FakeHyperZonePlayer()

        val resolved = ChannelPasswordGateRegistry.resolveGate(player)
        assertSame(offline, resolved, "无状态兜底渠道应能通过 supports 回退被选中")
    }

    @Test
    fun `returns null when no gate supports the player`() {
        register(FakeGate(channelId = "offline", priority = 0, holds = false, supported = false, status = null))
        assertNull(ChannelPasswordGateRegistry.resolveGate(FakeHyperZonePlayer()))
    }

    @Test
    fun `does not misfire when a gate shares the submitted credential channel id`() {
        // 已提交凭证属于 offline
        val submitting = FakeGate(
            channelId = "offline",
            priority = 0,
            holds = false,
            supported = true,
            status = null
        )
        register(submitting)
        val player = FakeHyperZonePlayer(submittedChannelId = "offline")

        assertSame(submitting, ChannelPasswordGateRegistry.resolveGate(player))
    }

    @Test
    fun `prefers the gate that is holding the player`() {
        val holder = FakeGate(
            channelId = "offline",
            priority = 0,
            holds = true,
            supported = true,
            status = ChannelPasswordGateStatus.NEEDS_LOGIN
        )
        register(holder)
        register(FakeGate(channelId = "floodgate", priority = 100, holds = true, supported = true, status = null))

        assertSame(holder, ChannelPasswordGateRegistry.resolveGate(FakeHyperZonePlayer()))
    }

    private class FakeGate(
        override val channelId: String,
        override val priority: Int,
        private val holds: Boolean,
        private val supported: Boolean,
        private val status: ChannelPasswordGateStatus?,
    ) : ChannelPasswordGate {
        override fun holdsPlayerByPendingState(): Boolean = holds
        override fun supports(player: HyperZonePlayer): Boolean = supported
        override fun getGateStatus(player: HyperZonePlayer): ChannelPasswordGateStatus? = status
        override fun handleRegister(player: Player, args: Array<String>): Component = Component.empty()
        override fun handleLogin(player: Player, args: Array<String>): Component = Component.empty()
        override fun handleChangePassword(player: Player, args: Array<String>): Component = Component.empty()
    }

    private class FakeHyperZonePlayer(
        private val submittedChannelId: String? = null,
    ) : HyperZonePlayer {
        override val clientOriginalName: String = "Tester"
        override val clientOriginalUUID: UUID = UUID.randomUUID()
        override val isOnlinePlayer: Boolean = false
        override val authChannelId: String? get() = submittedChannelId
        override fun hasAttachedProfile(): Boolean = false
        override fun getTemporaryGameProfile() = throw UnsupportedOperationException()
        override fun getAttachedGameProfile() = throw UnsupportedOperationException()
        override fun getApplyGameProfile() = throw UnsupportedOperationException()
        override fun sendMessage(message: Component) = Unit
    }
}
