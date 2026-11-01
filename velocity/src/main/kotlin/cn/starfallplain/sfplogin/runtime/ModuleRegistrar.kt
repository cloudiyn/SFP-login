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
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistry
import org.slf4j.Logger

/**
 * 子模块注册台账。
 *
 * 负责把"注册凭证渠道 → 调用模块 register"当成一个可回滚的事务：
 * 中途任何一步抛错，都会注销该模块已声明的凭证渠道并调用模块自身的
 * [HyperSubModule.unregister] 做补偿，避免留下半加载状态。
 *
 * 同一模块类可重复注册：旧实例会先被卸载（含凭证渠道），再挂载新实例。
 * 卸载顺序与注册顺序相反，保证依赖关系被正确拆除。
 */
class ModuleRegistrar(
    private val logger: Logger,
    private val credentialChannels: CredentialChannelRegistry,
) {

    private class RegisteredModule(
        val module: HyperSubModule,
        val api: HyperZoneApi,
    )

    private val lock = Any()
    private val modules = LinkedHashMap<String, RegisteredModule>()

    /**
     * 注册一个模块。
     *
     * @return 注册成功返回 `true`；失败时已回滚，返回 `false`
     */
    fun register(module: HyperSubModule, api: HyperZoneApi): Boolean {
        val moduleId = module.javaClass.name
        unloadIfPresent(moduleId)

        val registeredChannels = mutableListOf<String>()
        return try {
            module.credentialChannelIds.forEach { channelId ->
                val ability = credentialChannels.registerChannel(channelId)
                registeredChannels += channelId
                logger.info(
                    "凭证渠道已注册: {} (模块: {}) [canRegister={}]",
                    channelId,
                    module.javaClass.simpleName,
                    ability.canRegister
                )
            }
            module.register(api)
            synchronized(lock) { modules[moduleId] = RegisteredModule(module, api) }
            logger.info("模块加载成功: {}", moduleId)
            true
        } catch (e: Exception) {
            rollback(module, api, moduleId, registeredChannels, e)
            false
        }
    }

    /**
     * 卸载指定模块（按类名）。
     *
     * @return 该模块此前已注册并被移除时返回 `true`
     */
    fun unregister(moduleId: String): Boolean {
        val registered = synchronized(lock) { modules.remove(moduleId) } ?: return false
        release(registered)
        return true
    }

    /**
     * 卸载全部模块，顺序与注册顺序相反。
     */
    fun unregisterAll() {
        val snapshot = synchronized(lock) {
            val values = modules.values.toList()
            modules.clear()
            values
        }
        snapshot.asReversed().forEach(::release)
    }

    /** 当前已加载的模块类名（按注册顺序）。 */
    fun registeredModuleIds(): List<String> = synchronized(lock) { modules.keys.toList() }

    private fun unloadIfPresent(moduleId: String) {
        val previous = synchronized(lock) { modules.remove(moduleId) } ?: return
        logger.info("模块重复注册，先卸载旧实例: {}", moduleId)
        release(previous)
    }

    private fun release(registered: RegisteredModule) {
        val moduleId = registered.module.javaClass.name
        runCatching { registered.module.unregister(registered.api) }.onFailure { throwable ->
            logger.warn("模块 {} 卸载失败: {}", moduleId, throwable.message)
        }
        registered.module.credentialChannelIds.forEach { channelId ->
            runCatching { credentialChannels.unregisterChannel(channelId) }.onFailure { throwable ->
                logger.warn("注销凭证渠道 {} 失败: {}", channelId, throwable.message)
            }
        }
        logger.info("模块已卸载: {}", moduleId)
    }

    private fun rollback(
        module: HyperSubModule,
        api: HyperZoneApi,
        moduleId: String,
        registeredChannels: List<String>,
        error: Exception,
    ) {
        registeredChannels.forEach { channelId ->
            runCatching { credentialChannels.unregisterChannel(channelId) }.onFailure { throwable ->
                logger.warn("回滚凭证渠道 {} 失败: {}", channelId, throwable.message)
            }
        }
        runCatching { module.unregister(api) }.onFailure { throwable ->
            logger.warn("回滚模块 {} 失败: {}", moduleId, throwable.message)
        }
        logger.error(
            "模块 {} 加载失败，已回滚凭证渠道 {}: {}",
            moduleId,
            registeredChannels,
            error.message,
            error
        )
    }
}
