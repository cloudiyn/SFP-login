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

package cn.starfallplain.sfplogin.api.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * R1-1 兼容基线：锁定 remap / profile UUID 的生成方式，任何漂移都会破坏老玩家的数据关联。
 */
class RemapUtilsTest {
    @Test
    fun `genUUID is stable for same username and prefix`() {
        assertEquals(
            RemapUtils.genUUID("Steve", "HyperZone"),
            RemapUtils.genUUID("Steve", "HyperZone"),
        )
    }

    @Test
    fun `genUUID uses documented name-based derivation`() {
        val expected = UUID.nameUUIDFromBytes("HyperZone:Steve".toByteArray(StandardCharsets.UTF_8))
        assertEquals(expected, RemapUtils.genUUID("Steve", "HyperZone"))
    }

    @Test
    fun `genUUID is prefix sensitive`() {
        assertNotEquals(
            RemapUtils.genUUID("Steve", "HyperZone"),
            RemapUtils.genUUID("Steve", "OfflinePlayer"),
        )
    }

    @Test
    fun `genUUID is case sensitive while genProfileUUID normalizes`() {
        assertNotEquals(RemapUtils.genUUID("Steve", "HyperZone"), RemapUtils.genUUID("steve", "HyperZone"))
        assertEquals(RemapUtils.genUUID("steve", "h2l"), RemapUtils.genProfileUUID("Steve"))
        assertEquals(RemapUtils.genProfileUUID("STEVE"), RemapUtils.genProfileUUID("steve"))
    }

    @Test
    fun `genProfile keeps username and leaves properties empty`() {
        val profile = RemapUtils.genProfile("HZL000123", RemapUtils.REMAP_PREFIX)

        assertEquals("HZL000123", profile.name)
        assertEquals(RemapUtils.genUUID("HZL000123", RemapUtils.REMAP_PREFIX), profile.id)
        assertTrue(profile.properties.isEmpty())
    }

    @Test
    fun `randomProfile keeps expected name prefix and remap namespace`() {
        repeat(50) {
            val profile = RemapUtils.randomProfile()

            assertTrue(profile.name.startsWith(RemapUtils.EXPECTED_NAME_PREFIX), "unexpected name: ${profile.name}")
            assertEquals(RemapUtils.genUUID(profile.name, RemapUtils.REMAP_PREFIX), profile.id)
            assertTrue(profile.properties.isEmpty())
        }
    }

    @Test
    fun `resolveActualUuid keeps holder uuid when already known`() {
        val holder = UUID.fromString("55555555-5555-4555-8555-555555555555")

        assertEquals(holder, RemapUtils.resolveActualUuid(holder, "Steve", "HyperZone"))
    }

    @Test
    fun `resolveActualUuid derives uuid from remap prefix when holder uuid is absent`() {
        assertEquals(
            RemapUtils.genUUID("Steve", "HyperZone"),
            RemapUtils.resolveActualUuid(null, "Steve", "HyperZone"),
        )
    }
}
