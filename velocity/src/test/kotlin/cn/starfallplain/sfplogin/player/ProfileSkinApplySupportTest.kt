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

package cn.starfallplain.sfplogin.player

import com.velocitypowered.api.util.GameProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class ProfileSkinApplySupportTest {
    @Test
    fun `merge textures keeps attached identity and replaces previous textures property`() {
        val baseProfile = GameProfile(
            ATTACHED_UUID,
            "FormalName",
            listOf(
                GameProfile.Property("TeXtUrEs", "old-value", "old-signature"),
                GameProfile.Property("rank", "vip", ""),
            )
        )

        val merged = ProfileSkinApplySupport.mergeTextures(
            baseProfile,
            GameProfile.Property("textures", "new-value", "new-signature")
        )

        assertEquals(ATTACHED_UUID, merged.id)
        assertEquals("FormalName", merged.name)
        assertEquals(2, merged.properties.size)
        val rank = merged.properties.singleOrNull { it.name == "rank" }
        assertNotNull(rank)
        assertEquals("vip", rank!!.value)
        assertEquals("", rank.signature)
        val textures = merged.properties.singleOrNull { it.name.equals("textures", ignoreCase = true) }
        assertNotNull(textures)
        assertEquals("new-value", textures!!.value)
        assertEquals("new-signature", textures.signature)
        assertFalse(merged.properties.any { it.name.equals("textures", ignoreCase = true) && it.value == "old-value" })
    }

    companion object {
        private val ATTACHED_UUID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

