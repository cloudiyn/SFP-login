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

package cn.starfallplain.sfplogin.player

import com.velocitypowered.api.util.GameProfile
import cn.starfallplain.sfplogin.api.event.profile.ProfileSkinApplyEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.log.error
import cn.starfallplain.sfplogin.api.log.warn
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.sfpLoginMain

object ProfileSkinApplySupport {

    /**
     * 同步解析最终档案（含皮肤）。
     *
     * 内部会等待事件总线与皮肤提供者返回，**禁止在 Netty 事件循环线程上调用**：那会阻塞该连接
     * 以及同一循环上的所有连接。事件回调或异步链里请改用 [applyAsync] 并组合后续步骤
     * （见 [cn.starfallplain.sfplogin.runtime.ConnectionHandoff] 与 `LoginProfilePacketReplacer`）。
     *
     * 若检测到在事件循环线程上被调用，会记录错误并退化为不含皮肤的档案，避免把连接卡死。
     */
    fun apply(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile? = null): GameProfile? {
        if (isOnEventLoopThread()) {
            error {
                "[ProfileSkinFlow] apply() called on event loop thread, falling back without textures; use applyAsync instead: clientOriginal=${hyperZonePlayer.clientOriginalName}"
            }
            return baseProfile ?: runCatching { hyperZonePlayer.getAttachedGameProfile() }.getOrNull()
        }
        return applyAsync(hyperZonePlayer, baseProfile).join()
    }

    private fun isOnEventLoopThread(): Boolean {
        return Thread.currentThread() is io.netty.util.concurrent.FastThreadLocalThread
    }

    fun applyAsync(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile? = null): java.util.concurrent.CompletableFuture<GameProfile?> {
        val resolvedBaseProfile = resolveBaseProfile(hyperZonePlayer, baseProfile)
            ?: return java.util.concurrent.CompletableFuture.completedFuture(null)
        val event = ProfileSkinApplyEvent(hyperZonePlayer, resolvedBaseProfile)

        return sfpLoginMain().proxy.eventManager.fire(event)
            .handle { _, throwable ->
                if (throwable != null) {
                    error(throwable) { "Profile skin apply event failed: ${throwable.message}" }
                    return@handle resolvedBaseProfile
                }

                val textures = event.textures ?: return@handle resolvedBaseProfile
                val property = textures.toPropertyOrNull() ?: run {
                    warn {
                        "[ProfileSkinFlow] apply skipped incomplete textures: clientOriginal=${hyperZonePlayer.clientOriginalName}, profile=${resolvedBaseProfile.id}, valueLength=${textures.value.length}, signed=${textures.isSigned}"
                    }
                    return@handle resolvedBaseProfile
                }
                mergeTextures(resolvedBaseProfile, property)
            }
    }

    private fun resolveBaseProfile(hyperZonePlayer: HyperZonePlayer, baseProfile: GameProfile?): GameProfile? {
        if (baseProfile != null) {
            return baseProfile
        }

        return runCatching {
            hyperZonePlayer.getAttachedGameProfile()
        }.getOrElse { throwable ->
            debug(HyperZoneDebugType.PROFILE_SKIN) {
                "[ProfileSkinFlow] apply aborted: clientOriginal=${hyperZonePlayer.clientOriginalName}, reason=${throwable.message ?: throwable.javaClass.simpleName}, attachedProfile=${hyperZonePlayer.hasAttachedProfile()}"
            }
            null
        }
    }

    internal fun mergeTextures(baseProfile: GameProfile, textureProperty: GameProfile.Property): GameProfile {
        val mergedProperties = baseProfile.properties
            .filterNot { it.name.equals("textures", ignoreCase = true) }
            .toMutableList()
            .apply { add(textureProperty) }

        return GameProfile(
            baseProfile.id,
            baseProfile.name,
            mergedProperties
        )
    }
}

