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

package cn.starfallplain.sfplogin.reflect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自检表的守护测试。
 *
 * 这里不断言"当前 Velocity 版本全部可用"（那会让测试与服务端版本强绑定，换版本就红），
 * 只断言自检本身是自洽的：条目非空、名称唯一、失败项都带提示、重复调用结果稳定。
 */
class VelocityInternalAccessSelfCheckTest {

    @Test
    fun `自检覆盖关键内部入口`() {
        assertTrue(
            VelocityInternalAccess.selfCheckItemCount >= 20,
            "自检项过少，说明有内部入口没有纳入启动自检",
        )
    }

    @Test
    fun `自检失败项都带有排查提示`() {
        val failures = VelocityInternalAccess.selfCheck()

        failures.forEach { failure ->
            assertTrue(failure.name.isNotBlank(), "失败项缺少名称")
            assertTrue(failure.hint.isNotBlank(), "失败项 ${failure.name} 缺少排查提示")
        }
    }

    @Test
    fun `自检结果稳定可重复`() {
        val first = VelocityInternalAccess.selfCheck().map { it.name }
        val second = VelocityInternalAccess.selfCheck().map { it.name }

        assertEquals(first, second, "同一服务端版本下自检结果应稳定")
        assertEquals(first.size, first.toSet().size, "自检失败项不应重复")
    }

    @Test
    fun `varint 读取入口可用`() {
        val buffer = io.netty.buffer.Unpooled.buffer()
        buffer.writeByte(0x7f)

        assertEquals(127, VelocityInternalAccess.readVarInt(buffer))
        buffer.release()
    }
}
