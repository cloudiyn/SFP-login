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

package cn.starfallplain.sfplogin.runtime

import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.config.CoreConfig
import cn.starfallplain.sfplogin.config.StartConfig
import com.velocitypowered.api.proxy.ProxyServer
import io.mockk.mockk
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class HyperZoneContextTest {

    @AfterEach
    fun cleanup() {
        HyperZoneRuntime.close()
    }

    @Test
    fun `close 逆序释放已登记资源`() {
        val context = newContext()
        val order = mutableListOf<String>()
        context.onClose("a") { order += "a" }
        context.onClose("b") { order += "b" }
        context.onClose("c") { order += "c" }

        context.close()

        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun `单独释放过的资源不会在 close 时重复执行`() {
        val context = newContext()
        var first = 0
        var second = 0
        val resource = context.onClose("first") { first++ }
        context.onClose("second") { second++ }

        resource.release()
        resource.release()
        context.close()

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun `释放动作抛错不影响其余资源`() {
        val context = newContext()
        val order = mutableListOf<String>()
        context.onClose("boom") { throw IllegalStateException("boom") }
        context.onClose("after") { order += "after" }

        context.close()

        assertEquals(listOf("after"), order)
        assertTrue(context.isClosing)
    }

    @Test
    fun `close 期间登记的资源立即释放`() {
        val context = newContext()
        var late = 0
        context.onClose("trigger") {
            context.onClose("late") { late++ }
        }

        context.close()

        assertEquals(1, late)
    }

    @Test
    fun `重复 close 幂等`() {
        val context = newContext()
        var released = 0
        context.onClose("once") { released++ }

        context.close()
        context.close()

        assertEquals(1, released)
        assertFalse(HyperZoneRuntime.current() === context && !context.isClosing)
    }

    @Test
    fun `配置未加载时 requireCoreConfig 抛错`() {
        val context = newContext()
        assertNull(context.coreConfig)
        assertNull(context.startConfig)
        assertThrows(IllegalStateException::class.java) { context.requireCoreConfig() }
        assertThrows(IllegalStateException::class.java) { context.requireStartConfig() }
    }

    @Test
    fun `start 会关闭上一个上下文`() {
        val previous = newContext()
        var released = 0
        previous.onClose("previous") { released++ }

        val current = HyperZoneRuntime.start(newMain())

        assertEquals(1, released)
        assertTrue(previous.isClosing)
        assertTrue(HyperZoneRuntime.current() === current)
    }

    @Test
    fun `runtime close 后 current 返回 null`() {
        newContext()
        HyperZoneRuntime.close()
        assertNull(HyperZoneRuntime.current())
        assertThrows(IllegalStateException::class.java) { HyperZoneRuntime.require() }
    }

    @Test
    fun `上下文持有配置快照`() {
        val context = newContext()
        val config = CoreConfig()
        val start = StartConfig()
        context.coreConfig = config
        context.startConfig = start
        assertSame(config, context.requireCoreConfig())
        assertSame(start, context.requireStartConfig())
    }

    private fun newContext(): HyperZoneContext = HyperZoneRuntime.start(newMain())

    private fun newMain(): SFPLoginMain = SFPLoginMain(
        server = mockk<ProxyServer>(relaxed = true),
        logger = mockk<ComponentLogger>(relaxed = true),
        dataDirectory = Paths.get("build", "tmp", "hyper-zone-context-test"),
        plugin = mockk(relaxed = true),
    )
}
