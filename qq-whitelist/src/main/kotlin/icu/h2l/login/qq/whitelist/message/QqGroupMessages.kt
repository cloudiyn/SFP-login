/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.login.qq.whitelist.message

import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * QQ 群内纯文本文案。
 *
 * 与 [icu.h2l.api.message.HyperZoneMessageService] 共用 `messages/qq-whitelist/<locale>.conf`，
 * 但群里不能使用 MiniMessage 标签，所以这里直接读取原始字符串并用 `<占位符>` 做简单替换。
 */
class QqGroupMessages(
    private val dataDirectory: Path,
    private val language: String
) {
    private var localeMaps: List<Map<String, String>> = loadAll()

    /**
     * 渲染一条群内文案。
     *
     * @param key 相对 `qq-whitelist` 根的路径，例如 `group.bind-success`
     */
    fun text(key: String, vararg placeholders: Pair<String, String>): String {
        val template = localeMaps.firstNotNullOfOrNull { it[key] } ?: key
        return placeholders.fold(template) { acc, (name, value) -> acc.replace("<$name>", value) }
    }

    /**
     * 按指定语言渲染一条玩家可见文案。
     *
     * 与群内文案共用同一份 `messages/qq-whitelist/<locale>.conf`，
     * 但玩家侧允许使用 MiniMessage 不方便，这里返回纯文本组件。
     */
    fun playerText(language: String?, key: String, vararg placeholders: Pair<String, String>): String {
        val normalized = (language ?: this.language).replace('-', '_')
        val template = loadLocale(normalized)?.get(key)
            ?: localeMaps.firstNotNullOfOrNull { it[key] }
            ?: key
        return placeholders.fold(template) { acc, (name, value) -> acc.replace("<$name>", value) }
    }

    /**
     * 重新加载文案（消息文件热更新后调用）。
     */
    fun reload() {
        localeMaps = loadAll()
    }

    private fun loadAll(): List<Map<String, String>> {
        val locales = buildList {
            add(language)
            add("zh_cn")
            add("en_us")
        }.map { it.replace('-', '_') }.distinct()
        return locales.mapNotNull(::loadLocale)
    }

    private fun loadLocale(locale: String): Map<String, String>? {
        val path = dataDirectory.resolve("messages").resolve("qq-whitelist").resolve("$locale.conf")
        if (!Files.exists(path)) return null
        return runCatching {
            val node = HoconConfigurationLoader.builder().path(path).build().load()
            val root = node.node("qq-whitelist")
            if (root.virtual()) return@runCatching emptyMap()
            val result = LinkedHashMap<String, String>()
            flatten(root, "", result)
            result
        }.getOrNull()
    }

    private fun flatten(node: ConfigurationNode, prefix: String, target: MutableMap<String, String>) {
        if (node.isMap) {
            node.childrenMap().forEach { (key, child) ->
                val name = key.toString()
                flatten(child, if (prefix.isEmpty()) name else "$prefix.$name", target)
            }
            return
        }
        val raw = node.string ?: return
        if (!node.virtual() && prefix.isNotEmpty()) {
            target[prefix] = raw
        }
    }
}
