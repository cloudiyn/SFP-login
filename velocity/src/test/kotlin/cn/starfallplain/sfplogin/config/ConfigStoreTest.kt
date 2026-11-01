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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConfigStoreTest {

    @Test
    fun `首次发布后 revision 从 1 开始`() {
        val store = ConfigStore()
        assertNull(store.current())

        val published = store.publish(CoreConfig())

        assertEquals(1L, published.revision)
        assertEquals(1L, store.revision())
        assertSame(published, store.current())
    }

    @Test
    fun `重复发布推进 revision 且快照整体替换`() {
        val store = ConfigStore()
        val first = store.publish(CoreConfig())
        val second = store.publish(CoreConfig())

        assertEquals(first.revision + 1, second.revision)
        assertSame(second, store.current())
        // 旧快照仍然是完整的一份配置，不会被新发布改动。
        assertSame(first.core, first.core)
    }

    @Test
    fun `未发布时 require 抛出明确异常`() {
        val store = ConfigStore()

        val error = assertFailsWith<IllegalStateException> { store.require() }

        assertEquals("configuration snapshot is not available yet", error.message)
    }

    @Test
    fun `清空后回到未发布状态`() {
        val store = ConfigStore()
        store.publish(CoreConfig())
        store.clear()

        assertNull(store.current())
        assertNull(store.coreOrNull())
        assertEquals(0L, store.revision())
    }

    @Test
    fun `核心配置读取与快照一致`() {
        val store = ConfigStore()
        val core = CoreConfig()
        store.publish(core)

        assertSame(core, store.requireCore())
        assertSame(core, store.require().core)
    }
}
