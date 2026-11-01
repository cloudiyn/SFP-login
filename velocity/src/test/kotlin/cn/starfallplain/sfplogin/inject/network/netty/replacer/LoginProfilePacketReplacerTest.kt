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

package cn.starfallplain.sfplogin.inject.network.netty.replacer

import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class LoginProfilePacketReplacerTest {
    @Test
    fun `rewrite is skipped when current vc profile already matches expected profile`() {
        val expected = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "value-a", "signature-a"))
        )
        val current = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "value-a", "signature-a"))
        )

        assertFalse(shouldRewriteLoginProfile(current, expected))
    }

    @Test
    fun `rewrite is required when vc profile identity differs from expected profile`() {
        val expected = GameProfile(PROFILE_UUID, "FormalName", emptyList())
        val current = GameProfile(PROFILE_UUID, "FloodgateWrappedName", emptyList())

        assertTrue(shouldRewriteLoginProfile(current, expected))
    }

    @Test
    fun `rewrite is required when vc profile properties differ from expected profile`() {
        val expected = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "expected", "signature-a"))
        )
        val current = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "mutated", "signature-a"))
        )

        assertTrue(shouldRewriteLoginProfile(current, expected))
    }

    @Test
    fun `modern forwarding version is decoded after the 32 byte signature`() {
        val payload = Unpooled.buffer()
        payload.writeZero(MODERN_FORWARDING_SIGNATURE_LENGTH)
        ProtocolUtils.writeVarInt(payload, 3)
        val originalReaderIndex = payload.readerIndex()

        assertEquals(3, decodeModernForwardingVersion(payload))
        assertEquals(originalReaderIndex, payload.readerIndex(), "decode must not consume the packet buffer")
    }

    @Test
    fun `modern forwarding version falls back when payload is missing`() {
        assertNull(decodeModernForwardingVersion(null))
    }

    @Test
    fun `modern forwarding version falls back when payload is not longer than signature`() {
        val payload = Unpooled.buffer()
        payload.writeZero(MODERN_FORWARDING_SIGNATURE_LENGTH)

        assertNull(decodeModernForwardingVersion(payload))
    }

    @Test
    fun `modern forwarding version falls back when varint cannot be decoded`() {
        val payload = Unpooled.buffer()
        payload.writeZero(MODERN_FORWARDING_SIGNATURE_LENGTH)
        repeat(6) { payload.writeByte(0x80) }

        assertNull(decodeModernForwardingVersion(payload))
    }

    companion object {
        private val PROFILE_UUID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
    }
}

