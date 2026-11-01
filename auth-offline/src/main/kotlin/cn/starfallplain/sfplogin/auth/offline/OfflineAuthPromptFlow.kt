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

package cn.starfallplain.sfplogin.auth.offline

import cn.starfallplain.sfplogin.api.auth.AuthPromptInput
import cn.starfallplain.sfplogin.api.auth.AuthPromptMode
import cn.starfallplain.sfplogin.api.auth.AuthPromptPresenter
import cn.starfallplain.sfplogin.api.auth.AuthPromptPresenterRegistry
import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import cn.starfallplain.sfplogin.api.auth.AuthPromptSession
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.auth.offline.service.OfflineAuthService
import com.velocitypowered.api.proxy.Player
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 离线渠道的提示框接入。
 *
 * 等待区加入时优先用注册表中可用性最高的展示层（Java dialog、基岩表单）弹出登录/注册窗口，
 * 提交后直接走 [OfflineAuthService]，失败时在原窗口内回显错误。
 * 没有可用展示层时返回 false，由调用方回退到聊天提示。
 */
class OfflineAuthPromptFlow(
    private val authService: OfflineAuthService,
) {

    /**
     * 尝试为玩家弹出登录/注册提示框。
     *
     * @return 已展示时返回 true；无可用展示层或展示失败返回 false
     */
    fun presentFor(player: Player, hyperPlayer: HyperZonePlayer): Boolean {
        val presenter = AuthPromptPresenterRegistry.select(hyperPlayer) ?: return false
        return Presentation(player, hyperPlayer, presenter).open()
    }

    private inner class Presentation(
        private val player: Player,
        private val hyperPlayer: HyperZonePlayer,
        private val presenter: AuthPromptPresenter,
    ) {

        private val completed = AtomicBoolean(false)
        private val sessionRef = AtomicReference<AuthPromptSession?>(null)
        private val requestRef = AtomicReference(buildInitialRequest())

        fun open(): Boolean {
            val session = presenter.present(
                hyperPlayer,
                requestRef.get(),
                onSubmit = ::onSubmit,
                onClosed = ::onClosed,
            ) ?: return false
            sessionRef.set(session)
            return true
        }

        private fun buildInitialRequest(): AuthPromptRequest {
            val limit = presenter.inputLengthLimit.coerceIn(MIN_INPUT_LENGTH, MAX_INPUT_LENGTH)
            val passwordInput = AuthPromptInput(KEY_PASSWORD, OfflineDialogTexts.INPUT_PASSWORD, limit)
            return if (authService.hasOfflineAccount(player)) {
                val inputs = ArrayList<AuthPromptInput>(2)
                inputs += passwordInput
                if (authService.isTotpEnabledFor(player)) {
                    inputs += AuthPromptInput(KEY_TOTP, OfflineDialogTexts.INPUT_TOTP, TOTP_CODE_LENGTH)
                }
                AuthPromptRequest(
                    mode = AuthPromptMode.LOGIN,
                    title = OfflineDialogTexts.LOGIN_TITLE,
                    bodyLines = listOf(OfflineDialogTexts.LOGIN_BODY),
                    inputs = inputs,
                    submitLabel = OfflineDialogTexts.SUBMIT_LOGIN,
                )
            } else {
                AuthPromptRequest(
                    mode = AuthPromptMode.REGISTER,
                    title = OfflineDialogTexts.REGISTER_TITLE,
                    bodyLines = listOf(OfflineDialogTexts.REGISTER_BODY),
                    inputs = listOf(
                        passwordInput,
                        AuthPromptInput(KEY_CONFIRM, OfflineDialogTexts.INPUT_CONFIRM, limit),
                    ),
                    submitLabel = OfflineDialogTexts.SUBMIT_REGISTER,
                )
            }
        }

        private fun onSubmit(values: Map<String, String>) {
            if (completed.get()) return
            val request = requestRef.get()
            when (request.mode) {
                AuthPromptMode.REGISTER -> handleRegister(values)
                AuthPromptMode.LOGIN -> handleLogin(values)
            }
        }

        private fun handleRegister(values: Map<String, String>) {
            val password = values[KEY_PASSWORD].orEmpty()
            val confirm = values[KEY_CONFIRM].orEmpty()
            if (password.isEmpty()) {
                showError(OfflineDialogTexts.EMPTY_PASSWORD)
                return
            }
            if (password != confirm) {
                showError(OfflineDialogTexts.plainText(OfflineAuthMessages.PASSWORD_MISMATCH))
                return
            }
            val result = runCatching { authService.register(player, password) }.getOrElse { throwable ->
                showError(throwable.message ?: OfflineDialogTexts.plainText(OfflineAuthMessages.REGISTER_FAILED))
                return
            }
            finish(result.success, result.message, OfflineAuthMessages.REGISTER_FAILED)
        }

        private fun handleLogin(values: Map<String, String>) {
            val password = values[KEY_PASSWORD].orEmpty()
            if (password.isEmpty()) {
                showError(OfflineDialogTexts.EMPTY_PASSWORD)
                return
            }
            val totpCode = values[KEY_TOTP]?.trim()?.takeIf { it.isNotEmpty() }
            val result = runCatching { authService.login(player, password, totpCode) }.getOrElse { throwable ->
                showError(throwable.message ?: OfflineDialogTexts.plainText(OfflineAuthMessages.LOGIN_WRONG_PASSWORD))
                return
            }
            finish(result.success, result.message, OfflineAuthMessages.LOGIN_WRONG_PASSWORD)
        }

        private fun finish(success: Boolean, message: net.kyori.adventure.text.Component, failureFallback: net.kyori.adventure.text.Component) {
            if (!success) {
                val text = OfflineDialogTexts.plainText(message)
                showError(text.ifEmpty { OfflineDialogTexts.plainText(failureFallback) })
                return
            }
            completed.set(true)
            sessionRef.get()?.close()
            hyperPlayer.sendMessage(message)
        }

        private fun showError(message: String) {
            val session = sessionRef.get() ?: return
            val request = requestRef.get()
            val updated = request.copy(errorLine = message.ifEmpty { request.errorLine })
            requestRef.set(updated)
            session.show(updated)
        }

        private fun onClosed() {
            if (completed.get()) return
            hyperPlayer.sendMessage(net.kyori.adventure.text.Component.text(OfflineDialogTexts.CLOSED_HINT))
            authService.getJoinPrompts(player).forEach { hyperPlayer.sendMessage(it) }
        }
    }

    companion object {
        /** 密码输入框下限：太短会让客户端不显示输入框。 */
        private const val MIN_INPUT_LENGTH = 8

        /** 密码输入框上限：避免自定义展示层渲染异常。 */
        private const val MAX_INPUT_LENGTH = 128

        /** TOTP 验证码固定 6 位。 */
        private const val TOTP_CODE_LENGTH = 6

        private const val KEY_PASSWORD = "password"
        private const val KEY_CONFIRM = "confirm"
        private const val KEY_TOTP = "totp"
    }
}
