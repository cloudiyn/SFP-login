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
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DialogCustomActionCodecTest {

    private val version = ProtocolVersion.MINECRAFT_1_21_6

    private fun body(channelId: String, payload: CompoundBinaryTag?): io.netty.buffer.ByteBuf {
        val buf = Unpooled.buffer()
        ProtocolUtils.writeString(buf, channelId)
        buf.writeBoolean(payload != null)
        payload?.let { ProtocolUtils.writeBinaryTag(buf, version, it) }
        return buf
    }

    @Test
    fun `解析动作 ID 与 payload`() {
        val payload = CompoundBinaryTag.builder()
            .putString("mode", "LOGIN")
            .putString("password", "secret")
            .build()
        val buf = body("hzl:auth", payload)

        val action = DialogCustomActionCodec.decode(buf, version)

        assertNotNull(action)
        assertEquals("hzl:auth", action!!.channelId)
        assertEquals("secret", action.payload?.getString("password"))
        assertEquals("LOGIN", action.payload?.getString("mode"))
    }

    @Test
    fun `解析没有 payload 的动作`() {
        val action = DialogCustomActionCodec.decode(body("hzl:auth", null), version)

        assertNotNull(action)
        assertEquals("hzl:auth", action!!.channelId)
        assertNull(action.payload)
    }

    @Test
    fun `解析时不消费原 buffer`() {
        val buf = body("hzl:auth", CompoundBinaryTag.empty())

        DialogCustomActionCodec.decode(buf, version)

        assertEquals(0, buf.readerIndex())
        assertEquals(buf.writerIndex(), buf.readableBytes())
    }

    @Test
    fun `尾部有多余字节时视为未知包`() {
        val buf = body("hzl:auth", CompoundBinaryTag.empty())
        buf.writeByte(0x7F)

        assertNull(DialogCustomActionCodec.decode(buf, version))
    }

    @Test
    fun `空包体直接忽略`() {
        assertNull(DialogCustomActionCodec.decode(Unpooled.buffer(), version))
    }

    @Test
    fun `被截断的动作 ID 直接忽略`() {
        val buf = Unpooled.buffer()
        buf.writeByte(0x7F)

        assertNull(DialogCustomActionCodec.decode(buf, version))
    }

    @Test
    fun `payload 被截断时直接忽略`() {
        val buf = body("hzl:auth", CompoundBinaryTag.empty())
        buf.writerIndex(buf.writerIndex() - 1)

        assertNull(DialogCustomActionCodec.decode(buf, version))
    }
}
