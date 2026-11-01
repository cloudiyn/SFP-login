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

package cn.starfallplain.sfplogin.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class AuthConfig(
    @JvmField
    @Comment("config.core.auth.disableRegistration")
    val disableRegistration: List<String> = emptyList(),
    @JvmField
    @Comment("config.core.auth.prompt")
    val prompt: AuthPromptConfig = AuthPromptConfig(),
)

/**
 * 登录提示框（Java dialog / 基岩表单）配置。
 *
 * 关闭后所有客户端回退到等待区聊天命令（`/login`、`/register`）。
 */
@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class AuthPromptConfig(
    @JvmField
    @Comment("config.core.auth.prompt.dialogEnabled")
    val dialogEnabled: Boolean = true,
    @JvmField
    @Comment("config.core.auth.prompt.dialogTimeoutSeconds")
    val dialogTimeoutSeconds: Long = 120L,
    @JvmField
    @Comment("config.core.auth.prompt.inputLengthLimit")
    val inputLengthLimit: Int = 32,
)

