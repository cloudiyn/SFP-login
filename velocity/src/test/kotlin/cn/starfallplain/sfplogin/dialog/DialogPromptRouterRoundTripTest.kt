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
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.Unpooled
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag

/**
 * dialog 提交链路往返测试：对话框声明的输入键 -> 客户端回传的自定义包 -> 提交回调。
 *
 * 覆盖 dialog 与事件循环之间的契约边界：键名一致、payload 结构可解、过期/异名包不被吞错。
 */
class DialogPromptRouterRoundTripTest {

    private val playerId = UUID.randomUUID()

    private fun player(): ConnectedPlayer = mockk<ConnectedPlayer>(relaxed = true) {
        every { uniqueId } returns playerId
        every { protocolVersion } returns ProtocolVersion.MINECRAFT_1_21_6
    }

    private fun request(): AuthPromptRequest = AuthPromptRequest(
        mode = AuthPromptMode.LOGIN,
        title = "登录",
        bodyLines = listOf("请输入密码"),
        inputs = listOf(
            AuthPromptInput(key = "password", label = "密码", maxLength = 32),
            AuthPromptInput(key = "totp", label = "两步验证码", maxLength = 6),
        ),
        submitLabel = "登录",
    )

    /** 模拟客户端：把输入值按 key 放进 payload compound，并通过 custom_click_action 包发回。 */
    private fun clientClickPayload(request: AuthPromptRequest, values: Map<String, String>): io.netty.buffer.ByteBuf {
        val payload = CompoundBinaryTag.builder().apply {
            request.inputs.forEach { input ->
                values[input.key]?.let { putString(input.key, it) }
            }
        }.build()
        assertTrue(payload.size() > 0 || values.isEmpty())

        return Unpooled.buffer().also { buffer ->
            ProtocolUtils.writeString(buffer, DialogNbtFactory.ACTION_CHANNEL_ID)
            buffer.writeBoolean(true)
            ProtocolUtils.writeBinaryTag(buffer, ProtocolVersion.MINECRAFT_1_21_6, payload)
        }
    }

    @AfterTest
    fun tearDown() {
        DialogPromptRouter.clearAll()
    }

    @Test
    fun `客户端提交的输入按对话框声明的键回传`() {
        val request = request()
        var submitted: Map<String, String>? = null
        val target = object : DialogPromptTarget {
            override fun submit(payload: CompoundBinaryTag?) {
                submitted = request.inputs
                    .mapNotNull { input -> payload?.getString(input.key)?.takeIf { it.isNotEmpty() }?.let { input.key to it } }
                    .toMap()
            }
        }
        DialogPromptRouter.register(playerId, target)

        val handled = DialogPromptRouter.tryHandle(
            player(),
            clientClickPayload(request, mapOf("password" to "secret123", "totp" to "123456")),
        )

        assertTrue(handled)
        assertEquals(mapOf("password" to "secret123", "totp" to "123456"), submitted)
    }

    @Test
    fun `只有部分输入时其余键不出现在提交结果里`() {
        val request = request()
        var submitted: Map<String, String>? = null
        DialogPromptRouter.register(
            playerId,
            object : DialogPromptTarget {
                override fun submit(payload: CompoundBinaryTag?) {
                    submitted = request.inputs
                        .mapNotNull { input -> payload?.getString(input.key)?.takeIf { it.isNotEmpty() }?.let { input.key to it } }
                        .toMap()
                }
            },
        )

        assertTrue(DialogPromptRouter.tryHandle(player(), clientClickPayload(request, mapOf("password" to "only-pass"))))
        assertEquals(mapOf("password" to "only-pass"), submitted)
    }

    @Test
    fun `payload 里带未知键时不影响已知键`() {
        val request = request()
        var submitted: Map<String, String>? = null
        DialogPromptRouter.register(
            playerId,
            object : DialogPromptTarget {
                override fun submit(payload: CompoundBinaryTag?) {
                    submitted = request.inputs
                        .mapNotNull { input -> payload?.getString(input.key)?.takeIf { it.isNotEmpty() }?.let { input.key to it } }
                        .toMap()
                }
            },
        )

        val payload = CompoundBinaryTag.builder()
            .putString("password", "pw")
            .putString("unknown", "ignored")
            .put("extra", CompoundBinaryTag.builder().putString("a", "b").build())
            .build()
        val buffer = Unpooled.buffer().also {
            ProtocolUtils.writeString(it, DialogNbtFactory.ACTION_CHANNEL_ID)
            it.writeBoolean(true)
            ProtocolUtils.writeBinaryTag(it, ProtocolVersion.MINECRAFT_1_21_6, payload)
        }

        assertTrue(DialogPromptRouter.tryHandle(player(), buffer))
        assertEquals(mapOf("password" to "pw"), submitted)
    }

    @Test
    fun `其它命名空间的自定义包不被吞掉`() {
        val buffer = Unpooled.buffer().also {
            ProtocolUtils.writeString(it, "minecraft:some_other_action")
            it.writeBoolean(false)
        }

        assertFalse(DialogPromptRouter.tryHandle(player(), buffer))
    }

    @Test
    fun `没有活跃会话时过期提交也被消费`() {
        // 会话已关闭（例如超时）后客户端仍然点了按钮：包必须被消费，不能转发给后端
        val buffer = clientClickPayload(request(), mapOf("password" to "late"))
        assertTrue(DialogPromptRouter.tryHandle(player(), buffer))
        assertTrue(DialogPromptRouter.target(playerId) == null)
    }

    @Test
    fun `对话框 NBT 的输入键与提交键一致`() {
        val request = request()
        val dialog = DialogNbtFactory.build(request)
        val inputs = dialog.getList("inputs", BinaryTagTypes.COMPOUND)

        assertEquals(
            request.inputs.map { it.key },
            inputs.map { (it as CompoundBinaryTag).getString("key") },
        )
    }
}
