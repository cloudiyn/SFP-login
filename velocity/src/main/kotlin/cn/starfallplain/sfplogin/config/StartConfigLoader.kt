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

import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * start.conf 加载器。
 *
 * 故意不使用 [cn.starfallplain.sfplogin.api.util.ConfigLoader] 和 [cn.starfallplain.sfplogin.api.util.ConfigCommentTranslatorProvider]，
 * 以确保此配置文件：
 *  1. 不受 i18n 影响（注释为硬编码中英双语）
 *  2. 不依赖任何尚未初始化的服务
 *  3. 始终是第一个被读取的配置文件
 *
 * format 字段当前版本仅作预留，后续将据此选择不同的序列化后端（gson / yaml）。
 */
object StartConfigLoader {

    private const val FILE_NAME = "start.conf"

    private val HEADER = """
        |SFP-login — 启动前置配置 / Startup Pre-configuration
        |
        |此文件是 SFP-login 第一个被读取的配置文件，不受 i18n 系统影响。
        |This file is the first configuration loaded by SFP-login and is NOT affected by the i18n system.
        |
        |请在完成所有设置后将 ready 设为 true，否则插件将拒绝启动。
        |Set `ready = true` after finishing all settings, or the plugin will refuse to start.
        |
        |--- 字段说明 / Field Description ---
        |
        |  language : 配置注释语言，影响其他配置文件首次生成时的注释语言。
        |             Locale key for config comment translation (e.g. zh_cn, en_us).
        |
        |  format   : 配置文件格式，影响其他配置文件的序列化方式。
        |             Config serialization format: hocon | gson | yaml
        |             （当前版本仅完整支持 hocon，gson/yaml 为预留选项）
        |             (Current version fully supports hocon only; gson/yaml are reserved for future use)
        |
        |  ready    : 就绪标志，必须为 true 插件才会正常启动。
        |             Must be true for the plugin to start normally.
        |
        |--- 文档 / Documentation ---
        |
        |  用户文档站  : https://docs.h2l.icu
        |  GitHub     : https://github.com/CNRan/SFP-login
        |  Issues     : https://github.com/CNRan/SFP-login/issues
        |
        |--- 社区 / Community ---
        |
        |  Discord    : https://discord.gg/dCAeNyR9TA
        |  QQ 群      : https://qm.qq.com/q/GZWVfEyokS
        |
        |--- 支持项目 / Support the Project ---
        |
        |  如果 SFP-login 对你有帮助，欢迎前往 GitHub 点一个 Star ⭐，这对项目非常重要！
        |  If SFP-login has been helpful to you, please consider giving it a Star ⭐ on GitHub!
        |  → https://github.com/CNRan/SFP-login
    """.trimMargin()

    /**
     * 从 [dataDirectory] 加载 start.conf。
     *
     * 若文件不存在，会自动生成包含默认值（ready = false）的文件，并返回该默认值。
     * 调用方应在拿到结果后立即检查 [StartConfig.ready]。
     */
    fun load(dataDirectory: Path): StartConfig {
        Files.createDirectories(dataDirectory)
        val filePath = dataDirectory.resolve(FILE_NAME)
        val fileNotExists = Files.notExists(filePath)

        val loader = HoconConfigurationLoader.builder()
            .path(filePath)
            .defaultOptions { opts -> opts.shouldCopyDefaults(true).header(HEADER) }
            .build()

        val node = if (fileNotExists) loader.createNode() else loader.load()

        // 读取各字段，缺失时使用默认值
        val language = node.node("language").let {
            if (it.virtual()) "zh_cn" else it.string ?: "zh_cn"
        }
        val format = node.node("format").let {
            if (it.virtual()) "hocon" else it.string ?: "hocon"
        }
        val ready = node.node("ready").let {
            if (it.virtual()) false else it.getBoolean(false)
        }

        val config = StartConfig(language = language, format = format, ready = ready)

        // 首次创建时（或字段缺失时）写回文件
        if (fileNotExists) {
            node.node("language").set(config.language)
            node.node("format").set(config.format)
            node.node("ready").set(config.ready)
            loader.save(node)
        }

        return config
    }
}

