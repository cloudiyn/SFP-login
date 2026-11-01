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

package cn.starfallplain.sfplogin.listener

import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.connection.getNettyChannel
import cn.starfallplain.sfplogin.api.event.profile.VerifyInitialGameProfileEvent
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.util.buildAttachedIdentityGameProfile
import cn.starfallplain.sfplogin.util.hasSemanticGameProfileDifference
import cn.starfallplain.sfplogin.sfpLoginMain

/**
 * 当当前登录态已经 attach 正式档案时，
 * 核心应允许与该正式档案语义一致的初始 GameProfile 通过校验。
 */
class AttachedProfileInitialGameProfileListener {
    @Subscribe
    fun onVerifyInitialGameProfileEvent(event: VerifyInitialGameProfileEvent) {
        val channel = event.connection.getNettyChannel()
        val hyperPlayer = HyperZonePlayerManager.getByChannelOrNull(channel)
            ?: return
        val attachedProfile = sfpLoginMain().profileService.getAttachedProfile(hyperPlayer)
            ?: return
        val expectedProfile = buildAttachedIdentityGameProfile(
            currentGameProfile = event.gameProfile,
            attachedProfile = attachedProfile,
        )
        if (hasSemanticGameProfileDifference(expectedProfile, event.gameProfile)) {
            return
        }

        event.pass = true
    }
}




