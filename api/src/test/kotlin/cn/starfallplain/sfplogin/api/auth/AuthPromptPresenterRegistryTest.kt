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

package cn.starfallplain.sfplogin.api.auth

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer

class AuthPromptPresenterRegistryTest {

    private val player: HyperZonePlayer = mockk(relaxed = true)

    private val request = AuthPromptRequest(
        mode = AuthPromptMode.LOGIN,
        title = "登录",
        bodyLines = emptyList(),
        inputs = emptyList(),
        submitLabel = "提交",
    )

    private class FakePresenter(
        override val id: String,
        override val priority: Int,
        private val supported: Boolean,
        private val failOnCheck: Boolean = false,
    ) : AuthPromptPresenter {
        override fun isSupported(player: HyperZonePlayer): Boolean {
            if (failOnCheck) error("boom")
            return supported
        }

        override fun present(
            player: HyperZonePlayer,
            request: AuthPromptRequest,
            onSubmit: (Map<String, String>) -> Unit,
            onClosed: () -> Unit,
        ): AuthPromptSession? = null
    }

    @AfterEach
    fun tearDown() {
        AuthPromptPresenterRegistry.clear()
    }

    @Test
    fun `按优先级选择展示层`() {
        val low = FakePresenter("low", priority = 10, supported = true)
        val high = FakePresenter("high", priority = 200, supported = true)
        AuthPromptPresenterRegistry.register(low)
        AuthPromptPresenterRegistry.register(high)

        assertSame(high, AuthPromptPresenterRegistry.select(player))
    }

    @Test
    fun `跳过不支持的展示层`() {
        AuthPromptPresenterRegistry.register(FakePresenter("dialog", priority = 100, supported = false))
        val floodgate = FakePresenter("floodgate-form", priority = 200, supported = true)
        AuthPromptPresenterRegistry.register(floodgate)

        assertSame(floodgate, AuthPromptPresenterRegistry.select(player))
    }

    @Test
    fun `能力检测抛异常时视为不支持`() {
        AuthPromptPresenterRegistry.register(
            FakePresenter("broken", priority = 500, supported = true, failOnCheck = true),
        )

        assertNull(AuthPromptPresenterRegistry.select(player))
    }

    @Test
    fun `没有任何展示层时返回 null 以回退聊天`() {
        assertNull(AuthPromptPresenterRegistry.select(player))
    }

    @Test
    fun `相同 id 重复注册时后者覆盖`() {
        val first = FakePresenter("dialog", priority = 100, supported = true)
        val second = FakePresenter("dialog", priority = 100, supported = true)
        AuthPromptPresenterRegistry.register(first)
        AuthPromptPresenterRegistry.register(second)

        assertEquals(1, AuthPromptPresenterRegistry.all().size)
        assertSame(second, AuthPromptPresenterRegistry.select(player))
    }

    @Test
    fun `注销按 id 移除`() {
        AuthPromptPresenterRegistry.register(FakePresenter("dialog", priority = 100, supported = true))

        AuthPromptPresenterRegistry.unregister("dialog")

        assertTrue(AuthPromptPresenterRegistry.all().isEmpty())
    }

    @Test
    fun `默认输入长度上限是 32`() {
        assertEquals(32, AuthPromptPresenter.DEFAULT_INPUT_LENGTH_LIMIT)
        val fake = FakePresenter("plain", priority = 0, supported = true)
        assertEquals(AuthPromptPresenter.DEFAULT_INPUT_LENGTH_LIMIT, fake.inputLengthLimit)
    }
}
