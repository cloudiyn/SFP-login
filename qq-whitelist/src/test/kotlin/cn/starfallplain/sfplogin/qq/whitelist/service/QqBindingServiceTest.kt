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

package cn.starfallplain.sfplogin.qq.whitelist.service

import cn.starfallplain.sfplogin.api.db.Profile
import cn.starfallplain.sfplogin.qq.whitelist.config.QqWhitelistConfig
import cn.starfallplain.sfplogin.qq.whitelist.message.QqGroupMessages
import cn.starfallplain.sfplogin.qq.whitelist.store.QqBindCodeService
import cn.starfallplain.sfplogin.qq.whitelist.store.QqBinding
import cn.starfallplain.sfplogin.qq.whitelist.store.QqBindingStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class QqBindingServiceTest {

    @field:TempDir
    lateinit var dataDirectory: Path

    private val store = InMemoryStore()
    private val sent = mutableListOf<String>()
    private var oneBotConnected = true

    private val messages by lazy { QqGroupMessages(dataDirectory, "zh_cn") }

    private val config = QqWhitelistConfig().apply {
        groups = listOf(100L)
        enforce = true
        onebot.failOpenWhenDisconnected = true
    }

    private val codes = QqBindCodeService(
        ttlMillis = 60_000,
        codeLength = 6,
        attemptLimit = 3
    )

    private val service by lazy {
        QqBindingService(
            config = config,
            store = store,
            codes = codes,
            messages = messages,
            onebotAvailable = { oneBotConnected },
            sender = { _, text -> sent += text },
            log = { }
        )
    }

    private fun profile(name: String = "Steve") = Profile(UUID.randomUUID(), name, UUID.randomUUID())

    @Test
    fun `binds profile with valid code and reports success`() {
        val profile = profile()
        val code = service.issueCode(profile.id, profile.name)

        service.handleGroupMessage(100L, 42L, "绑定 $code")

        val binding = store.findByProfile(profile.id)
        assertNotNull(binding)
        assertEquals(42L, binding!!.qqId)
        assertTrue(sent.any { it.contains("group.bind-success") })
    }

    @Test
    fun `ignores messages from groups not configured`() {
        val profile = profile()
        val code = service.issueCode(profile.id, profile.name)

        service.handleGroupMessage(999L, 42L, "绑定 $code")

        assertNull(store.findByProfile(profile.id))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `rejects when qq reached binding limit`() {
        config.binding.maxPerQq = 1
        val first = profile("A")
        val second = profile("B")
        service.handleGroupMessage(100L, 42L, "绑定 ${service.issueCode(first.id, first.name)}")
        service.handleGroupMessage(100L, 42L, "绑定 ${service.issueCode(second.id, second.name)}")

        assertNull(store.findByProfile(second.id))
        assertTrue(sent.any { it.contains("group.limit-reached") })
    }

    @Test
    fun `rejects profile already bound to another qq`() {
        val profile = profile()
        service.handleGroupMessage(100L, 42L, "绑定 ${service.issueCode(profile.id, profile.name)}")
        service.handleGroupMessage(100L, 43L, "绑定 ${service.issueCode(profile.id, profile.name)}")

        assertEquals(42L, store.findByProfile(profile.id)?.qqId)
        assertTrue(sent.any { it.contains("group.profile-conflict") || it.contains("group.already-bound") })
    }

    @Test
    fun `group unbind only removes binding of the same qq`() {
        val profile = profile("Steve")
        service.handleGroupMessage(100L, 42L, "绑定 ${service.issueCode(profile.id, profile.name)}")

        service.handleGroupMessage(100L, 43L, "解绑 Steve")
        assertNotNull(store.findByProfile(profile.id))

        service.handleGroupMessage(100L, 42L, "解绑 Steve")
        assertNull(store.findByProfile(profile.id))
        assertTrue(sent.any { it.contains("group.unbind-success") })
    }

    @Test
    fun `gate denies unbound player and reuses the same pending code`() {
        val profile = profile()

        val first = service.evaluate(profile)
        val second = service.evaluate(profile)

        assertTrue(first is QqGateOutcome.Denied)
        assertTrue(second is QqGateOutcome.Denied)
        assertEquals((first as QqGateOutcome.Denied).code, (second as QqGateOutcome.Denied).code)
    }

    @Test
    fun `gate allows bound player and reports notice only once`() {
        val profile = profile()
        service.handleGroupMessage(100L, 42L, "绑定 ${service.issueCode(profile.id, profile.name)}")
        sent.clear()

        val first = service.evaluate(profile)
        val second = service.evaluate(profile)

        assertTrue(first is QqGateOutcome.Allowed)
        assertEquals(42L, (first as QqGateOutcome.Allowed).newlyBoundQq)
        assertTrue(second is QqGateOutcome.Allowed)
        assertNull((second as QqGateOutcome.Allowed).newlyBoundQq)
    }

    @Test
    fun `gate allows when enforce disabled`() {
        config.enforce = false
        val outcome = service.evaluate(profile())
        assertTrue(outcome is QqGateOutcome.Allowed)
    }

    @Test
    fun `gate fails open when onebot disconnected and configured`() {
        oneBotConnected = false
        val outcome = service.evaluate(profile())
        assertTrue(outcome is QqGateOutcome.Allowed)
    }

    @Test
    fun `gate fails closed when onebot disconnected and fail-open disabled`() {
        oneBotConnected = false
        config.onebot.failOpenWhenDisconnected = false

        val outcome = service.evaluate(profile())
        assertTrue(outcome is QqGateOutcome.Denied)
    }

    private class InMemoryStore : QqBindingStore {
        private val bindings = mutableListOf<QqBinding>()

        override fun findByQq(qqId: Long) = bindings.filter { it.qqId == qqId }

        override fun findByProfile(profileId: UUID) = bindings.firstOrNull { it.profileId == profileId }

        override fun findByPlayerName(playerName: String) =
            bindings.firstOrNull { it.playerName.equals(playerName, ignoreCase = true) }

        override fun countByQq(qqId: Long) = bindings.count { it.qqId == qqId }

        override fun insert(binding: QqBinding): Boolean {
            if (bindings.any { it.profileId == binding.profileId }) {
                return false
            }
            return bindings.add(binding)
        }

        override fun deleteByProfile(profileId: UUID) =
            bindings.removeIf { it.profileId == profileId }

        override fun markNotified(profileId: UUID) {
            val index = bindings.indexOfFirst { it.profileId == profileId }
            if (index >= 0) {
                bindings[index] = bindings[index].copy(notifyPending = false)
            }
        }
    }
}
