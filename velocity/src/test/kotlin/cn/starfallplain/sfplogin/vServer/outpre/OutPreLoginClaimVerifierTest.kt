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

package cn.starfallplain.sfplogin.vServer.outpre

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OutPreLoginClaimVerifierTest {

    private val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `身份完全一致时不报告差异`() {
        val mismatch = OutPreLoginClaimVerifier.verify(
            OutPreClaimedIdentity(uuid, "Steve"),
            OutPreClaimedIdentity(uuid, "Steve"),
        )
        assertNull(mismatch)
    }

    @Test
    fun `用户名比较忽略大小写与首尾空白`() {
        val mismatch = OutPreLoginClaimVerifier.verify(
            OutPreClaimedIdentity(uuid, "Steve"),
            OutPreClaimedIdentity(uuid, "  sTeVe  "),
        )
        assertNull(mismatch)
    }

    @Test
    fun `用户名不同视为不一致`() {
        val mismatch = OutPreLoginClaimVerifier.verify(
            OutPreClaimedIdentity(uuid, "Steve"),
            OutPreClaimedIdentity(uuid, "Alex"),
        )
        assertNotNull(mismatch)
        assertEquals("Steve", mismatch.expected.name)
        assertEquals("Alex", mismatch.returned.name)
    }

    @Test
    fun `UUID 不同视为不一致`() {
        val other = UUID.fromString("99999999-8888-7777-6666-555555555555")
        val mismatch = OutPreLoginClaimVerifier.verify(
            OutPreClaimedIdentity(uuid, "Steve"),
            OutPreClaimedIdentity(other, "Steve"),
        )
        assertNotNull(mismatch)
    }

    @Test
    fun `任一侧缺少 UUID 时只比较用户名`() {
        assertNull(
            OutPreLoginClaimVerifier.verify(
                OutPreClaimedIdentity(null, "Steve"),
                OutPreClaimedIdentity(uuid, "Steve"),
            ),
        )
        assertNull(
            OutPreLoginClaimVerifier.verify(
                OutPreClaimedIdentity(uuid, "Steve"),
                OutPreClaimedIdentity(null, "Steve"),
            ),
        )
    }

    @Test
    fun `差异描述同时给出声明与回传身份`() {
        val mismatch = OutPreLoginClaimVerifier.verify(
            OutPreClaimedIdentity(uuid, "Steve"),
            OutPreClaimedIdentity(null, "Alex"),
        )
        assertNotNull(mismatch)
        val description = mismatch.describe()
        assertEquals(true, description.contains("expected(uuid=$uuid, name=Steve)"))
        assertEquals(true, description.contains("returned(uuid=null, name=Alex)"))
    }
}
