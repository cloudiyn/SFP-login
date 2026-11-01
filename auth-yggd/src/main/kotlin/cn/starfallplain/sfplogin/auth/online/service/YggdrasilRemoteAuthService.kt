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
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.auth.online.config.EntryConfig
import cn.starfallplain.sfplogin.auth.online.db.EntryTableManager
import cn.starfallplain.sfplogin.auth.online.manager.EntryConfigManager
import cn.starfallplain.sfplogin.auth.online.record.YggdrasilAuthRequestContext
import cn.starfallplain.sfplogin.auth.online.record.YggdrasilAuthResult
import cn.starfallplain.sfplogin.auth.online.req.AuthServerConfig
import cn.starfallplain.sfplogin.auth.online.req.AuthenticationRequest
import cn.starfallplain.sfplogin.auth.online.req.AuthenticationRequestEntry
import cn.starfallplain.sfplogin.auth.online.req.AuthenticationResult
import cn.starfallplain.sfplogin.auth.online.req.ConcurrentAuthenticationManager
import cn.starfallplain.sfplogin.auth.online.req.MojangStyleAuthRequest
import cn.starfallplain.sfplogin.auth.online.util.VelocityGson
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.net.http.HttpClient
import java.time.Duration
import java.util.*

class YggdrasilRemoteAuthService(
    private val proxy: ProxyServer,
    private val entryConfigManager: EntryConfigManager,
    private val databaseManager: HyperZoneDatabaseManager,
    private val entryTableManager: EntryTableManager,
    private val profileService: HyperZoneProfileService,
    private val credentialService: YggdrasilCredentialService
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val gson: Gson = VelocityGson.INSTANCE

    fun authenticate(
        context: YggdrasilAuthRequestContext,
        onFirstBatchFailure: (YggdrasilAuthResult) -> Unit
    ): YggdrasilAuthResult = runBlocking {
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
            "开始对玩家 ${context.username} (UUID: ${context.uuid}) 进行Yggdrasil验证"
        }

        val knownEntries = findCandidateEntriesByClientIdentity(context.username, context.uuid)
        if (knownEntries.isNotEmpty()) {
            debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                "玩家 ${context.username} 在数据库中找到 ${knownEntries.size} 个Entry记录"
            }

            val firstBatchRequests = buildAuthRequests(knownEntries)
            if (firstBatchRequests.isNotEmpty()) {
                val firstBatchResult = executeAuthRequests(
                    username = context.username,
                    serverId = context.serverId,
                    playerIp = context.playerIp,
                    requests = firstBatchRequests,
                    batchName = "第一批次"
                )

                if (firstBatchResult.isSuccess) {
                    val firstBatchValidation = validateFirstBatchProfile(context, firstBatchResult)
                    if (firstBatchValidation != null) {
                        return@runBlocking firstBatchValidation
                    }
                    return@runBlocking firstBatchResult
                }

                onFirstBatchFailure(firstBatchResult)
            }
        }

        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "第一批次验证未通过，开始第二批次（所有Yggdrasil Entry）" }
        runSecondBatchAuth(context)
    }

    private fun validateFirstBatchProfile(
        context: YggdrasilAuthRequestContext,
        result: YggdrasilAuthResult
    ): YggdrasilAuthResult? {
        val success = result as? YggdrasilAuthResult.Success ?: return null
        val entryProfileId = credentialService.findBoundProfileIdByAuthenticatedEntry(success)
            ?: return YggdrasilAuthResult.Failed("第一批次验证失败：未获取到 Entry Profile")

        if (profileService.getProfile(entryProfileId) == null) {
            return YggdrasilAuthResult.Failed("第一批次验证失败：无法找到玩家 Profile")
        }

        debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
            "[YggdrasilFlow] 第一批次已确认可信 Profile: user=${context.username} requestUuid=${context.uuid} authenticatedName=${success.profile.name} authenticatedUuid=${success.profile.id} pid=$entryProfileId"
        }
        return null
    }

    private suspend fun runSecondBatchAuth(
        context: YggdrasilAuthRequestContext
    ): YggdrasilAuthResult {
        val secondBatchRequests = buildAuthRequests(entryConfigManager.getAllConfigs().values.toList())
        if (secondBatchRequests.isEmpty()) {
            return YggdrasilAuthResult.NoEntriesConfigured
        }

        return executeAuthRequests(
            username = context.username,
            serverId = context.serverId,
            playerIp = context.playerIp,
            requests = secondBatchRequests,
            batchName = "第二批次"
        )
    }

    private fun findCandidateEntriesByClientIdentity(username: String, uuid: UUID): List<String> {
        val foundEntries = mutableSetOf<String>()
        val allEntries = entryConfigManager.getAllConfigs()

        databaseManager.executeTransaction {
            for ((_, entryConfig) in allEntries) {
                val entryTable = entryTableManager.getEntryTable(entryConfig.id.lowercase()) ?: continue
                val hasRecord = entryTable.selectAll()
                    .where { (entryTable.name eq username) or (entryTable.uuid eq uuid) }
                    .count() > 0
                if (hasRecord) {
                    foundEntries.add(entryConfig.id)
                    debug(HyperZoneDebugType.YGGDRASIL_AUTH) {
                        "在Entry表 ${entryConfig.id} 中找到玩家 $username 的记录"
                    }
                }
            }
        }

        return foundEntries.toList()
    }

    private fun buildAuthRequests(entries: List<Any>): List<Pair<String, AuthenticationRequest>> {
        val requests = mutableListOf<Pair<String, AuthenticationRequest>>()
        for (entry in entries) {
            val entryConfig = when (entry) {
                is String -> entryConfigManager.getConfigById(entry)
                is EntryConfig -> entry
                else -> null
            } ?: continue

            val authServerConfig = AuthServerConfig(
                url = entryConfig.yggdrasil.url,
                name = entryConfig.name,
                connectTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(10)
            )
            val authRequest = MojangStyleAuthRequest(
                config = authServerConfig,
                httpClient = httpClient,
                gson = gson,
                preventProxy = proxy.configuration.shouldPreventClientProxyConnections()
            )
            requests += entryConfig.id to authRequest
        }

        return requests
    }

    private suspend fun executeAuthRequests(
        username: String,
        serverId: String,
        playerIp: String?,
        requests: List<Pair<String, AuthenticationRequest>>,
        batchName: String
    ): YggdrasilAuthResult {
        debug(HyperZoneDebugType.YGGDRASIL_AUTH) { "$batchName: 开始并发验证，共 ${requests.size} 个 Entry" }

        val authManager = ConcurrentAuthenticationManager(
            authRequests = requests.map { AuthenticationRequestEntry(it.first, it.second) },
            globalTimeout = Duration.ofSeconds(30)
        )

        return when (val result = authManager.authenticate(username, serverId, playerIp)) {
            is AuthenticationResult.Success -> YggdrasilAuthResult.Success(
                profile = result.profile,
                entryId = result.entryId ?: "unknown",
                serverUrl = result.serverUrl
            )
            is AuthenticationResult.Failure -> YggdrasilAuthResult.Failed(
                reason = result.reason,
                statusCode = result.statusCode
            )
            is AuthenticationResult.Timeout -> YggdrasilAuthResult.Timeout
        }
    }
}
