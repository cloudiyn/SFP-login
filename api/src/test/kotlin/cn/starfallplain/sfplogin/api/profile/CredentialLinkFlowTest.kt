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

package cn.starfallplain.sfplogin.api.profile

import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CredentialLinkFlowTest {

    private class FakeCredential(override val channelId: String) : HyperZoneCredential {
        override val credentialId: String = channelId

        override fun getBoundProfileId(): UUID? = null
    }

    /**
     * 只实现存储与 attach 结果，[HyperZoneCredentialFlow.submitAndLink] 使用接口默认实现。
     */
    private class FakeFlow : HyperZoneCredentialFlow {
        val credentials = mutableListOf<HyperZoneCredential>()
        var attachResult: UUID? = UUID.randomUUID()
        var attachError: RuntimeException? = null

        override fun submitCredential(player: HyperZonePlayer, credential: HyperZoneCredential) {
            credentials += credential
        }

        override fun replaceCredential(
            player: HyperZonePlayer,
            oldChannelId: String,
            credential: HyperZoneCredential,
        ) {
            credentials.removeAll { it.channelId == oldChannelId }
            credentials += credential
        }

        override fun getSubmittedCredentials(player: HyperZonePlayer): List<HyperZoneCredential> = credentials.toList()

        override fun clearCredentials(player: HyperZonePlayer) {
            credentials.clear()
        }

        override fun overVerify(player: HyperZonePlayer): UUID? {
            attachError?.let { throw it }
            return attachResult
        }

        override fun resetVerify(player: HyperZonePlayer) {
            credentials.clear()
        }
    }

    private val player = mockk<HyperZonePlayer>(relaxed = true)

    @Test
    fun `提交并链接成功时保留凭证并返回档案 ID`() {
        val flow = FakeFlow()
        val credential = FakeCredential("offline")

        val result = flow.submitAndLink(player, credential)

        assertTrue(result.isLinked)
        assertEquals(CredentialLinkStage.LINKED, result.stage)
        assertEquals(flow.attachResult, result.attachedProfileId)
        assertNull(result.reason)
        assertEquals(listOf(credential), flow.credentials)
    }

    @Test
    fun `attach 失败时回滚凭证并返回 REJECTED`() {
        val flow = FakeFlow().apply { attachResult = null }

        val result = flow.submitAndLink(player, FakeCredential("offline"))

        assertEquals(CredentialLinkStage.REJECTED, result.stage)
        assertFalse(result.isLinked)
        assertNull(result.attachedProfileId)
        assertTrue(flow.credentials.isEmpty())
    }

    @Test
    fun `attach 抛错时回滚到提交前的凭证`() {
        val old = FakeCredential("yggdrasil")
        val flow = FakeFlow().apply {
            credentials += old
            attachError = IllegalStateException("attach boom")
        }

        val result = flow.submitAndLink(player, FakeCredential("offline"))

        assertEquals(CredentialLinkStage.REJECTED, result.stage)
        assertEquals("attach boom", result.reason)
        assertEquals(listOf(old), flow.credentials)
    }

    @Test
    fun `restoreCredentials 先清空再按顺序重新登记`() {
        val flow = FakeFlow().apply { credentials += FakeCredential("stale") }
        val first = FakeCredential("a")
        val second = FakeCredential("b")

        flow.restoreCredentials(player, listOf(first, second))

        assertEquals(listOf(first, second), flow.credentials)
    }
}
