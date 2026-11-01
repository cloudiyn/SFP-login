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

package cn.starfallplain.sfplogin.api.module

/**
 * 模块描述符：以数据描述一个模块的身份、开关与装配方式。
 *
 * 引入描述符的目的是把"模块有哪些、叫什么、受哪个配置项控制、依赖谁"从主类里的
 * 一串硬编码调用，收敛成一张可遍历的表。装配方（主插件）只按表遍历，
 * 新增模块不再需要改动主流程；独立发布的同名 jar 也会用同一个 [id] 参与去重。
 *
 * @property id 模块唯一标识，同时也是独立分发时的插件 id（例如 `hzl-auth-offline`）
 * @property displayName 日志里展示的名字
 * @property configKey 在 `modules` 配置块中控制开关的键（例如 `authOffline`）
 * @property implementationClassName 内置实现的完整类名；为 `null` 表示只有独立 jar 形态
 * @property requiredPluginIds 启用前必须已加载的插件 id（例如 floodgate）
 * @property credentialChannelIds 该模块注册的凭证渠道 id
 */
data class ModuleDescriptor(
    val id: String,
    val displayName: String,
    val configKey: String,
    val implementationClassName: String? = null,
    val requiredPluginIds: List<String> = emptyList(),
    val credentialChannelIds: Set<String> = emptySet(),
) {
    /** 是否为可内置到主 jar 的模块。 */
    val supportsEmbedded: Boolean
        get() = implementationClassName != null
}
