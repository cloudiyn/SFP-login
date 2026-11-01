/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.login.auth.offline.listener

// HyperZonePlayerManager.create(...) belongs to core pre-login initialization and is intentionally
// kept in the core `velocity` EventListener. Do not initialize channel here to avoid ordering issues.
import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.connection.OpenPreLoginEvent
import icu.h2l.api.log.info
import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.type.OfflineUUIDType
import icu.h2l.login.auth.offline.util.ExtraUuidUtils

class OfflinePreLoginListener {
    @Subscribe
    fun onPreLogin(event: OpenPreLoginEvent) {
        val uuid = event.uuid
        val name = event.userName
        val host = event.host
        // channel/player initialization is performed by the main plugin's EventListener

        val cfg = AuthOfflineConfigLoader.getConfig().match
        if (!cfg.enable) return

        // 注意：这里必须判断「玩家连接的 host 是否以配置前缀开头」。
        // 早期实现写成 `prefix.startsWith(host)`（参数写反），导致 host 匹配永远为 false，
        // 离线玩家无法通过 host 兜底判定为离线，从而被当成在线模式走 Yggdrasil，
        // 最终被后端以「无效会话」拒绝。判断方向必须保持 host.startsWith(prefix)。
        val offlineHost = matchesOfflineHost(cfg.hostMatch.start, host)
        if (offlineHost) {
            info { "匹配到离线 host 玩家: $name" }
        }
        val offlineUUIDType = ExtraUuidUtils.matchType(uuid, name)

        event.isOnline = !(offlineUUIDType != OfflineUUIDType.UNKNOWN || offlineHost)
        info { "传入 UUID 信息玩家: $name UUID:$uuid 类型: $offlineUUIDType 在线:${event.isOnline}" }
    }

    companion object {
        /**
         * 判断玩家连接使用的 host 是否命中了「离线入口」前缀配置。
         *
         * 命中规则：`host` 以 `prefixes` 中任一前缀开头（大小写不敏感）。
         * 例如默认配置 `prefixes = ["offline", "o-"]` 时，`offline.example.com`、`o-1.example.com` 都会命中，
         * 而 `play.example.com`、`starfallplain.cn` 不会命中。
         */
        internal fun matchesOfflineHost(prefixes: List<String>, host: String): Boolean {
            val normalizedHost = host.trim()
            if (normalizedHost.isEmpty()) return false
            return prefixes.any { prefix ->
                prefix.isNotBlank() && normalizedHost.startsWith(prefix, ignoreCase = true)
            }
        }
    }
}


