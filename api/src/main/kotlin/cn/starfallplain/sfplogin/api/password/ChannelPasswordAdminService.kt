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

package cn.starfallplain.sfplogin.api.password

import cn.starfallplain.sfplogin.api.util.PasswordHasher
import java.util.Locale

/**
 * 跨渠道密码后台管理服务。
 *
 * 三个认证渠道（offline / yggdrasil / floodgate）在改造后共用同一张统一密码表，
 * 因此这里提供**与渠道无关**的按玩家名定位与强制改密能力，供 OP 管理命令使用。
 *
 * 定位规则：统一表的 `name` 列在三个渠道下都存放玩家名（offline 为小写化玩家名，
 * yggdrasil 为入口处填写的名字，floodgate 为基岩版档案名），因此直接按名匹配即可；
 * 同名可能横跨多渠道，由调用方决定如何处理。
 */
class ChannelPasswordAdminService(
    private val repository: ChannelPasswordRepository = ChannelPasswordProvider.get()
) {

    /** 一条按玩家名匹配到的密码记录，附带其渠道信息。 */
    data class Located(
        val channel: PasswordChannel,
        val name: String,
        val entryId: String,
        val channelKey: String,
        val profileId: java.util.UUID?,
        val hasPassword: Boolean
    )

    /**
     * 按玩家名（不区分大小写）查找所有渠道下的密码记录。
     */
    fun locateByName(name: String): List<Located> {
        return repository.findByName(name).mapNotNull { entry ->
            val channel = PasswordChannel.fromId(entry.channel) ?: return@mapNotNull null
            Located(
                channel = channel,
                name = entry.name,
                entryId = entry.entryId,
                channelKey = entry.channelKey,
                profileId = entry.profileId,
                hasPassword = entry.hasPassword
            )
        }
    }

    /**
     * 强制设置某条记录的密码（使用项目默认的 SHA-256 格式），并清除失败计数与封锁。
     *
     * @return true 表示写入成功
     */
    fun forceSetPassword(target: Located, newPassword: String): Boolean {
        val hash = PasswordHasher.sha256(newPassword)
        return repository.setPassword(
            channel = target.channel,
            channelKey = target.channelKey,
            hash = hash,
            format = PasswordHasher.FORMAT_SHA256,
            name = target.name.lowercase(Locale.ROOT),
            entryId = target.entryId,
            profileId = target.profileId
        )
    }

    /**
     * 按玩家名强制改密。
     *
     * 若同名命中多条（跨渠道），则**全部**改为同一新密码，返回实际改动的条数；
     * 未命中任何记录时返回 null，便于上层区分“无此玩家”与“改密失败”。
     */
    fun forceSetPasswordByName(name: String, newPassword: String): Int? {
        val targets = locateByName(name)
        if (targets.isEmpty()) {
            return null
        }
        var changed = 0
        for (target in targets) {
            if (forceSetPassword(target, newPassword)) {
                changed++
            }
        }
        return changed
    }
}
