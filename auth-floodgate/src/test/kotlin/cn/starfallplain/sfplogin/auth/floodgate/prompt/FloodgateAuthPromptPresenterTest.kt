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

package cn.starfallplain.sfplogin.auth.floodgate.prompt

import cn.starfallplain.sfplogin.api.auth.AuthPromptInput
import cn.starfallplain.sfplogin.api.auth.AuthPromptMode
import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import cn.starfallplain.sfplogin.api.auth.AuthPromptSession
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import com.velocitypowered.api.proxy.Player
import io.mockk.every
import io.mockk.mockk
import org.geysermc.cumulus.Form
import org.geysermc.floodgate.api.FloodgateApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FloodgateAuthPromptPresenterTest {

    private val playerId: UUID = UUID.randomUUID()
    private val hyperPlayer: HyperZonePlayer = mockk(relaxed = true)
    private val proxyPlayer: Player = mockk(relaxed = true)

    private var floodgatePlayer = true
    private var acceptForm = true
    private var capturedForm: Form<*>? = null
    private var capturedTarget: UUID? = null

    private lateinit var apiHolder: FloodgateApiHolder

    private val request = AuthPromptRequest(
        mode = AuthPromptMode.LOGIN,
        title = "登录",
        bodyLines = listOf("请输入密码"),
        inputs = listOf(
            AuthPromptInput(key = "password", label = "密码", maxLength = 32),
            AuthPromptInput(key = "totp", label = "动态验证码", maxLength = 6),
        ),
        submitLabel = "提交",
    )

    @BeforeEach
    fun setUp() {
        floodgatePlayer = true
        acceptForm = true
        capturedForm = null
        capturedTarget = null
        every { hyperPlayer.getProxyPlayerOrNull() } returns proxyPlayer
        every { proxyPlayer.uniqueId } returns playerId
        apiHolder = object : FloodgateApiHolder(mockk<FloodgateApi>(relaxed = true)) {
            override fun isFloodgatePlayer(uuid: UUID): Boolean = floodgatePlayer

            override fun sendForm(uuid: UUID, form: Form<*>): Boolean {
                capturedForm = form
                capturedTarget = uuid
                return acceptForm
            }
        }
    }

    private fun presenter() = FloodgateAuthPromptPresenter(apiHolder)

    @Test
    fun `只对 Floodgate 玩家可用`() {
        assertTrue(presenter().isSupported(hyperPlayer))

        floodgatePlayer = false

        assertFalse(presenter().isSupported(hyperPlayer))
    }

    @Test
    fun `展示层优先级高于 dialog`() {
        assertTrue(presenter().priority > 100)
        assertEquals("floodgate-form", presenter().id)
    }

    @Test
    fun `下发表单时带上标题正文与输入项`() {
        val session = presenter().present(hyperPlayer, request, {}, {})

        assertNotNull(session)
        assertEquals(playerId, capturedTarget)
        val json = capturedForm!!.getJsonData()
        assertTrue(json.contains("登录"), json)
        assertTrue(json.contains("请输入密码"), json)
        assertTrue(json.contains("密码"), json)
        assertTrue(json.contains("动态验证码"), json)
    }

    @Test
    fun `提交表单时按输入顺序回调取值`() {
        val values = AtomicReference<Map<String, String>>(emptyMap())
        presenter().present(hyperPlayer, request, { values.set(it) }, {})!!

        capturedForm!!.getResponseHandler().accept("""[null,"secret","123456"]""")

        assertEquals("secret", values.get()["password"])
        assertEquals("123456", values.get()["totp"])
    }

    @Test
    fun `关闭表单时回调关闭而不是提交`() {
        val closed = AtomicInteger()
        val submitted = AtomicInteger()
        presenter().present(hyperPlayer, request, { submitted.incrementAndGet() }, { closed.incrementAndGet() })!!

        capturedForm!!.getResponseHandler().accept("null")

        assertEquals(1, closed.get())
        assertEquals(0, submitted.get())
    }

    @Test
    fun `表单无法下发时不创建会话`() {
        acceptForm = false

        val session: AuthPromptSession? = presenter().present(hyperPlayer, request, {}, {})

        assertNull(session)
    }

    @Test
    fun `关闭回调只触发一次`() {
        val closed = AtomicInteger()
        val session = presenter().present(hyperPlayer, request, {}, { closed.incrementAndGet() })!!

        session.close()
        session.close()

        assertEquals(1, closed.get())
    }

    @Test
    fun `会话关闭后忽略迟到的表单响应`() {
        val submitted = AtomicInteger()
        val session = presenter().present(hyperPlayer, request, { submitted.incrementAndGet() }, {})!!

        session.close()
        capturedForm!!.getResponseHandler().accept("""[null,"secret","123456"]""")

        assertEquals(0, submitted.get())
    }
}
