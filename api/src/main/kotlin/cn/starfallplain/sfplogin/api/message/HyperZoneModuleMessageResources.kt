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

package cn.starfallplain.sfplogin.api.message

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 帮助子模块把内置消息资源复制到数据目录中的工具入口。
 */
object HyperZoneModuleMessageResources {
    /**
     * 将模块 jar 内打包的 locale 文件复制到 `messages/<namespace>` 目录。
     *
     * 已存在的目标文件会被保留，不会覆盖用户自定义内容。
     */
    fun copyBundledLocales(
        dataDirectory: Path,
        namespace: String,
        classLoader: ClassLoader,
        locales: List<String> = listOf("en_us", "zh_cn", "ru_ru")
    ) {
        val messageDir = dataDirectory.resolve("messages").resolve(namespace)
        Files.createDirectories(messageDir)

        locales.forEach { localeKey ->
            val target = messageDir.resolve("$localeKey.conf")
            if (Files.exists(target)) {
                return@forEach
            }

            val resourcePath = "messages/$namespace/$localeKey.conf"
            val resource = classLoader.getResourceAsStream(resourcePath) ?: return@forEach
            resource.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        HyperZoneMessageServiceProvider.getOrNull()?.reload()
    }
}

