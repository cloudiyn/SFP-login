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

import cn.starfallplain.sfplogin.api.auth.AuthPromptInput
import cn.starfallplain.sfplogin.api.auth.AuthPromptMode
import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogNbtFactoryTest {

    private val loginRequest = AuthPromptRequest(
        mode = AuthPromptMode.LOGIN,
        title = "登录",
        bodyLines = listOf("请输入密码", "输入后点击下方按钮"),
        inputs = listOf(
            AuthPromptInput(key = "password", label = "密码", maxLength = 32),
            AuthPromptInput(key = "totp", label = "动态验证码", maxLength = 6),
        ),
        submitLabel = "提交",
    )

    @Test
    fun `构造 multi_action 对话框骨架`() {
        val dialog = DialogNbtFactory.build(loginRequest)

        assertEquals("minecraft:multi_action", dialog.getString("type"))
        assertEquals("登录", dialog.getCompound("title").getString("text"))
        assertTrue(dialog.getBoolean("can_close_with_escape"))
        assertEquals("close", dialog.getString("after_action"))
    }

    @Test
    fun `正文逐行编译为 plain_message`() {
        val body = DialogNbtFactory.build(loginRequest).getList("body", BinaryTagTypes.COMPOUND)

        assertEquals(2, body.size())
        val first = body.getCompound(0)
        assertEquals("minecraft:plain_message", first.getString("type"))
        assertEquals(200, first.getInt("width"))
        val contents = first.getCompound("contents")
        assertEquals("请输入密码", contents.getString("text"))
        assertNull(contents.get("color"), "普通正文不应当带颜色")
        assertEquals("输入后点击下方按钮", body.getCompound(1).getCompound("contents").getString("text"))
    }

    @Test
    fun `输入控件编译为 text 输入并保留 key 与长度`() {
        val inputs = DialogNbtFactory.build(loginRequest).getList("inputs", BinaryTagTypes.COMPOUND)

        assertEquals(2, inputs.size())
        val password = inputs.getCompound(0)
        assertEquals("minecraft:text", password.getString("type"))
        assertEquals("password", password.getString("key"))
        assertEquals("密码", password.getCompound("label").getString("text"))
        assertEquals(200, password.getInt("width"))
        assertEquals(32, password.getInt("max_length"))
        assertEquals("totp", inputs.getCompound(1).getString("key"))
        assertEquals(6, inputs.getCompound(1).getInt("max_length"))
    }

    @Test
    fun `按钮动作走 dynamic custom 并带上模式标记`() {
        val actions = DialogNbtFactory.build(loginRequest).getList("actions", BinaryTagTypes.COMPOUND)

        assertEquals(1, actions.size())
        val button = actions.getCompound(0)
        assertEquals("提交", button.getCompound("label").getString("text"))
        val action = button.getCompound("action")
        assertEquals("dynamic/custom", action.getString("type"))
        assertEquals(DialogNbtFactory.ACTION_CHANNEL_ID, action.getString("id"))
        assertEquals("LOGIN", action.getCompound("additions").getString("mode"))
    }

    @Test
    fun `注册模式把模式标记换成 REGISTER`() {
        val dialog = DialogNbtFactory.build(loginRequest.copy(mode = AuthPromptMode.REGISTER))
        val action = dialog.getList("actions", BinaryTagTypes.COMPOUND)
            .getCompound(0)
            .getCompound("action")

        assertEquals("REGISTER", action.getCompound("additions").getString("mode"))
    }

    @Test
    fun `错误提示追加在正文末尾并标红`() {
        val dialog = DialogNbtFactory.build(loginRequest.copy(errorLine = "密码错误"))
        val body = dialog.getList("body", BinaryTagTypes.COMPOUND)

        assertEquals(3, body.size())
        val error = body.getCompound(2).getCompound("contents")
        assertEquals("密码错误", error.getString("text"))
        assertEquals("red", error.getString("color"))
        assertNull(
            body.getCompound(0).getCompound("contents").get("color"),
            "错误提示前的正文不应当被染色",
        )
    }

    @Test
    fun `没有输入控件时 inputs 为空列表而不是缺省`() {
        val dialog = DialogNbtFactory.build(loginRequest.copy(inputs = emptyList()))
        val inputs = dialog.getList("inputs", BinaryTagTypes.COMPOUND)

        assertEquals(0, inputs.size())
        assertNotNull(dialog.get("inputs"))
    }

    @Test
    fun `空正文仍然编译出合法的 body 列表`() {
        val dialog = DialogNbtFactory.build(loginRequest.copy(bodyLines = emptyList()))

        assertEquals(0, dialog.getList("body", BinaryTagTypes.COMPOUND).size())
        assertEquals("minecraft:multi_action", dialog.getString("type"))
    }

    @Test
    fun `构造结果可以按 NBT 序列化往返`() {
        val dialog = DialogNbtFactory.build(loginRequest)
        val output = java.io.ByteArrayOutputStream()
        net.kyori.adventure.nbt.BinaryTagIO.writer().write(dialog, output)

        val restored = net.kyori.adventure.nbt.BinaryTagIO.reader()
            .read(java.io.ByteArrayInputStream(output.toByteArray())) as CompoundBinaryTag

        assertEquals(dialog, restored)
    }
}
