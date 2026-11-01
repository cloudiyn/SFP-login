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

package cn.starfallplain.sfplogin.api.util

/**
 * 全局配置格式提供者。
 *
 * 在插件启动最早期（start.conf 读取之后、任何其他配置加载之前）
 * 由主插件根据 start.conf 的 `format` 字段绑定格式，
 * [ConfigLoader] 将据此选择对应的 ConfigurationLoader 实现。
 *
 * 若未绑定（或绑定 null），[ConfigLoader] 回退到 [ConfigFormat.HOCON]。
 */
object ConfigFormatProvider {

    @Volatile
    private var format: ConfigFormat = ConfigFormat.HOCON

    /**
     * 绑定全局配置格式（应在 start.conf 加载完成后、任何其他配置加载之前调用）。
     */
    fun bind(format: ConfigFormat) {
        this.format = format
    }

    /**
     * 获取当前配置格式，未绑定时返回 [ConfigFormat.HOCON]。
     */
    fun get(): ConfigFormat = format

    /**
     * 重置为默认格式（通常在插件卸载时调用）。
     */
    fun reset() {
        format = ConfigFormat.HOCON
    }
}

