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

package cn.starfallplain.sfplogin.reflect

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerPing
import com.velocitypowered.proxy.connection.MinecraftConnection
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class VelocityInternalAccessPingTest {

    @Test
    fun `createPingSessionHandler constructs against real Velocity class`() {
        val pingFuture = CompletableFuture<ServerPing>()
        val registeredServer = mockk<RegisteredServer>(relaxed = true)
        val conn = mockk<MinecraftConnection>(relaxed = true)

        val handler = VelocityInternalAccess.createPingSessionHandler(
            pingFuture = pingFuture,
            registeredServer = registeredServer,
            conn = conn,
            protocolVersion = ProtocolVersion.MAXIMUM_VERSION,
            virtualHost = null,
        )

        assertNotNull(handler)
    }

    @Test
    fun `createPingSessionHandler accepts string virtual host`() {
        val handler = VelocityInternalAccess.createPingSessionHandler(
            pingFuture = CompletableFuture<ServerPing>(),
            registeredServer = mockk<RegisteredServer>(relaxed = true),
            conn = mockk<MinecraftConnection>(relaxed = true),
            protocolVersion = ProtocolVersion.MAXIMUM_VERSION,
            virtualHost = "auth.example.com",
        )

        assertNotNull(handler)
    }
}
