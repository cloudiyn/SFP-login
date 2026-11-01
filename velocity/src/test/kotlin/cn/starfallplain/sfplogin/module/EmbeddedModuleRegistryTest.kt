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

package cn.starfallplain.sfplogin.module

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedModuleRegistryTest {

    @Test
    fun `描述符 id 与配置键唯一`() {
        val ids = EmbeddedModuleRegistry.descriptors.map { it.id }
        val configKeys = EmbeddedModuleRegistry.descriptors.map { it.configKey }

        assertEquals(ids.size, ids.toSet().size, "模块 id 必须唯一，独立 jar 靠它去重")
        assertEquals(configKeys.size, configKeys.toSet().size, "配置键必须唯一，否则开关会互相覆盖")
    }

    @Test
    fun `描述符表覆盖七个内置模块且顺序稳定`() {
        assertEquals(
            listOf(
                "hzl-auth-floodgate",
                "hzl-auth-offline",
                "hzl-auth-yggd",
                "hzl-safe",
                "hzl-profile-skin",
                "hzl-qq-whitelist",
                "hzl-data-merge",
            ),
            EmbeddedModuleRegistry.descriptors.map { it.id },
        )
    }

    @Test
    fun `所有内置模块都声明了实现类`() {
        EmbeddedModuleRegistry.descriptors.forEach { descriptor ->
            assertTrue(descriptor.supportsEmbedded, "${descriptor.id} 应可内置装配")
            assertNotNull(descriptor.implementationClassName, "${descriptor.id} 缺少实现类名")
            assertTrue(descriptor.displayName.isNotBlank(), "${descriptor.id} 缺少展示名")
        }
    }

    @Test
    fun `按 id 查询与按描述符实例一致`() {
        val descriptor = EmbeddedModuleRegistry.authOffline

        assertNotNull(EmbeddedModuleRegistry.byId("hzl-auth-offline"))
        assertEquals(descriptor, EmbeddedModuleRegistry.byId("hzl-auth-offline"))
        assertNull(EmbeddedModuleRegistry.byId("hzl-not-exists"))
    }

    @Test
    fun `floodgate 模块声明了必需插件`() {
        assertEquals(listOf("floodgate"), EmbeddedModuleRegistry.authFloodgate.requiredPluginIds)
        assertTrue(EmbeddedModuleRegistry.authOffline.requiredPluginIds.isEmpty())
    }
}
