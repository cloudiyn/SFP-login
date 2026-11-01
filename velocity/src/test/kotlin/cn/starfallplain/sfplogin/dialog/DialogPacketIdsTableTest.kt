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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogPacketIdsTableTest {

    @Test
    fun `收录的协议版本都受支持`() {
        assertEquals(setOf(771, 772, 773, 774, 775), DialogPacketIdsTable.supportedProtocols())
        DialogPacketIdsTable.supportedProtocols().forEach { protocol ->
            assertTrue(DialogPacketIdsTable.supports(protocol), "protocol $protocol 应当受支持")
        }
    }

    @Test
    fun `对话框出现之前的协议版本不受支持`() {
        listOf(0, 47, 340, 758, 767, 768, 769, 770).forEach { protocol ->
            assertFalse(DialogPacketIdsTable.supports(protocol), "protocol $protocol 不应当受支持")
            assertNull(DialogPacketIdsTable.forProtocol(protocol))
        }
    }

    @Test
    fun `未核对包 ID 的更高版本回退聊天`() {
        // 26.2（776）的 dialog 包 ID 未经核对，宁可回退聊天也不能发出错误包
        assertNull(DialogPacketIdsTable.forProtocol(776))
        assertFalse(DialogPacketIdsTable.supports(776))
    }

    @Test
    fun `包 ID 与 minecraft-data 记录一致`() {
        assertEquals(DialogPacketIds(0x85, 0x84, 0x41), DialogPacketIdsTable.forProtocol(771))
        assertEquals(DialogPacketIds(0x85, 0x84, 0x41), DialogPacketIdsTable.forProtocol(772))
        assertEquals(DialogPacketIds(0x8A, 0x89, 0x41), DialogPacketIdsTable.forProtocol(773))
        assertEquals(DialogPacketIds(0x8A, 0x89, 0x41), DialogPacketIdsTable.forProtocol(774))
        assertEquals(DialogPacketIds(0x8C, 0x8B, 0x44), DialogPacketIdsTable.forProtocol(775))
    }

    @Test
    fun `最低支持版本与表中的最小版本一致`() {
        val minimum = DialogPacketIdsTable.supportedProtocols().minOrNull()
        assertEquals(minimum, DialogPacketIdsTable.MIN_SUPPORTED_PROTOCOL)
    }
}
