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

package icu.h2l.login.message

import icu.h2l.login.config.MessagesConfig
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class MessageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `normalizes short and hyphenated locale keys`() {
        val service = newService()

        assertEquals("en_us", service.normalizeLocaleKey("en"))
        assertEquals("zh_cn", service.normalizeLocaleKey("zh-CN"))
        assertEquals("ru_ru", service.normalizeLocaleKey("ru_ru"))
    }

    @Test
    fun `copies bundled locale files on load`() {
        val service = newService()

        service.load(MessagesConfig())

        assertTrue(Files.exists(tempDir.resolve("messages/en_us.conf")))
        assertTrue(Files.exists(tempDir.resolve("messages/zh_cn.conf")))
        assertTrue(Files.exists(tempDir.resolve("messages/ru_ru.conf")))
    }

    @Test
    fun `fills missing keys from bundled defaults of the same locale`() {
        val service = newService()
        service.load(MessagesConfig())

        // 用户配置只保留一个空的 common 节点：缺失的 key 应由内置默认补齐，而不是判定为缺失
        Files.writeString(
            tempDir.resolve("messages/zh_cn.conf"),
            "common {}",
            StandardCharsets.UTF_8
        )

        service.load(MessagesConfig())

        assertEquals(
            "<red>该命令只能由玩家执行。</red>",
            service.resolveTemplate("zh_cn", MessageKeys.Common.ONLY_PLAYER)
        )
    }

    @Test
    fun `user overrides take precedence over bundled defaults`() {
        val service = newService()
        service.load(MessagesConfig())

        Files.writeString(
            tempDir.resolve("messages/zh_cn.conf"),
            "common { only-player = \"<red>自定义文案</red>\" }",
            StandardCharsets.UTF_8
        )

        service.load(MessagesConfig())

        assertEquals(
            "<red>自定义文案</red>",
            service.resolveTemplate("zh_cn", MessageKeys.Common.ONLY_PLAYER)
        )
    }

    @Test
    fun `reports missing for keys absent from every locale`() {
        val service = newService()
        service.load(MessagesConfig())

        assertTrue(
            service.resolveTemplate("zh_cn", "player.does-not-exist-at-all")
                .contains("Missing message")
        )
    }

    private fun newService(): MessageService {
        return MessageService(tempDir, ComponentLogger.logger("MessageServiceTest"))
    }
}

