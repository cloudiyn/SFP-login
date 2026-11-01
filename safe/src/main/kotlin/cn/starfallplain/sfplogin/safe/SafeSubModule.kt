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

package cn.starfallplain.sfplogin.safe

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.message.HyperZoneModuleMessageResources
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.safe.config.SafeConfigLoader
import cn.starfallplain.sfplogin.safe.listener.AuthFailureGuardListener
import cn.starfallplain.sfplogin.safe.listener.PreLoginGuardListener
import cn.starfallplain.sfplogin.safe.service.ConnectionRateLimiter
import cn.starfallplain.sfplogin.safe.service.IpCooldownManager
import cn.starfallplain.sfplogin.safe.service.IpConcurrentOnlineGuard
import cn.starfallplain.sfplogin.safe.service.StrictModeController
import cn.starfallplain.sfplogin.safe.service.UsernameValidator

class SafeSubModule : HyperSubModule {
    override fun register(api: HyperZoneApi) {
        HyperZoneModuleMessageResources.copyBundledLocales(api.dataDirectory, "safe", javaClass.classLoader)
        val config = SafeConfigLoader.load(api.dataDirectory)
        val logger = java.util.logging.Logger.getLogger("hzl-safe")
        val authFailureCooldownManager = IpCooldownManager(
            enabled = config.authFailure.enabled,
            windowSeconds = config.authFailure.windowSeconds,
            triggerAttempts = config.authFailure.triggerAttempts,
            cooldownSeconds = config.authFailure.cooldownSeconds
        )
        val listener = PreLoginGuardListener(
            config = config,
            globalRateLimiter = ConnectionRateLimiter(config.globalRateLimit.windowSeconds, config.globalRateLimit.maxAttempts),
            ipRateLimiter = ConnectionRateLimiter(config.ipRateLimit.windowSeconds, config.ipRateLimit.maxAttempts),
            strictGlobalRateLimiter = ConnectionRateLimiter(
                config.strictMode.globalRateLimit.windowSeconds,
                config.strictMode.globalRateLimit.maxAttempts
            ),
            strictIpRateLimiter = ConnectionRateLimiter(
                config.strictMode.ipRateLimit.windowSeconds,
                config.strictMode.ipRateLimit.maxAttempts
            ),
            ipCooldownManager = IpCooldownManager(
                enabled = config.ipCooldown.enabled,
                windowSeconds = config.ipCooldown.windowSeconds,
                triggerAttempts = config.ipCooldown.triggerAttempts,
                cooldownSeconds = config.ipCooldown.cooldownSeconds
            ),
            authFailureCooldownManager = authFailureCooldownManager,
            strictModeController = StrictModeController(
                enabled = config.strictMode.enabled,
                windowSeconds = config.strictMode.windowSeconds,
                triggerAttempts = config.strictMode.triggerAttempts,
                recoverAfterSeconds = config.strictMode.recoverAfterSeconds,
                logger = logger
            ),
            usernameValidator = UsernameValidator(config.username),
            ipConcurrentGuard = IpConcurrentOnlineGuard(
                enabled = config.ipLimit.enable,
                maxConcurrent = config.ipLimit.max
            )
        )
        api.proxy.eventManager.register(api, listener)
        api.proxy.eventManager.register(api, AuthFailureGuardListener(authFailureCooldownManager, logger))
        info { "SafeSubModule 已加载，入口层安全防护已启用" }
    }
}

