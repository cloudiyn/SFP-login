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

import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.spongepowered.configurate.ConfigurationNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 主配置迁移器。
 *
 * 当前只处理一件事：`vServer.mode: backend` 已被移除，启动时自动改写为 `outpre`。
 * 迁移前的文件会备份为 `core.conf.bak-<时间戳>`，备份失败不阻塞启动；
 * 迁移本身任何异常都只记 WARN，随后继续使用默认值启动。
 */
object CoreConfigMigrator {

    /** 配置文件名，与主类 `loadCoreConfig()` 保持一致。 */
    const val CONFIG_FILE_NAME = "core.conf"

    /** 要迁移的旧模式。 */
    const val LEGACY_BACKEND_MODE = "backend"

    /** 迁移后的目标模式。 */
    const val TARGET_MODE = "outpre"

    private const val BACKUP_SUFFIX_PREFIX = ".bak-"
    private val BACKUP_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * 若配置仍处于 `backend` 模式，则备份原文件并把节点改写为 `outpre`。
     *
     * @return true 表示发生了迁移，调用方需要把配置写回磁盘。
     */
    fun migrate(node: ConfigurationNode, dataDirectory: Path, logger: ComponentLogger): Boolean {
        return try {
            val modeNode = node.node("vServer", "mode")
            val currentMode = modeNode.string?.trim()?.lowercase()
            if (currentMode != LEGACY_BACKEND_MODE) {
                return false
            }

            val backup = backupBeforeMigration(dataDirectory.resolve(CONFIG_FILE_NAME), logger)
            modeNode.set(TARGET_MODE)
            logger.warn(
                "[ConfigMigration] vServer.mode=backend 已移除，已自动切换为 outpre" +
                    if (backup != null) "（原配置已备份为 ${backup.fileName}）" else "（备份失败，请自行保留原配置）"
            )
            true
        } catch (throwable: Throwable) {
            logger.warn("[ConfigMigration] 配置迁移失败，将继续使用默认值启动: ${throwable.message}")
            false
        }
    }

    private fun backupBeforeMigration(configFile: Path, logger: ComponentLogger): Path? {
        if (!Files.isRegularFile(configFile)) {
            return null
        }
        return runCatching {
            val timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT)
            var target = configFile.resolveSibling(configFile.fileName.toString() + BACKUP_SUFFIX_PREFIX + timestamp)
            var index = 1
            while (Files.exists(target)) {
                target = configFile.resolveSibling(
                    configFile.fileName.toString() + BACKUP_SUFFIX_PREFIX + timestamp + "-" + index
                )
                index++
            }
            Files.copy(configFile, target, StandardCopyOption.COPY_ATTRIBUTES)
            target
        }.onFailure { throwable ->
            logger.warn("[ConfigMigration] 备份 $configFile 失败: ${throwable.message}")
        }.getOrNull()
    }
}
