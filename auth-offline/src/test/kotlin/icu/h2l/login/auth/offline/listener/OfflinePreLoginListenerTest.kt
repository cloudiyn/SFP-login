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

package icu.h2l.login.auth.offline.listener

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflinePreLoginListenerTest {
    private val defaultPrefixes = listOf("offline", "o-")

    @Test
    fun `host starting with configured prefix matches offline entry`() {
        assertTrue(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "offline.example.com"))
        assertTrue(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "o-1.example.com"))
    }

    @Test
    fun `host starting with configured prefix matches case insensitively`() {
        assertTrue(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "OFFLINE.example.com"))
        assertTrue(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "O-1.example.com"))
    }

    @Test
    fun `normal host does not match offline entry`() {
        // 回归用例：早期实现写成 `prefix.startsWith(host)`（参数写反），
        // 任何 host 都会返回 false，离线玩家无法按 host 兜底判定为离线。
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "starfallplain.cn"))
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "play.example.com"))
    }

    @Test
    fun `host containing prefix in the middle does not match`() {
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "play.offline.example.com"))
    }

    @Test
    fun `blank host and blank prefix are ignored`() {
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, ""))
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(defaultPrefixes, "   "))
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(listOf("", "  "), "offline.example.com"))
        assertFalse(OfflinePreLoginListener.matchesOfflineHost(emptyList(), "offline.example.com"))
    }
}
