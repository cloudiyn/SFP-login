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

package icu.h2l.login.qq.whitelist.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class QqBindCodeServiceTest {

    private var now = 1_000_000L
    private val service = QqBindCodeService(
        ttlMillis = 60_000,
        codeLength = 6,
        attemptLimit = 2,
        clock = { now }
    )

    @Test
    fun `issues digits-only code and verifies it once`() {
        val profileId = UUID.randomUUID()
        val code = service.issue(profileId, "Steve")

        assertEquals(6, code.code.length)
        assertTrue(code.code.all { it.isDigit() })
        assertEquals(profileId, code.profileId)

        val verified = service.verify(code.code)
        assertTrue(verified is QqBindCodeService.VerifyResult.Ok)

        service.consume(code.code)
        assertEquals(QqBindCodeService.VerifyResult.NotFound, service.verify(code.code))
    }

    @Test
    fun `code expires after ttl`() {
        val code = service.issue(UUID.randomUUID(), "Steve")
        now += 60_001L

        assertEquals(QqBindCodeService.VerifyResult.Expired, service.verify(code.code))
    }

    @Test
    fun `normalizes whitespace and case`() {
        val code = service.issue(UUID.randomUUID(), "Steve")
        val verified = service.verify("  ${code.code}  ")
        assertTrue(verified is QqBindCodeService.VerifyResult.Ok)
    }

    @Test
    fun `invalidate on too many failed attempts`() {
        val code = service.issue(UUID.randomUUID(), "Steve")
        service.recordFailure(code.code)
        service.recordFailure(code.code)

        assertEquals(QqBindCodeService.VerifyResult.NotFound, service.verify(code.code))
    }

    @Test
    fun `issuing again invalidates previous code of the same profile`() {
        val profileId = UUID.randomUUID()
        val first = service.issue(profileId, "Steve")
        val second = service.issue(profileId, "Steve")

        assertEquals(QqBindCodeService.VerifyResult.NotFound, service.verify(first.code))
        assertNotNull(service.pending(profileId))
        assertEquals(second.code, service.pending(profileId)?.code)
    }

    @Test
    fun `pending returns null after expiry`() {
        val profileId = UUID.randomUUID()
        service.issue(profileId, "Steve")
        now += 60_001L

        assertNull(service.pending(profileId))
    }
}
