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

package cn.starfallplain.sfplogin.dialog

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DialogPromptRouterTest {

    private val playerId: UUID = UUID.randomUUID()
    private val player: ConnectedPlayer = mockk()

    @BeforeEach
    fun setUp() {
        DialogPromptRouter.clear(playerId)
        every { player.uniqueId } returns playerId
        every { player.protocolVersion } returns ProtocolVersion.MINECRAFT_1_21_6
    }

    private fun body(channelId: String, payload: CompoundBinaryTag?): ByteBuf {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeString(buf, channelId)
        buf.writeBoolean(payload != null)
        payload?.let { ProtocolUtils.writeBinaryTag(buf, player.protocolVersion, it) }
        return buf
    }

    private class RecordingTarget : DialogPromptTarget {
        val payloads = AtomicReference<CompoundBinaryTag?>(null)
        val calls = AtomicInteger()

        override fun submit(payload: CompoundBinaryTag?) {
            payloads.set(payload)
            calls.incrementAndGet()
        }
    }

    @Test
    fun `已注册会话收到 payload`() {
        val target = RecordingTarget()
        DialogPromptRouter.register(playerId, target)
        val payload = CompoundBinaryTag.builder().putString("password", "secret").build()

        val handled = DialogPromptRouter.tryHandle(player, body(DialogNbtFactory.ACTION_CHANNEL_ID, payload))

        assertTrue(handled)
        assertEquals(1, target.calls.get())
        assertEquals("secret", target.payloads.get()?.getString("password"))
    }

    @Test
    fun `没有活跃会话的过期响应也会被消费`() {
        val handled = DialogPromptRouter.tryHandle(player, body(DialogNbtFactory.ACTION_CHANNEL_ID, null))

        assertTrue(handled, "过期响应不能转发给后端")
    }

    @Test
    fun `其它命名空间的动作不归本插件处理`() {
        val target = RecordingTarget()
        DialogPromptRouter.register(playerId, target)

        val handled = DialogPromptRouter.tryHandle(player, body("other:mod", null))

        assertFalse(handled)
        assertEquals(0, target.calls.get())
    }

    @Test
    fun `无法解析的包体不归本插件处理`() {
        val handled = DialogPromptRouter.tryHandle(player, Unpooled.buffer())

        assertFalse(handled)
    }

    @Test
    fun `注销只移除同一个目标`() {
        val first = RecordingTarget()
        val second = RecordingTarget()
        DialogPromptRouter.register(playerId, second)

        DialogPromptRouter.unregister(playerId, first)

        assertSame(second, DialogPromptRouter.target(playerId))
    }

    @Test
    fun `清理后不再有目标`() {
        DialogPromptRouter.register(playerId, RecordingTarget())

        DialogPromptRouter.clear(playerId)

        assertNull(DialogPromptRouter.target(playerId))
    }
}
