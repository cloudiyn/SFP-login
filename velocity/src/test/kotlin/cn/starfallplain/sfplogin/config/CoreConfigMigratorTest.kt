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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path

class CoreConfigMigratorTest {

    @TempDir
    lateinit var dataDirectory: Path

    private val logger: ComponentLogger = ComponentLogger.logger("CoreConfigMigratorTest")

    @Test
    fun `migrates backend mode to outpre and backs up the original file`() {
        val original = "vServer { mode = \"backend\" }"
        val node = writeConfig(original)

        assertTrue(CoreConfigMigrator.migrate(node, dataDirectory, logger))

        assertEquals(CoreConfigMigrator.TARGET_MODE, node.node("vServer", "mode").string)
        val backups = backups()
        assertEquals(1, backups.size)
        assertEquals(original, Files.readString(backups.single()))
    }

    @Test
    fun `migrates uppercase backend mode`() {
        val node = writeConfig("vServer { mode = \"BACKEND\" }")

        assertTrue(CoreConfigMigrator.migrate(node, dataDirectory, logger))

        assertEquals(CoreConfigMigrator.TARGET_MODE, node.node("vServer", "mode").string)
    }

    @Test
    fun `leaves outpre mode untouched and writes no backup`() {
        val node = writeConfig("vServer { mode = \"outpre\" }")

        assertFalse(CoreConfigMigrator.migrate(node, dataDirectory, logger))

        assertEquals(CoreConfigMigrator.TARGET_MODE, node.node("vServer", "mode").string)
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `handles missing config file without failing`() {
        val node = HoconConfigurationLoader.builder()
            .path(dataDirectory.resolve(CoreConfigMigrator.CONFIG_FILE_NAME))
            .build()
            .createNode()
        node.node("vServer", "mode").set(CoreConfigMigrator.LEGACY_BACKEND_MODE)

        // 文件不存在：仍然完成迁移，只是没有可备份的原文件
        assertTrue(CoreConfigMigrator.migrate(node, dataDirectory, logger))

        assertEquals(CoreConfigMigrator.TARGET_MODE, node.node("vServer", "mode").string)
        assertTrue(backups().isEmpty())
    }

    private fun writeConfig(content: String): org.spongepowered.configurate.CommentedConfigurationNode {
        val file = dataDirectory.resolve(CoreConfigMigrator.CONFIG_FILE_NAME)
        Files.writeString(file, content)
        return HoconConfigurationLoader.builder().path(file).build().load()
    }

    private fun backups(): List<Path> {
        if (!Files.isDirectory(dataDirectory)) {
            return emptyList()
        }
        return Files.list(dataDirectory).use { stream ->
            stream.filter { it.fileName.toString().startsWith(CoreConfigMigrator.CONFIG_FILE_NAME + ".bak-") }
                .toList()
        }
    }
}
