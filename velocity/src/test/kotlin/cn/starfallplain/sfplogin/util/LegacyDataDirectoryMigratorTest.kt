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

package cn.starfallplain.sfplogin.util

import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LegacyDataDirectoryMigratorTest {

    private val logger = ComponentLogger.logger("LegacyDataDirectoryMigratorTest")

    @Test
    fun `旧目录存在时复制到空的新目录且保留旧目录`(@TempDir root: Path) {
        val plugins = root.resolve("plugins")
        val legacy = plugins.resolve("hyperzonelogin")
        Files.createDirectories(legacy.resolve("logs"))
        Files.writeString(legacy.resolve("core.conf"), "database = sqlite")
        Files.writeString(legacy.resolve("storage.db"), "binary")
        Files.writeString(legacy.resolve("logs").resolve("old.log"), "line")
        val target = plugins.resolve("sfplogin")
        Files.createDirectories(target) // 空目录，允许迁移

        val copied = LegacyDataDirectoryMigrator.migrate(target, logger)

        assertTrue(copied)
        assertEquals("database = sqlite", Files.readString(target.resolve("core.conf")))
        assertEquals("binary", Files.readString(target.resolve("storage.db")))
        assertEquals("line", Files.readString(target.resolve("logs").resolve("old.log")))
        assertTrue(Files.isDirectory(legacy), "旧目录必须保留")
        assertTrue(Files.exists(legacy.resolve("storage.db")), "旧目录内容必须保留")
    }

    @Test
    fun `新目录非空时不覆盖并保留新数据`(@TempDir root: Path) {
        val plugins = root.resolve("plugins")
        val legacy = plugins.resolve("hyperzonelogin")
        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("core.conf"), "legacy")
        val target = plugins.resolve("sfplogin")
        Files.createDirectories(target)
        Files.writeString(target.resolve("core.conf"), "current")

        val copied = LegacyDataDirectoryMigrator.migrate(target, logger)

        assertFalse(copied)
        assertEquals("current", Files.readString(target.resolve("core.conf")))
        assertTrue(Files.exists(legacy.resolve("core.conf")))
    }

    @Test
    fun `旧目录不存在时什么也不做`(@TempDir root: Path) {
        val plugins = root.resolve("plugins")
        val target = plugins.resolve("sfplogin")

        val copied = LegacyDataDirectoryMigrator.migrate(target, logger)

        assertFalse(copied)
        assertFalse(Files.exists(target))
    }
}
