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

package cn.starfallplain.sfplogin.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaitingAreaReleaseTransactionTest {

    @Test
    fun `放行成功时按顺序执行且不回滚`() {
        val events = mutableListOf<String>()
        val result = WaitingAreaReleaseTransaction.run(
            markLeavePending = { events += "mark" },
            clearLeavePending = { events += "clear" },
            release = { events += "release" },
        )
        assertTrue(result)
        assertEquals(listOf("mark", "release"), events)
    }

    @Test
    fun `放行抛错时回滚标记并报告失败`() {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val result = WaitingAreaReleaseTransaction.run(
            markLeavePending = { events += "mark" },
            clearLeavePending = { events += "clear" },
            release = { events += "release"; throw IllegalStateException("boom") },
            onFailure = { failures += it },
        )
        assertFalse(result)
        assertEquals(listOf("mark", "release", "clear"), events)
        assertEquals(1, failures.size)
        assertEquals("boom", failures.single().message)
    }

    @Test
    fun `写标记就抛错时不回滚`() {
        val events = mutableListOf<String>()
        val result = WaitingAreaReleaseTransaction.run(
            markLeavePending = { throw IllegalStateException("mark failed") },
            clearLeavePending = { events += "clear" },
            release = { events += "release" },
        )
        assertFalse(result)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `回滚本身抛错也返回失败且不向外抛出`() {
        val failures = mutableListOf<String?>()
        val result = WaitingAreaReleaseTransaction.run(
            markLeavePending = {},
            clearLeavePending = { throw IllegalStateException("rollback failed") },
            release = { throw IllegalStateException("release failed") },
            onFailure = { failures += it.message },
        )
        assertFalse(result)
        assertEquals(listOf<String?>("release failed", "rollback failed"), failures)
    }

    @Test
    fun `放行动作只执行一次`() {
        var releases = 0
        val result = WaitingAreaReleaseTransaction.run(
            markLeavePending = {},
            clearLeavePending = {},
            release = { releases++ },
        )
        assertTrue(result)
        assertEquals(1, releases)
    }
}
