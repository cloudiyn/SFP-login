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

import cn.starfallplain.sfplogin.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 登录提示框（Java dialog / 基岩表单）使用的纯文本文案。
 *
 * 展示层只接受纯文本，因此这里把 MiniMessage 渲染结果降级成纯文本；
 * 资源缺失新键时回退到代码内置文案，兼容旧数据目录。
 */
internal object OfflineDialogTexts {
    private const val NAMESPACE = "auth-offline"
    private const val DIALOG = "dialog"

    private val PLAIN_TEXT = PlainTextComponentSerializer.plainText()

    val LOGIN_TITLE: String get() = text("$DIALOG.login-title", "登录")
    val LOGIN_BODY: String get() = text("$DIALOG.login-body", "请输入离线账号密码以完成登录。")
    val REGISTER_TITLE: String get() = text("$DIALOG.register-title", "注册")
    val REGISTER_BODY: String get() = text("$DIALOG.register-body", "首次进入请先设置离线账号密码。")
    val INPUT_PASSWORD: String get() = text("$DIALOG.input-password", "密码")
    val INPUT_CONFIRM: String get() = text("$DIALOG.input-confirm", "再次输入密码")
    val INPUT_TOTP: String get() = text("$DIALOG.input-totp", "验证码（可选）")
    val SUBMIT_LOGIN: String get() = text("$DIALOG.submit-login", "登录")
    val SUBMIT_REGISTER: String get() = text("$DIALOG.submit-register", "注册")
    val EMPTY_PASSWORD: String get() = text("$DIALOG.empty-password", "密码不能为空")
    val CLOSED_HINT: String get() = text("$DIALOG.closed-hint", "已关闭登录窗口，可继续使用 /login 命令登录。")

    /**
     * 把任意组件降级成提示框可用的纯文本（同时剥离旧版 `§` 颜色码）。
     */
    fun plainText(component: net.kyori.adventure.text.Component): String {
        val serialized = runCatching { PLAIN_TEXT.serialize(component) }.getOrDefault("")
        return LEGACY_COLOR_CODE.replace(serialized, "").trim()
    }

    private fun text(key: String, fallback: String): String {
        val service = HyperZoneMessageServiceProvider.getOrNull() ?: return fallback
        val fullKey = "$NAMESPACE.$key"
        if (!service.hasKey(fullKey)) return fallback
        val rendered = runCatching { service.render(fullKey) }.getOrNull() ?: return fallback
        return plainText(rendered).ifEmpty { fallback }
    }

    private val LEGACY_COLOR_CODE = Regex("§[0-9a-fk-orA-FK-OR]")
}
