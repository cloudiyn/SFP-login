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

import com.google.gson.Gson
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import cn.starfallplain.sfplogin.api.event.profile.ProfileSkinPreprocessEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.log.error
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.profile.skin.ProfileSkinModel
import cn.starfallplain.sfplogin.api.profile.skin.ProfileSkinSource
import cn.starfallplain.sfplogin.api.profile.skin.ProfileSkinTextures
import cn.starfallplain.sfplogin.auth.online.record.YggdrasilAuthResult
import cn.starfallplain.sfplogin.auth.online.util.VelocityGson
import java.nio.charset.StandardCharsets
import java.util.*

class YggdrasilProfileSkinDispatcher(
    private val proxy: ProxyServer
) {
    private val gson: Gson = VelocityGson.INSTANCE

    fun fireProfileSkinPreprocessEvent(
        handler: HyperZonePlayer,
        result: YggdrasilAuthResult.Success
    ) {
        val event = ProfileSkinPreprocessEvent(
            hyperZonePlayer = handler,
            authenticatedProfile = result.profile,
            entryId = result.entryId,
            serverUrl = result.serverUrl
        )
        event.textures = extractTextures(result.profile)
        event.source = extractSkinSource(event.textures)

        debug(HyperZoneDebugType.PROFILE_SKIN) {
            "[ProfileSkinFlow] preprocess dispatch start: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}, server=${result.serverUrl}, authenticatedProfile=${describeProfile(result.profile)}, eventTextures=${describeTextures(event.textures)}, eventSource=${describeSource(event.source)}"
        }

        runCatching {
            proxy.eventManager.fire(event).join()
        }.onSuccess {
            debug(HyperZoneDebugType.PROFILE_SKIN) {
                "[ProfileSkinFlow] preprocess dispatch completed: clientOriginal=${handler.clientOriginalName}, entry=${result.entryId}, resultingTextures=${describeTextures(event.textures)}, resultingSource=${describeSource(event.source)}"
            }
        }.onFailure { throwable ->
            error(throwable) { "Profile skin preprocess event failed: ${throwable.message}" }
        }
    }

    private fun extractTextures(profile: GameProfile): ProfileSkinTextures? {
        val property = profile.properties.firstOrNull { it.name.equals("textures", ignoreCase = true) } ?: return null
        return ProfileSkinTextures(property.value, property.signature)
    }

    private fun extractSkinSource(textures: ProfileSkinTextures?): ProfileSkinSource? {
        val value = textures?.value ?: return null
        val decoded = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        val root = gson.fromJson(decoded, Map::class.java)
        val texturesMap = root["textures"] as? Map<*, *> ?: return null
        val skinMap = texturesMap["SKIN"] as? Map<*, *> ?: return null
        val url = skinMap["url"] as? String ?: return null
        val metadata = skinMap["metadata"] as? Map<*, *>
        val model = metadata?.get("model") as? String
        return ProfileSkinSource(url, ProfileSkinModel.normalize(model))
    }

    private fun describeProfile(profile: GameProfile?): String {
        if (profile == null) {
            return "null"
        }
        return "id=${profile.id}, name=${profile.name}, properties=${profile.properties.size}, textures=${describeTextures(profile.properties.firstOrNull { it.name.equals("textures", ignoreCase = true) }?.let { ProfileSkinTextures(it.value, it.signature) })}"
    }

    private fun describeTextures(textures: ProfileSkinTextures?): String {
        if (textures == null) {
            return "none"
        }
        return "present(valueLength=${textures.value.length}, signed=${textures.isSigned})"
    }

    private fun describeSource(source: ProfileSkinSource?): String {
        if (source == null) {
            return "none"
        }
        return "url=${source.skinUrl}, model=${source.model}"
    }
}
