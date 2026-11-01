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

import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.db.Profile
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleRequestEvent
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleResult
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredential
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class LoginManagerTest {
    @Test
    fun `dispatch returns no claims when no module applies`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val profileService = mockk<HyperZoneProfileService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>()
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneProfileServiceProvider.bind(profileService)
        mockkStatic("cn.starfallplain.sfplogin.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedProfile() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            CompletableFuture.completedFuture(firstArg())
        }

        val result = LoginManager(proxyServer, profileService, timeoutMillis = 200).dispatch(player, hyperPlayer).join()

        assertEquals(LoginManager.Status.NO_CLAIMS, result.status)
    }

    @Test
    fun `dispatch waits all claim failures`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val profileService = mockk<HyperZoneProfileService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>()
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneProfileServiceProvider.bind(profileService)
        mockkStatic("cn.starfallplain.sfplogin.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedProfile() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("module-a") { CompletableFuture.completedFuture(LoginHandleResult.failed("a failed")) }
                event.claim("module-b") { CompletableFuture.completedFuture(LoginHandleResult.failed("b failed")) }
            }
            CompletableFuture.completedFuture(event)
        }

        val resultFuture = LoginManager(proxyServer, profileService, timeoutMillis = 300).dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.ALL_FAILED, result.status)
        assertEquals(2, result.failures.size)
    }

    @Test
    fun `dispatch short-circuits on first success and cancels others`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val profileService = mockk<HyperZoneProfileService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>()
        val channel = EmbeddedChannel()
        val attached = AtomicBoolean(false)
        val slowFuture = CompletableFuture<LoginHandleResult>()
        val credential = mockk<HyperZoneCredential>(relaxed = true)
        val attachedProfile = mockk<Profile>(relaxed = true)
        every { proxyServer.eventManager } returns eventManager
        HyperZoneProfileServiceProvider.bind(profileService)
        mockkStatic("cn.starfallplain.sfplogin.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedProfile() } answers { attached.get() }
        every { profileService.getAttachedProfile(hyperPlayer) } returns null
        every { profileService.attachVerifiedCredentialProfile(hyperPlayer) } answers { attached.set(true); attachedProfile }
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("winner") {
                    CompletableFuture.completedFuture(LoginHandleResult.success(credential = credential))
                }
                event.claim("slow") { slowFuture }
            }
            CompletableFuture.completedFuture(event)
        }

        val resultFuture = LoginManager(proxyServer, profileService, timeoutMillis = 400).dispatch(player, hyperPlayer)
        channel.runUntilComplete(resultFuture)
        val result = resultFuture.join()

        assertEquals(LoginManager.Status.SUCCESS, result.status)
        assertEquals("winner", result.winnerModuleId)
        assertTrue(slowFuture.isCancelled)
        verify(exactly = 1) { profileService.attachVerifiedCredentialProfile(hyperPlayer) }
    }

    @Test
    fun `dispatch returns timeout for hanging claim`() {
        val eventManager = mockk<EventManager>()
        val proxyServer = mockk<ProxyServer>()
        val profileService = mockk<HyperZoneProfileService>()
        val player = mockk<Player>(relaxed = true)
        val hyperPlayer = mockk<VelocityHyperZonePlayer>()
        val channel = EmbeddedChannel()
        every { proxyServer.eventManager } returns eventManager
        HyperZoneProfileServiceProvider.bind(profileService)
        mockkStatic("cn.starfallplain.sfplogin.api.player.VelocityPlayerExtensionsKt")
        every { player.getChannel() } returns channel
        every { hyperPlayer.getProxyPlayerOrNull() } returns player
        every { hyperPlayer.hasAttachedProfile() } returns false
        every { eventManager.fire(any<Any>()) } answers {
            val event = firstArg<Any>()
            if (event is LoginHandleRequestEvent) {
                event.claim("hang") { CompletableFuture<LoginHandleResult>() }
            }
            CompletableFuture.completedFuture(event)
        }

        val result = LoginManager(proxyServer, profileService, timeoutMillis = 60).dispatch(player, hyperPlayer).join()

        assertEquals(LoginManager.Status.TIMEOUT, result.status)
    }

    private fun EmbeddedChannel.runUntilComplete(future: CompletableFuture<*>) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!future.isDone && System.nanoTime() < deadline) {
            runPendingTasks()
            Thread.sleep(1)
        }
    }
}

