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

import cn.starfallplain.sfplogin.api.log.warn
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.gson.GsonConfigurationLoader
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path



object ConfigLoader {
    /**
     * Unified configuration loader supporting HOCON / Gson / YAML formats.
     * The format is determined by [ConfigFormatProvider], which is bound from start.conf at startup.
     * @param dataDirectory The directory containing the configuration file
     * @param fileName The name of the configuration file (e.g., "remap.conf")
     * @param nodePath The path within the configuration file. Use an array of keys for nested structures.
     * @param defaultProvider Provides the default config object if reading fails or file empty
     * @param postLoadHook A hook that gets invoked immediately after loading/getting the config. Returning a non-null instance overrides the default/loaded result.
     * @param forceSaveHook Whether to save the file back, usually true if first creation, but can be customized
     */
    inline fun <reified T : Any> loadConfig(
        dataDirectory: Path,
        fileName: String,
        nodePath: Array<String> = emptyArray(),
        defaultProvider: () -> T,
        noinline postLoadHook: ((ConfigurationNode, T, Boolean) -> T)? = null,
        noinline forceSaveHook: ((ConfigurationNode, Boolean) -> Boolean) = { _, firstCreation -> firstCreation }
    ): T {
        val path = dataDirectory.resolve(fileName)
        val fileNotExists = Files.notExists(path)
        val loader = buildLoader(path)
        val node = loader.load()
        val targetNode = if (nodePath.isEmpty()) node else node.node(*nodePath)

        val firstCreation = fileNotExists || targetNode.virtual()

        val loaded = runCatching { targetNode.get(T::class.java) }.getOrNull() ?: defaultProvider()

        // Detect schema mismatch: compare actual file keys with expected keys from a freshly serialised default instance
        if (!targetNode.virtual() && targetNode.isMap) {
            runCatching {
                val tempNode = loader.createNode()
                tempNode.set(T::class.java, loaded)
                val expectedKeys = tempNode.childrenMap().keys.map { it.toString() }.toSet()
                val actualKeys = targetNode.childrenMap().keys.map { it.toString() }.toSet()
                val extraKeys = actualKeys - expectedKeys
                val missingKeys = expectedKeys - actualKeys
                if (extraKeys.isNotEmpty() || missingKeys.isNotEmpty()) {
                    warn {
                        buildString {
                            append("[ConfigLoader] Schema mismatch detected in \"$fileName\"")
                            if (nodePath.isNotEmpty()) append(" (node: ${nodePath.joinToString(".")})")
                            append(". It is recommended to delete the file and let it regenerate.")
                            if (extraKeys.isNotEmpty()) append(" Extra keys (no longer used): $extraKeys.")
                            if (missingKeys.isNotEmpty()) append(" Missing keys (new defaults will be used): $missingKeys.")
                        }
                    }
                }
            }
        }

        val finalConfig = postLoadHook?.invoke(targetNode, loaded, firstCreation) ?: loaded
        val shouldSave = forceSaveHook(targetNode, firstCreation)

        if (shouldSave) {
            targetNode.set(finalConfig)
            val commentedNode = node as? CommentedConfigurationNode
            if (commentedNode != null) {
                ConfigCommentTranslatorProvider.getOrNull()?.let { translator ->
                    translateNodeComments(commentedNode, translator)
                }
            }
            loader.save(node)
        }
        return finalConfig
    }

    /**
     * 根据 [ConfigFormatProvider] 当前格式和 [path] 构建对应的 ConfigurationLoader。
     *
     * - [ConfigFormat.HOCON] → [HoconConfigurationLoader]（支持 header 注释）
     * - [ConfigFormat.GSON]  → [GsonConfigurationLoader]（JSON，不支持 header）
     * - [ConfigFormat.YAML]  → [YamlConfigurationLoader]（YAML，不支持 header）
     *
     * 所有格式均注册 Kotlin data-class 字段发现器，确保 ObjectMapper 正常工作。
     */
    @PublishedApi
    internal fun buildLoader(path: Path): org.spongepowered.configurate.loader.AbstractConfigurationLoader<*> {
        val objectMapperFactory = ObjectMapper.factoryBuilder()
            .addDiscoverer(dataClassFieldDiscoverer())
            .build()

        return when (ConfigFormatProvider.get()) {
            ConfigFormat.GSON -> GsonConfigurationLoader.builder()
                .defaultOptions { opts: ConfigurationOptions ->
                    opts.shouldCopyDefaults(true)
                        .serializers { s -> s.registerAnnotatedObjects(objectMapperFactory) }
                }
                .path(path)
                .build()

            ConfigFormat.YAML -> YamlConfigurationLoader.builder()
                .defaultOptions { opts: ConfigurationOptions ->
                    opts.shouldCopyDefaults(true)
                        .serializers { s -> s.registerAnnotatedObjects(objectMapperFactory) }
                }
                .path(path)
                .build()

            ConfigFormat.HOCON -> HoconConfigurationLoader.builder()
                .defaultOptions { opts: ConfigurationOptions ->
                    opts.shouldCopyDefaults(true)
                        .serializers { s -> s.registerAnnotatedObjects(objectMapperFactory) }
                }
                .path(path)
                .build()
        }
    }

    @PublishedApi
    internal fun translateNodeComments(node: CommentedConfigurationNode, translator: ConfigCommentTranslator) {
        val comment = node.comment()
        if (comment != null) {
            val translated = translator.translate(comment.trim())
            if (translated != null) {
                node.comment(translated)
            }
        }
        if (node.isMap) {
            node.childrenMap().values.forEach { child -> translateNodeComments(child, translator) }
        } else if (node.isList) {
            node.childrenList().forEach { child -> translateNodeComments(child, translator) }
        }
    }
}
