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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

class OutPreAuthEndpointProbeTest {

    @Test
    fun `reports reachable when something listens on the port`() {
        ServerSocket(0).use { server ->
            val address = InetSocketAddress("127.0.0.1", server.localPort)
            assertTrue(OutPreAuthEndpointProbe.isReachable(address, 1_000))
        }
    }

    @Test
    fun `reports unreachable when nothing listens on the port`() {
        val port = ServerSocket(0).use { it.localPort }
        val address = InetSocketAddress("127.0.0.1", port)
        assertFalse(OutPreAuthEndpointProbe.isReachable(address, 500))
    }

    @Test
    fun `rejects invalid port without attempting a connection`() {
        assertFalse(OutPreAuthEndpointProbe.isReachable(InetSocketAddress("127.0.0.1", 0), 100))
    }
}
