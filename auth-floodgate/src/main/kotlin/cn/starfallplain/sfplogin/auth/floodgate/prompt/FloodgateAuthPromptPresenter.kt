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

import cn.starfallplain.sfplogin.api.auth.AuthPromptPresenter
import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import cn.starfallplain.sfplogin.api.auth.AuthPromptSession
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.geysermc.cumulus.CustomForm
import org.geysermc.cumulus.component.util.ComponentType
import org.geysermc.cumulus.response.CustomFormResponse

/**
 * 基于 Floodgate / Cumulus 自定义表单的基岩版认证提示框。
 *
 * 优先级高于 Java dialog：基岩客户端无法渲染 Java 版 dialog，
 * 只能通过表单接收输入。表单提交结果由 Floodgate 在代理线程回调。
 */
class FloodgateAuthPromptPresenter(
    private val apiHolder: FloodgateApiHolder = FloodgateApiHolder(),
    override val inputLengthLimit: Int = AuthPromptPresenter.DEFAULT_INPUT_LENGTH_LIMIT,
) : AuthPromptPresenter {

    override val id: String = PRESENTER_ID

    override val priority: Int = PRIORITY

    override fun isSupported(player: HyperZonePlayer): Boolean {
        val proxyPlayer = player.getProxyPlayerOrNull() ?: return false
        return runCatching { apiHolder.isFloodgatePlayer(proxyPlayer.uniqueId) }.getOrDefault(false)
    }

    override fun present(
        player: HyperZonePlayer,
        request: AuthPromptRequest,
        onSubmit: (Map<String, String>) -> Unit,
        onClosed: () -> Unit,
    ): AuthPromptSession? {
        val proxyPlayer = player.getProxyPlayerOrNull() ?: return null
        if (!isSupported(player)) return null
        val session = FormSession(apiHolder, proxyPlayer.uniqueId, onSubmit, onClosed)
        return if (session.send(request)) session else null
    }

    private class FormSession(
        private val apiHolder: FloodgateApiHolder,
        private val playerId: UUID,
        private val onSubmit: (Map<String, String>) -> Unit,
        private val onClosed: () -> Unit,
    ) : AuthPromptSession {

        private val closed = AtomicBoolean(false)

        override fun show(request: AuthPromptRequest) {
            send(request)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { onClosed() }
        }

        fun send(request: AuthPromptRequest): Boolean {
            if (closed.get()) return false
            val builder = CustomForm.builder().title(request.title)
            val body = bodyText(request)
            if (body.isNotEmpty()) {
                builder.label(body)
            }
            request.inputs.forEach { input ->
                builder.input(input.label, "", "")
            }
            builder.responseHandler { form, rawResponse -> handleResponse(form, rawResponse, request) }
            return runCatching { apiHolder.sendForm(playerId, builder.build()) }.getOrDefault(false)
        }

        private fun handleResponse(form: CustomForm, rawResponse: String, request: AuthPromptRequest) {
            if (closed.get()) return
            val isClosed = runCatching { form.isClosed(rawResponse) }.getOrDefault(true)
            if (isClosed) {
                close()
                return
            }
            val response = runCatching { form.parseResponse(rawResponse) }.getOrNull() ?: return
            val values = readValues(form, response, request)
            runCatching { onSubmit(values) }
        }

        private fun readValues(
            form: CustomForm,
            response: CustomFormResponse,
            request: AuthPromptRequest,
        ): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            val content = runCatching { form.content }.getOrNull().orEmpty()
            if (content.isEmpty()) {
                // 拿不到表单内容时退化为按输入顺序读取
                request.inputs.forEachIndexed { index, input ->
                    val value = runCatching { response.asInput(index) }.getOrNull()?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        values[input.key] = value
                    }
                }
                return values
            }
            // 基岩回传的数组按“所有控件”给出，标签位是 null，所以必须按控件类型计算下标
            var inputIndex = 0
            content.forEachIndexed { componentIndex, component ->
                if (component?.type() != ComponentType.INPUT) return@forEachIndexed
                val key = request.inputs.getOrNull(inputIndex)?.key
                inputIndex++
                if (key == null) return@forEachIndexed
                val value = runCatching { response.asInput(componentIndex) }.getOrNull()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    values[key] = value
                }
            }
            return values
        }

        private fun bodyText(request: AuthPromptRequest): String {
            val lines = ArrayList<String>(request.bodyLines.size + 1)
            lines += request.bodyLines
            request.errorLine?.takeIf { it.isNotBlank() }?.let { lines += it }
            return lines.joinToString("\n")
        }
    }

    companion object {
        /** 展示层 ID。 */
        const val PRESENTER_ID = "floodgate-form"

        /** 高于 Java dialog（100），基岩玩家优先走表单。 */
        const val PRIORITY = 200
    }
}
