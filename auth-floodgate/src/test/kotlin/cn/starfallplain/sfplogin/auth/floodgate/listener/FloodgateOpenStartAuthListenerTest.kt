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

package cn.starfallplain.sfplogin.auth.floodgate.listener

import com.velocitypowered.api.util.GameProfile
import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.event.connection.OpenStartAuthEvent
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.auth.floodgate.db.FloodgateAuthRepository
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateAuthService
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateSessionHolder
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.Channel
import org.geysermc.floodgate.api.FloodgateApi
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class FloodgateOpenStartAuthListenerTest {
    @Test
    fun `listener establishes floodgate session before outpre auth start`() {
        val api = mockk<HyperZoneApi>()
        val playerAccessor = mockk<HyperZonePlayerAccessor>()
        val hyperPlayer = mockk<HyperZonePlayer>(relaxed = true)
        val apiHolder = FakeFloodgateApiHolder()
        val sessionHolder = FloodgateSessionHolder()
        val repository = mockk<FloodgateAuthRepository>(relaxed = true)
        val profileService = mockk<HyperZoneProfileService>(relaxed = true)
        val channel = mockk<Channel>()
        val event = OpenStartAuthEvent(
            userName = "ktese",
            userUUID = UUID.randomUUID(),
            serverId = "",
            playerIp = "192.168.9.203",
            channel = channel,
            isOnline = false,
        ).apply {
            gameProfile = GameProfile(UUID.randomUUID(), "ktese", emptyList())
        }
        apiHolder.resolvedIdentity = FloodgateApiHolder.ResolvedFloodgateIdentity(
            userName = ".ktese",
            userUUID = UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"),
            xuid = 1234567890L,
        )
        apiHolder.trustedUuids += UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50")
        every { api.hyperZonePlayers } returns playerAccessor
        every { api.serverAdapter } returns null
        every {
            playerAccessor.create(
                channel,
                "ktese",
                UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"),
                any(),
            )
        } returns hyperPlayer

        val authService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = apiHolder,
            sessionHolder = sessionHolder,
            repository = repository,
            profileService = profileService,
        )

        FloodgateOpenStartAuthListener(authService, apiHolder).onOpenStartAuth(event)

        assertTrue(event.allow)
        val remembered = sessionHolder.get(channel)
        assertNotNull(remembered)
        assertEquals("ktese", remembered!!.userName)
        assertEquals(UUID.fromString("00000000-0000-0000-0009-01fb2b9f0a50"), remembered.userUUID)
        assertEquals(1234567890L, remembered.xuid)
    }

    private class FakeFloodgateApiHolder : FloodgateApiHolder(mockk<FloodgateApi>(relaxed = true)) {
        var resolvedIdentity: ResolvedFloodgateIdentity? = null
        val trustedUuids = mutableSetOf<UUID>()

        override fun resolveLoginIdentity(userName: String, playerIp: String): ResolvedFloodgateIdentity? {
            return resolvedIdentity
        }

        override fun isFloodgatePlayer(uuid: UUID): Boolean {
            return uuid in trustedUuids
        }

        override fun getPlayerPrefix(): String {
            return "."
        }
    }
}
