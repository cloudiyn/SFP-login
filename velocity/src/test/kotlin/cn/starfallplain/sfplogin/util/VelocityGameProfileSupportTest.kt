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

package cn.starfallplain.sfplogin.util

import com.velocitypowered.api.util.GameProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class VelocityGameProfileSupportTest {
    @Test
    fun `semantic comparison ignores property order`() {
        val expected = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(
                GameProfile.Property("textures", "value-a", "signature-a"),
                GameProfile.Property("rank", "vip", ""),
            )
        )
        val actual = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(
                GameProfile.Property("rank", "vip", ""),
                GameProfile.Property("textures", "value-a", "signature-a"),
            )
        )

        assertFalse(hasSemanticGameProfileDifference(expected, actual))
    }

    @Test
    fun `semantic comparison detects profile mutations`() {
        val expected = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "value-a", "signature-a"))
        )
        val actual = GameProfile(
            PROFILE_UUID,
            "FormalName",
            listOf(GameProfile.Property("textures", "value-b", "signature-a"))
        )

        assertTrue(hasSemanticGameProfileDifference(expected, actual))
    }

    companion object {
        private val PROFILE_UUID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

