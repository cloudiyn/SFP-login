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
 * 配置文件序列化格式，由 start.conf 中的 `format` 字段决定。
 *
 * [HOCON] — Human-Optimized Config Object Notation（默认，功能最完整）
 * [GSON]  — JSON 格式，基于 configurate-gson
 * [YAML]  — YAML 格式，基于 configurate-yaml
 */
enum class ConfigFormat(
    /** start.conf 中 format 字段对应的字符串值（不区分大小写）*/
    val key: String
) {
    HOCON("hocon"),
    GSON("gson"),
    YAML("yaml");

    companion object {
        /**
         * 将 start.conf 中的原始字符串解析为 [ConfigFormat]。
         * 未知值回退到 [HOCON] 并不抛出异常，以保证向前兼容。
         */
        fun fromKey(key: String): ConfigFormat =
            entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) } ?: HOCON
    }
}

