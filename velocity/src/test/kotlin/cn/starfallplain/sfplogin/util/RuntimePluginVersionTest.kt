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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimePluginVersionTest {
    @Test
    fun `extractPluginVersion reads version from plugin json`() {
        val pluginJson = """
            {
              "id":"sfplogin",
              "name":"SFP-login",
              "version":"26.7.1-SNAPSHOT-abcd1234"
            }
        """.trimIndent()

        assertEquals("26.7.1-SNAPSHOT-abcd1234", extractPluginVersion(pluginJson))
    }

    @Test
    fun `isSnapshotPluginVersion only matches snapshot builds`() {
        assertTrue(isSnapshotPluginVersion("26.7.1-SNAPSHOT-abcd1234"))
        assertFalse(isSnapshotPluginVersion("26.7.1-RC-abcd1234"))
        assertFalse(isSnapshotPluginVersion("26.7.1-abcd1234"))
        assertFalse(isSnapshotPluginVersion(null))
    }
}
