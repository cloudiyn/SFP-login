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

package cn.starfallplain.sfplogin.config.i18n

import cn.starfallplain.sfplogin.api.util.ConfigCommentTranslator
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.io.StringReader
import java.util.Locale

/**
 * 配置文件注释 i18n 服务。
 *
 * 从插件内置资源（config-comments/{locale}.conf）加载配置注释翻译表，
 * 根据服务端 JVM 区域自动选择语言，在配置首次生成时将翻译键替换为本地化文本。
 *
 * 翻译键格式：config.{模块}.{字段路径}，例如 "config.core.database"。
 */
class ConfigCommentI18nService(
    private val logger: ComponentLogger,
    /**
     * 覆盖语言（来自 defaultLocale 配置），null 时自动检测 JVM 区域。
     */
    private val defaultLocaleOverride: String? = null,
) : ConfigCommentTranslator {

    private val localeNodes: Map<String, ConfigurationNode> = loadBundledLocales()

    /**
     * 翻译给定的配置注释键，返回本地化文本；键不存在则返回 null（保留原键）。
     */
    override fun translate(key: String): String? {
        if (!key.startsWith("config.")) return null
        val candidates = buildLocaleCandidates()
        for (locale in candidates) {
            val node = localeNodes[locale] ?: continue
            // 翻译文件使用带引号的平铺键（"config.db.sqlite" = "value"），用单 key 直接查找
            val value = node.node(key).string
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun buildLocaleCandidates(): List<String> {
        val result = LinkedHashSet<String>()
        defaultLocaleOverride?.let { normalizeLocale(it)?.let(result::add) }
        normalizeLocale(Locale.getDefault().toLanguageTag())?.let(result::add)
        normalizeLocale(Locale.getDefault().language)?.let(result::add)
        result += DEFAULT_LOCALE
        return result.toList()
    }

    private fun normalizeLocale(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('-', '_')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            "en" -> "en_us"
            "zh", "zh_hans", "zh_hans_cn" -> "zh_cn"
            "ru" -> "ru_ru"
            else -> normalized
        }
    }

    private fun loadBundledLocales(): Map<String, ConfigurationNode> {
        val result = mutableMapOf<String, ConfigurationNode>()
        for (locale in BUNDLED_LOCALES) {
            val resourcePath = "$RESOURCE_DIR/$locale.conf"
            val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) {
                logger.warn("[ConfigCommentI18n] 未找到内置翻译资源：{}", resourcePath)
                continue
            }
            runCatching {
                val text = resource.use { it.readBytes().toString(Charsets.UTF_8) }
                val node = HoconConfigurationLoader.builder()
                    .source { StringReader(text).buffered() }
                    .build()
                    .load()
                result[locale] = node
            }.onFailure { e ->
                logger.warn("[ConfigCommentI18n] 加载翻译资源失败：{} — {}", resourcePath, e.message)
            }
        }
        return result
    }

    companion object {
        private const val RESOURCE_DIR = "config-comments"
        private const val DEFAULT_LOCALE = "en_us"
        private val BUNDLED_LOCALES = listOf("zh_cn", "en_us")
    }
}

