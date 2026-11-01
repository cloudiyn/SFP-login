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

/**
 * 在向后端发送登录档案包前触发，允许监听器替换将要转发的 [GameProfile]。
 *
 * HZL 核心（velocity 模块）会在 [PostOrder.NORMAL] 阶段注册默认监听器，
 * 计算预期档案并在档案与 Velocity 当前持有值不一致时将 [modified] 置为 `true`；
 * 各认证子模块（如 auth-floodgate）可在更高优先级（[PostOrder.LAST]）覆写 [profile]。
 *
 * 当所有监听器处理完毕后：
 * - [modified] 为 `false`：无需重写，处理器立即自移除（retire）；
 * - [modified] 为 `true`：使用 [profile] 替换登录阶段下发给后端的档案包，并输出替换成功日志。
 *
 * @property hyperZonePlayer  当前登录态玩家对象
 * @property targetServerName 此次连接的目标后端服务器名称
 * @property initialProfile   处理器初始解析出的预期档案（由替换器在触发事件前计算）
 */
@AwaitingEvent
class LoginProfileReplaceEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val targetServerName: String,
    val initialProfile: GameProfile,
) {
    /**
     * 监听器可改写的最终转发档案；初始值等于 [initialProfile]。
     */
    var profile: GameProfile = initialProfile

    /**
     * 是否需要执行替换。监听器将 [profile] 设置为预期值后，必须同时将此标志置为 `true`，
     * 否则替换器将退出并不做任何修改。
     */
    var modified: Boolean = false
}

