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

package cn.starfallplain.sfplogin.api.event.profile

import com.velocitypowered.api.event.annotation.AwaitingEvent
import com.velocitypowered.api.util.GameProfile
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.profile.skin.ProfileSkinTextures

/**
 * 在最终构造要发送给后端的正式 [GameProfile] 前触发的皮肤应用事件。
 *
 * 监听器可基于 [baseProfile] 与当前玩家上下文写入最终要注入的 [textures]。
 *
 * @property hyperZonePlayer 当前登录态玩家对象
 * @property baseProfile 当前皮肤注入流程的基础档案
 */
@AwaitingEvent
class ProfileSkinApplyEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val baseProfile: GameProfile
) {
    /**
     * 监听器可写入的最终皮肤纹理数据。
     */
    var textures: ProfileSkinTextures? = null
}

