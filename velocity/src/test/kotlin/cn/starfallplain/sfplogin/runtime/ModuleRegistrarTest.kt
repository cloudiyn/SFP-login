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

package cn.starfallplain.sfplogin.runtime

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.profile.CredentialChannelAbility
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Logger

class ModuleRegistrarTest {

    private val logger = mockk<Logger>(relaxed = true)
    private val api = mockk<HyperZoneApi>(relaxed = true)
    private val registry = mockk<CredentialChannelRegistry>(relaxed = true)

    private fun newRegistrar(): ModuleRegistrar {
        every { registry.registerChannel(any()) } answers {
            CredentialChannelAbility(firstArg(), canRegister = true)
        }
        every { registry.unregisterChannel(any()) } returns true
        return ModuleRegistrar(logger, registry)
    }

    private open class FakeModule(
        override val credentialChannelIds: Set<String> = emptySet(),
        private val failOnRegister: Boolean = false,
        private val onRegister: (HyperZoneApi) -> Unit = {},
        private val onUnregister: (HyperZoneApi) -> Unit = {},
    ) : HyperSubModule {
        var registered = false
        var unregistered = false

        override fun register(api: HyperZoneApi) {
            if (failOnRegister) {
                throw IllegalStateException("boom")
            }
            registered = true
            onRegister(api)
        }

        override fun unregister(api: HyperZoneApi) {
            unregistered = true
            onUnregister(api)
        }
    }

    private class SecondFakeModule(
        override val credentialChannelIds: Set<String> = emptySet(),
        onUnregister: (HyperZoneApi) -> Unit = {},
    ) : FakeModule(credentialChannelIds, onUnregister = onUnregister)

    @Test
    fun `注册成功后记录模块与凭证渠道`() {
        val registrar = newRegistrar()
        val module = FakeModule(credentialChannelIds = setOf("offline", "yggdrasil"))

        assertTrue(registrar.register(module, api))

        assertTrue(module.registered)
        assertEquals(listOf(FakeModule::class.java.name), registrar.registeredModuleIds())
        verify(exactly = 1) { registry.registerChannel("offline") }
        verify(exactly = 1) { registry.registerChannel("yggdrasil") }
    }

    @Test
    fun `注册失败时回滚凭证渠道与模块`() {
        val registrar = newRegistrar()
        val module = FakeModule(credentialChannelIds = setOf("offline"), failOnRegister = true)

        assertFalse(registrar.register(module, api))

        assertTrue(registrar.registeredModuleIds().isEmpty())
        assertTrue(module.unregistered)
        verify(exactly = 1) { registry.unregisterChannel("offline") }
    }

    @Test
    fun `重复注册先卸载旧实例`() {
        val registrar = newRegistrar()
        val first = FakeModule(credentialChannelIds = setOf("offline"))
        val second = FakeModule(credentialChannelIds = setOf("offline"))

        assertTrue(registrar.register(first, api))
        assertTrue(registrar.register(second, api))

        assertTrue(first.unregistered)
        assertTrue(second.registered)
        assertEquals(listOf(FakeModule::class.java.name), registrar.registeredModuleIds())
        verify(exactly = 1) { registry.unregisterChannel("offline") }
    }

    @Test
    fun `卸载单个模块幂等`() {
        val registrar = newRegistrar()
        val module = FakeModule(credentialChannelIds = setOf("offline"))
        registrar.register(module, api)

        assertTrue(registrar.unregister(FakeModule::class.java.name))
        assertFalse(registrar.unregister(FakeModule::class.java.name))
        assertTrue(module.unregistered)
        verify(exactly = 1) { registry.unregisterChannel("offline") }
    }

    @Test
    fun `全部卸载按注册逆序并清空台账`() {
        val registrar = newRegistrar()
        val order = mutableListOf<String>()
        val first = FakeModule(credentialChannelIds = setOf("offline"), onUnregister = { order += "first" })
        val second = SecondFakeModule(credentialChannelIds = setOf("yggdrasil"), onUnregister = { order += "second" })

        registrar.register(first, api)
        registrar.register(second, api)
        registrar.unregisterAll()

        assertEquals(listOf("second", "first"), order)
        assertTrue(registrar.registeredModuleIds().isEmpty())
        verify(exactly = 1) { registry.unregisterChannel("offline") }
        verify(exactly = 1) { registry.unregisterChannel("yggdrasil") }
    }

    @Test
    fun `卸载抛错不影响其余模块`() {
        val registrar = newRegistrar()
        val broken = object : HyperSubModule {
            override fun register(api: HyperZoneApi) = Unit
            override fun unregister(api: HyperZoneApi) {
                throw IllegalStateException("unregister boom")
            }
        }
        val healthy = FakeModule()
        registrar.register(broken, api)
        registrar.register(healthy, api)

        registrar.unregisterAll()

        assertTrue(healthy.unregistered)
        assertTrue(registrar.registeredModuleIds().isEmpty())
    }
}
