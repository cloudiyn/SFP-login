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

package cn.starfallplain.sfplogin.auth.online.service

import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistryProvider
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.auth.online.config.EntryConfig
import cn.starfallplain.sfplogin.auth.online.credential.YggdrasilHyperZoneCredential
import cn.starfallplain.sfplogin.auth.online.db.EntryDatabaseHelper
import cn.starfallplain.sfplogin.auth.online.manager.EntryConfigManager
import cn.starfallplain.sfplogin.auth.online.message.YggdrasilMessages
import cn.starfallplain.sfplogin.auth.online.record.YggdrasilCredentialPreparation
import cn.starfallplain.sfplogin.auth.online.record.YggdrasilAuthResult
import java.util.*

class YggdrasilCredentialService(
    private val entryConfigManager: EntryConfigManager,
    private val profileService: HyperZoneProfileService,
    private val entryDatabaseHelper: EntryDatabaseHelper
) {
    fun prepareCredentialForSuccessfulAuth(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ): YggdrasilCredentialPreparation {
        if (profileService.getAttachedProfile(handler) != null) {
            return YggdrasilCredentialPreparation(credentialToSubmit = null)
        }

        val existingBoundProfileId = findBoundProfileIdByAuthenticatedEntry(result)
        if (existingBoundProfileId != null) {
            return YggdrasilCredentialPreparation(
                credentialToSubmit = yggdrasilCredential(
                    entryId = result.entryId,
                    authenticatedName = result.profile.name,
                    authenticatedUuid = result.profile.id,
                    suggestedProfileCreateUuid = resolveProfileResolveUuid(result),
                    knownProfileId = existingBoundProfileId
                )
            )
        }

        val profileResolveUuid = resolveProfileResolveUuid(result)
        val probeCredential = yggdrasilCredential(
            entryId = result.entryId,
            authenticatedName = result.profile.name,
            authenticatedUuid = result.profile.id,
            suggestedProfileCreateUuid = profileResolveUuid
        )

        val channelAbility = CredentialChannelRegistryProvider.getOrNull()?.getChannelAbility("yggdrasil")
        if (channelAbility?.canRegister == false) {
            return YggdrasilCredentialPreparation(
                credentialToSubmit = probeCredential,
                failureReason = YggdrasilMessages.registrationDisabledReason(handler)
            )
        }

        if (profileService.canCreate(probeCredential)) {
            val createdProfile = try {
                profileService.create(probeCredential)
            } catch (throwable: IllegalStateException) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = null,
                    failureReason = throwable.message ?: "创建 Profile 失败"
                )
            }

            val bound = entryDatabaseHelper.createEntry(
                entryId = result.entryId,
                name = resolveEntryName(result),
                uuid = result.profile.id,
                pid = createdProfile.id
            )
            if (bound) {
                return YggdrasilCredentialPreparation(
                    credentialToSubmit = yggdrasilCredential(
                        entryId = result.entryId,
                        authenticatedName = result.profile.name,
                        authenticatedUuid = result.profile.id,
                        suggestedProfileCreateUuid = profileResolveUuid,
                        knownProfileId = createdProfile.id
                    )
                )
            }
        }

        return YggdrasilCredentialPreparation(
            credentialToSubmit = yggdrasilCredential(
                entryId = result.entryId,
                authenticatedName = result.profile.name,
                authenticatedUuid = result.profile.id,
                suggestedProfileCreateUuid = profileResolveUuid
            )
        )
    }

    fun findBoundProfileIdByAuthenticatedEntry(success: YggdrasilAuthResult.Success): UUID? {
        val profileId = entryDatabaseHelper.findEntryByUuid(
            entryId = success.entryId,
            uuid = success.profile.id
        ) ?: return null

        entryDatabaseHelper.updateEntryName(
            entryId = success.entryId,
            uuid = success.profile.id,
            newName = resolveEntryName(success)
        )

        // 同步外部认证改名到正式 Profile（LS / Mojang 侧改名后，Profile 表也需更新）
        if (!profileService.syncProfileName(profileId, success.profile.name)) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "[YggdrasilFlow] Profile $profileId 名称同步到 '${success.profile.name}' 失败（可能名称冲突），保留旧名"
            }
        }

        return profileId
    }

    /**
     * 解析该渠道绑定行的 name 列取值。
     *
     * 玩家验证（Yggdrasil）时应以 entry 配置里填写的名字（[EntryConfig.name]）为准，
     * 而不是远端 Yggdrasil 返回的 profile.name：
     * 远端名可能被玩家随时改动，用它作绑定行名会导致同一账号出现多行、密码门状态漂移。
     * 仅当 entry 配置缺失 name 时才回退到远端名。
     */
    private fun resolveEntryName(success: YggdrasilAuthResult.Success): String {
        val configuredName = entryConfigManager.getConfigById(success.entryId)?.name
        return configuredName?.trim()?.takeUnless { it.isEmpty() } ?: success.profile.name
    }

    private fun resolveProfileResolveUuid(result: YggdrasilAuthResult.Success): UUID? {
        val entryConfig = entryConfigManager.getConfigById(result.entryId)
        if (entryConfig == null) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "[YggdrasilFlow] 未找到 Entry ${result.entryId} 的配置，Profile 解析回退为透传远端 UUID: ${result.profile.id}"
            }
            return result.profile.id
        }

        return if (entryConfig.yggdrasil.passYggdrasilUuidToProfileResolve) {
            result.profile.id
        } else {
            null
        }
    }

    private fun yggdrasilCredential(
        entryId: String,
        authenticatedName: String,
        authenticatedUuid: UUID,
        suggestedProfileCreateUuid: UUID?,
        knownProfileId: UUID? = null
    ): YggdrasilHyperZoneCredential {
        return YggdrasilHyperZoneCredential(
            entryDatabaseHelper = entryDatabaseHelper,
            entryId = entryId,
            authenticatedName = authenticatedName,
            authenticatedUUID = authenticatedUuid,
            suggestedProfileCreateUuid = suggestedProfileCreateUuid,
            knownProfileId = knownProfileId
        )
    }
}
