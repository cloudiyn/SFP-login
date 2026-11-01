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

package cn.starfallplain.sfplogin.auth.offline.service

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.event.auth.AuthenticationFailureEvent
import cn.starfallplain.sfplogin.api.password.ChannelPasswordService
import cn.starfallplain.sfplogin.api.password.ChannelPasswordServiceProvider
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistryProvider
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlowProvider
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.auth.offline.OfflineAuthMessages
import cn.starfallplain.sfplogin.auth.offline.api.db.OfflineAuthEntry
import cn.starfallplain.sfplogin.auth.offline.config.AuthOfflineConfigLoader
import cn.starfallplain.sfplogin.auth.offline.db.OfflineAuthRepository
import cn.starfallplain.sfplogin.auth.offline.mail.OfflineAuthEmailSender
import cn.starfallplain.sfplogin.auth.offline.totp.OfflineTotpAuthenticator
import net.kyori.adventure.text.Component
import java.security.SecureRandom
import java.util.*

class OfflineAuthService(
    private val repository: OfflineAuthRepository,
    private val playerAccessor: HyperZonePlayerAccessor,
    private val profileService: HyperZoneProfileService,
    private val emailSender: OfflineAuthEmailSender,
    private val totpAuthenticator: OfflineTotpAuthenticator,
    private val proxy: ProxyServer,
    /** 三渠道共享的密码校验/会话服务；默认取全局绑定实例。 */
    private val passwordService: ChannelPasswordService = ChannelPasswordServiceProvider.get()
) {
    data class Result(val success: Boolean, val message: Component)
    data class SessionCheckResult(val passed: Boolean, val message: Component? = null)
    data class SessionPrepareResult(
        val passed: Boolean,
        val message: Component? = null,
        val credential: OfflineHyperZoneCredential? = null
    )

    private val logger = java.util.logging.Logger.getLogger("hzl-auth-offline")
    private val secureRandom = SecureRandom()

    /** 统一密码表仓库（补全 profileId 等跨渠道字段时使用）。 */
    private val passwordRepository: cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository =
        cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.get()

    fun register(player: Player, password: String): Result {
        val hyperZonePlayer = playerAccessor.getByPlayer(player)

        // 检查凭证渠道能力：若该渠道已禁用注册，直接返回错误
        val channelAbility = CredentialChannelRegistryProvider.getOrNull()?.getChannelAbility("offline")
        if (channelAbility?.canRegister == false) {
            return Result(false, OfflineAuthMessages.REGISTER_DISABLED)
        }

        val username = hyperZonePlayer.getSubmittedCredentials().filterIsInstance<OfflineHyperZoneCredential>()
            .firstOrNull()?.getRegistrationName() ?: hyperZonePlayer.clientOriginalName
        val normalizedName = username.lowercase()
        if (repository.getByName(normalizedName) != null) {
            return Result(false, OfflineAuthMessages.OFFLINE_PASSWORD_ALREADY_SET)
        }

        validatePassword(username, password)?.let {
            return it
        }

        val attachedProfile = profileService.getAttachedProfile(hyperZonePlayer)
        if (attachedProfile != null) {
            return bindExistingProfile(player, hyperZonePlayer, attachedProfile, username, normalizedName, password)
        }

        // 使用凭证探针与 ProfileService 交互，避免裸露传递注册名与 UUID
        val probeCredential = offlineCredential(normalizedName = normalizedName, registrationName = username)
        if (profileService.canCreate(probeCredential)) {
            val profile = try {
                profileService.create(probeCredential)
            } catch (throwable: IllegalStateException) {
                return Result(false, componentFromThrowable(throwable, OfflineAuthMessages.REGISTER_FAILED))
            }

            return createOfflinePasswordEntry(
                player = player,
                hyperZonePlayer = hyperZonePlayer,
                normalizedName = normalizedName,
                password = password,
                profileId = profile.id,
                successMessage = OfflineAuthMessages.REGISTER_SUCCESS,
                failureMessage = OfflineAuthMessages.REGISTER_FAILED,
                markVerified = true,
                issueSession = AuthOfflineConfigLoader.getConfig().main.toChannelPasswordPolicy().sessionEnabled &&
                    AuthOfflineConfigLoader.getConfig().main.session.issueOnRegister
            )
        }

        return Result(false, OfflineAuthMessages.REGISTER_CREATE_BLOCKED)
    }

    private fun bindExistingProfile(
        player: Player,
        hyperZonePlayer: cn.starfallplain.sfplogin.api.player.HyperZonePlayer,
        profile: cn.starfallplain.sfplogin.api.db.Profile,
        username: String,
        normalizedName: String,
        password: String
    ): Result {
        if (!hyperZonePlayer.canBind()) {
            return Result(false, OfflineAuthMessages.REGISTER_BIND_DENIED)
        }
        if (repository.getByProfileId(profile.id) != null || repository.getByName(normalizedName) != null) {
            return Result(false, OfflineAuthMessages.OFFLINE_PASSWORD_ALREADY_SET)
        }

        validatePassword(username, password)?.let {
            return it
        }

        return createOfflinePasswordEntry(
            player = player,
            hyperZonePlayer = hyperZonePlayer,
            normalizedName = normalizedName,
            password = password,
            profileId = profile.id,
            successMessage = OfflineAuthMessages.REGISTER_BOUND_SUCCESS,
            failureMessage = OfflineAuthMessages.REGISTER_FAILED
        )
    }

    private fun createOfflinePasswordEntry(
        player: Player,
        hyperZonePlayer: cn.starfallplain.sfplogin.api.player.HyperZonePlayer,
        normalizedName: String,
        password: String,
        profileId: UUID,
        successMessage: Component,
        failureMessage: Component,
        markVerified: Boolean = false,
        issueSession: Boolean = false
    ): Result {
        val created = repository.create(
            name = normalizedName,
            passwordHash = hashPassword(password),
            hashFormat = HASH_FORMAT_SHA256,
            profileId = profileId
        )
        return if (created) {
            if (markVerified) {
                HyperZoneCredentialFlowProvider.get().submitCredential(
                    hyperZonePlayer,
                    offlineCredential(normalizedName, profileId = profileId)
                )
                runCatching {
                    HyperZoneCredentialFlowProvider.get().overVerify(hyperZonePlayer)
                }.getOrElse { throwable ->
                    return Result(false, componentFromThrowable(throwable, OfflineAuthMessages.PROFILE_ATTACH_FAILED_AFTER_LOGIN))
                }
            }
            if (issueSession) {
                issueSession(profileId, player)
            }
            Result(true, successMessage)
        } else {
            Result(false, failureMessage)
        }
    }

    fun login(player: Player, password: String, totpCode: String? = null): Result {
        return loginInternal(player, null, password, totpCode)
    }

    fun loginAs(player: Player, username: String, password: String, totpCode: String? = null): Result {
        return loginInternal(player, username, password, totpCode)
    }

    private fun loginInternal(player: Player, username: String?, password: String, totpCode: String? = null): Result {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (hyperPlayer.hasAttachedProfile()) {
            return Result(false, OfflineAuthMessages.ALREADY_LOGGED_IN)
        }

        val entry = resolveLoginEntry(player, username)
            ?: return Result(false, missingLoginEntryMessage(hyperPlayer.clientOriginalName, username))

        val policy = AuthOfflineConfigLoader.getConfig().main.toChannelPasswordPolicy()
        when (val verified = passwordService.verifyPassword(entry.toChannelEntry(), password, policy)) {
            is ChannelPasswordService.VerifyResult.Blocked -> {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.RATE_LIMITED,
                    reasonMessage = "offline login temporarily blocked"
                )
                return Result(false, OfflineAuthMessages.loginBlocked(verified.remainingSeconds))
            }

            is ChannelPasswordService.VerifyResult.Wrong -> {
                if (verified.remainingAttempts <= 0) {
                    publishAuthFailure(
                        player = player,
                        authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                        reason = AuthenticationFailureEvent.Reason.RATE_LIMITED,
                        reasonMessage = "offline login blocked after too many failures"
                    )
                    return Result(false, OfflineAuthMessages.loginBlocked(policy.blockSeconds.toLong()))
                }
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.INVALID_CREDENTIALS,
                    reasonMessage = "offline password mismatch"
                )
                return Result(false, OfflineAuthMessages.wrongPasswordWithRemainingAttempts(verified.remainingAttempts))
            }

            is ChannelPasswordService.VerifyResult.NoRecord -> {
                return Result(false, missingLoginEntryMessage(hyperPlayer.clientOriginalName, username))
            }

            is ChannelPasswordService.VerifyResult.Success -> Unit
        }

        if (isTotpEnabled(entry)) {
            val trimmedCode = totpCode?.trim().orEmpty()
            if (trimmedCode.isEmpty()) {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.TOTP_REQUIRED,
                    reasonMessage = "totp code required for offline login"
                )
                return Result(false, OfflineAuthMessages.TOTP_LOGIN_REQUIRED)
            }
            if (!totpAuthenticator.verifyCode(entry.name, entry.totpSecret!!, trimmedCode)) {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.TOTP_INVALID,
                    reasonMessage = "invalid offline totp code"
                )
                return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
            }
        }

        HyperZoneCredentialFlowProvider.get().submitCredential(
            hyperPlayer,
            offlineCredential(entry.name, profileId = entry.profileId)
        )
        runCatching {
            HyperZoneCredentialFlowProvider.get().overVerify(hyperPlayer)
        }.getOrElse { throwable ->
            return Result(false, componentFromThrowable(throwable, OfflineAuthMessages.ATTACHED_PROFILE_MISSING))
        }
        // 档案已在此时完成绑定，补全统一密码表中可能为空的 profileId（旧库/迁移数据）
        ensureProfileBinding(entry.name, entry.profileId)
        issueSession(entry.profileId, player)
        return Result(true, OfflineAuthMessages.LOGIN_SUCCESS)
    }

    fun beginTotpSetup(player: Player, password: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }
        if (isTotpEnabled(entry)) {
            return Result(false, OfflineAuthMessages.TOTP_ALREADY_ENABLED)
        }

        val setup = totpAuthenticator.createSetup(profileId, entry.name)
        return Result(true, OfflineAuthMessages.totpSetupGenerated(setup.secret, setup.otpAuthUrl))
    }

    fun confirmTotpSetup(player: Player, code: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        if (isTotpEnabled(entry)) {
            return Result(false, OfflineAuthMessages.TOTP_ALREADY_ENABLED)
        }

        val pendingSetup = totpAuthenticator.getPendingSetup(profileId)
            ?: return Result(false, OfflineAuthMessages.TOTP_PENDING_NOT_FOUND)
        if (!totpAuthenticator.verifyPendingCode(profileId, entry.name, code)) {
            return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
        }

        return if (repository.updateTotpSecret(profileId, pendingSetup.secret)) {
            totpAuthenticator.clearPendingSetup(profileId)
            repository.clearSession(profileId)
            Result(true, OfflineAuthMessages.TOTP_ENABLED)
        } else {
            Result(false, OfflineAuthMessages.TOTP_ENABLE_FAILED)
        }
    }

    fun disableTotp(player: Player, password: String, code: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }
        val secret = entry.totpSecret ?: return Result(false, OfflineAuthMessages.TOTP_NOT_ENABLED)
        if (!totpAuthenticator.verifyCode(entry.name, secret, code)) {
            return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
        }

        return if (repository.updateTotpSecret(profileId, null)) {
            totpAuthenticator.clearPendingSetup(profileId)
            Result(true, OfflineAuthMessages.TOTP_DISABLED)
        } else {
            Result(false, OfflineAuthMessages.TOTP_DISABLE_FAILED)
        }
    }

    fun changePassword(player: Player, oldPassword: String, newPassword: String): Result {
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED_SIMPLE)
        val profileId = entry.profileId
        if (!verifyPassword(oldPassword, entry)) {
            return Result(false, OfflineAuthMessages.OLD_PASSWORD_WRONG)
        }

        val username = playerAccessor.getByPlayer(player).clientOriginalName

        validatePassword(username, newPassword)?.let {
            return it
        }

        val updated = repository.updatePassword(
            profileId = profileId,
            passwordHash = hashPassword(newPassword),
            hashFormat = HASH_FORMAT_SHA256
        )
        return if (updated) {
            Result(true, OfflineAuthMessages.PASSWORD_CHANGED)
        } else {
            Result(false, OfflineAuthMessages.PASSWORD_UPDATE_FAILED)
        }
    }

    fun logout(player: Player): Result {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (!hyperPlayer.hasAttachedProfile()) {
            return Result(false, OfflineAuthMessages.NOT_LOGGED_IN)
        }

        resolveEntryByPlayer(player)?.let { repository.clearSession(it.profileId) }
        hyperPlayer.resetVerify()
        return Result(true, OfflineAuthMessages.LOGOUT_SUCCESS)
    }

    fun unregister(player: Player, password: String): Result {
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED_SIMPLE)
        val profileId = entry.profileId
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        val deleted = repository.deleteByProfileId(profileId)
        return if (deleted) {
            Result(true, OfflineAuthMessages.UNREGISTER_SUCCESS)
        } else {
            Result(false, OfflineAuthMessages.UNREGISTER_FAILED)
        }
    }

    fun addEmail(player: Player, password: String, email: String, confirmEmail: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        if (!email.equals(confirmEmail, ignoreCase = true)) {
            return Result(false, OfflineAuthMessages.EMAIL_MISMATCH)
        }

        val normalizedEmail = normalizeEmail(email) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        val occupied = repository.getByEmail(normalizedEmail)
        if (occupied != null && occupied.profileId != profileId) {
            return Result(false, OfflineAuthMessages.EMAIL_ALREADY_USED)
        }

        return if (repository.updateEmail(profileId, normalizedEmail)) {
            Result(true, OfflineAuthMessages.EMAIL_ADDED)
        } else {
            Result(false, OfflineAuthMessages.EMAIL_BIND_FAILED)
        }
    }

    fun changeEmail(player: Player, password: String, oldEmail: String, newEmail: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val normalizedOldEmail = normalizeEmail(oldEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val normalizedNewEmail = normalizeEmail(newEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        if (!normalizedOldEmail.equals(entry.email, ignoreCase = true)) {
            return Result(false, OfflineAuthMessages.EMAIL_OLD_MISMATCH)
        }

        val occupied = repository.getByEmail(normalizedNewEmail)
        if (occupied != null && occupied.profileId != profileId) {
            return Result(false, OfflineAuthMessages.EMAIL_ALREADY_USED)
        }

        return if (repository.updateEmail(profileId, normalizedNewEmail)) {
            Result(true, OfflineAuthMessages.EMAIL_CHANGED)
        } else {
            Result(false, OfflineAuthMessages.EMAIL_CHANGE_FAILED)
        }
    }

    fun showEmail(player: Player, password: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        return if (entry.email.isNullOrBlank()) {
            Result(false, OfflineAuthMessages.EMAIL_NOT_SET)
        } else {
            Result(true, OfflineAuthMessages.emailShow(entry.email))
        }
    }

    fun startEmailRecovery(player: Player, email: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val normalizedEmail = normalizeEmail(email) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = repository.getByEmail(normalizedEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_NOT_SET)
        val profileId = entry.profileId
        val now = System.currentTimeMillis()
        val emailConfig = AuthOfflineConfigLoader.getConfig().main.email
        val cooldownMillis = emailConfig.recoveryCooldownSeconds * 1000L

        if (entry.recoveryRequestedAt != null && now - entry.recoveryRequestedAt < cooldownMillis) {
            val remaining = ((cooldownMillis - (now - entry.recoveryRequestedAt)) / 1000).coerceAtLeast(1)
            return Result(false, OfflineAuthMessages.recoveryCooldown(remaining))
        }

        val code = generateRecoveryCode(emailConfig.recoveryCodeLength)
        val expireAt = now + emailConfig.recoveryCodeExpireMinutes * 60_000L
        if (!repository.startRecovery(profileId, hashPassword(code), expireAt, now)) {
            return Result(false, OfflineAuthMessages.RECOVERY_CODE_WRITE_FAILED)
        }

        val deliveryResult = deliverRecoveryCode(player.username, normalizedEmail, code, expireAt)
        if (!deliveryResult.success) {
            repository.clearRecoveryState(profileId)
            return Result(false, OfflineAuthMessages.recoverySendFailure(deliveryResult.diagnosticMessage))
        }

        val successMessage = if (!deliveryResult.diagnosticMessage.isNullOrBlank() &&
            !deliveryResult.diagnosticMessage.equals("SMTP", ignoreCase = true)
        ) {
            OfflineAuthMessages.recoveryEmailSentWithDiagnostic(deliveryResult.diagnosticMessage)
        } else {
            OfflineAuthMessages.RECOVERY_EMAIL_SENT
        }
        return Result(true, successMessage)
    }

    fun verifyRecoveryCode(player: Player, code: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        val storedHash = entry.recoveryCodeHash ?: return Result(false, OfflineAuthMessages.RECOVERY_CODE_NOT_REQUESTED)
        val now = System.currentTimeMillis()

        if (entry.recoveryCodeExpireAt == null || entry.recoveryCodeExpireAt < now) {
            repository.clearRecoveryState(profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_CODE_EXPIRED)
        }

        val emailConfig = AuthOfflineConfigLoader.getConfig().main.email
        if (entry.recoveryVerifyTries >= emailConfig.maxCodeVerifyAttempts) {
            repository.clearRecoveryState(profileId)
            return Result(false, OfflineAuthMessages.recoveryCodeAttemptsExceeded())
        }

        if (hashPassword(code) != storedHash) {
            repository.incrementRecoveryVerifyTries(profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_CODE_INCORRECT)
        }

        val verifiedUntil = now + emailConfig.resetPasswordWindowMinutes * 60_000L
        return if (repository.markRecoveryVerified(profileId, verifiedUntil)) {
            Result(true, OfflineAuthMessages.RECOVERY_CODE_CORRECT)
        } else {
            Result(false, OfflineAuthMessages.RECOVERY_STATE_UPDATE_FAILED)
        }
    }

    fun setPasswordByRecovery(player: Player, newPassword: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val hyperPlayer = playerAccessor.getByPlayer(player)
        val username = hyperPlayer.clientOriginalName
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val profileId = entry.profileId
        val verifiedUntil = entry.resetPasswordVerifiedUntil
        val now = System.currentTimeMillis()
        if (verifiedUntil == null || verifiedUntil < now) {
            repository.clearRecoveryState(profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_PASSWORD_WINDOW_EXPIRED)
        }

        validatePassword(username, newPassword)?.let {
            return it
        }

        val updated = repository.updatePassword(profileId, hashPassword(newPassword), HASH_FORMAT_SHA256)
        if (!updated) {
            return Result(false, OfflineAuthMessages.PASSWORD_RESET_FAILED)
        }

        HyperZoneCredentialFlowProvider.get().submitCredential(
            hyperPlayer,
            offlineCredential(entry.name, profileId = profileId)
        )
        runCatching {
            HyperZoneCredentialFlowProvider.get().overVerify(hyperPlayer)
        }.getOrElse { throwable ->
            return Result(false, componentFromThrowable(throwable, OfflineAuthMessages.ATTACHED_PROFILE_MISSING))
        }
        ensureProfileBinding(entry.name, profileId)
        return Result(true, OfflineAuthMessages.PASSWORD_CHANGE_AUTO_AUTHED)
    }

    fun getJoinPrompts(player: Player): List<Component> {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (hyperPlayer.hasAttachedProfile()) {
            return emptyList()
        }

        val entry = resolveEntryByPlayer(player)
        val prompts = ArrayList<Component>()

        if (entry == null) {
            prompts += OfflineAuthMessages.REGISTER_REQUEST
            prompts += OfflineAuthMessages.LOGIN_OTHER_USERNAME_PROMPT
            return prompts
        }

        prompts += OfflineAuthMessages.LOGIN_REQUEST
        if (isTotpEnabled(entry)) {
            prompts += OfflineAuthMessages.TOTP_LOGIN_HINT
        }
        prompts += OfflineAuthMessages.CHANGE_PASSWORD_PROMPT
        if (AuthOfflineConfigLoader.getConfig().main.prompt.showRecoveryHint && !entry.email.isNullOrBlank()) {
            prompts += OfflineAuthMessages.RECOVERY_HINT
        }
        if (AuthOfflineConfigLoader.getConfig().main.email.enabled) {
            prompts += if (entry.email.isNullOrBlank()) {
                OfflineAuthMessages.EMAIL_ADD_PROMPT
            } else {
                OfflineAuthMessages.emailShow(entry.email)
            }
        }
        if (AuthOfflineConfigLoader.getConfig().main.totp.enabled) {
            prompts += if (isTotpEnabled(entry)) {
                OfflineAuthMessages.TOTP_REMOVE_PROMPT
            } else {
                OfflineAuthMessages.TOTP_ADD_PROMPT
            }
        }
        return prompts
    }

    /**
     * 该玩家是否已存在离线密码档案。
     *
     * 供提示框流程决定展示「登录」还是「注册」界面。
     */
    fun hasOfflineAccount(player: Player): Boolean = resolveEntryByPlayer(player) != null

    /**
     * 该玩家是否已启用 TOTP 二次验证。
     *
     * 供提示框流程决定是否需要额外展示验证码输入。
     */
    fun isTotpEnabledFor(player: Player): Boolean = resolveEntryByPlayer(player)?.let(::isTotpEnabled) == true

    fun tryAutoLoginPrepare(player: Player): SessionPrepareResult? {
        val config = AuthOfflineConfigLoader.getConfig()
        val policy = config.main.toChannelPasswordPolicy()
        if (!policy.sessionEnabled) {
            return null
        }

        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (hyperPlayer.hasAttachedProfile()) {
            return SessionPrepareResult(true)
        }

        val entry = resolveEntryByPlayer(player) ?: return null
        val profileId = entry.profileId
        if (isTotpEnabled(entry) && !config.main.totp.allowSessionBypass) {
            repository.clearSession(profileId)
            publishAuthFailure(
                player = player,
                authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                reason = AuthenticationFailureEvent.Reason.SESSION_REJECTED,
                reasonMessage = "session bypass rejected because totp is enabled"
            )
            return SessionPrepareResult(false, OfflineAuthMessages.TOTP_LOGIN_REQUIRED)
        }

        val currentIp = getPlayerRemoteAddress(player)
        return when (passwordService.checkSession(entry.toChannelEntry(), policy, currentIp)) {
            is ChannelPasswordService.SessionResult.Valid -> SessionPrepareResult(
                passed = true,
                message = OfflineAuthMessages.SESSION_AUTO_LOGIN,
                credential = offlineCredential(entry.name, profileId = profileId)
            )

            is ChannelPasswordService.SessionResult.Invalid -> {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.SESSION_REJECTED,
                    reasonMessage = "session invalid (expired or ip mismatch)"
                )
                SessionPrepareResult(false, OfflineAuthMessages.SESSION_INVALID)
            }

            is ChannelPasswordService.SessionResult.NoSession -> null
        }
    }

    fun tryAutoLogin(player: Player): SessionCheckResult? {
        val prepared = tryAutoLoginPrepare(player) ?: return null
        if (!prepared.passed) {
            return SessionCheckResult(false, prepared.message)
        }
        val hyperPlayer = playerAccessor.getByPlayer(player)
        prepared.credential?.let { HyperZoneCredentialFlowProvider.get().submitCredential(hyperPlayer, it) }
        runCatching {
            HyperZoneCredentialFlowProvider.get().overVerify(hyperPlayer)
        }.getOrElse { throwable ->
            return SessionCheckResult(false, componentFromThrowable(throwable, OfflineAuthMessages.ATTACHED_PROFILE_MISSING))
        }
        return SessionCheckResult(true, prepared.message)
    }

    private fun verifyPassword(password: String, entry: OfflineAuthEntry): Boolean {
        return passwordService.matches(password, entry.passwordHash, entry.hashFormat)
    }

    private fun hashPassword(password: String): String {
        return passwordService.sha256Hex(password)
    }

    /** 把离线条目投影为统一密码表条目，供共享校验/会话服务使用。 */
    private fun OfflineAuthEntry.toChannelEntry(): cn.starfallplain.sfplogin.api.password.ChannelPasswordEntry {
        return cn.starfallplain.sfplogin.api.password.ChannelPasswordEntry(
            id = id,
            channel = cn.starfallplain.sfplogin.api.password.PasswordChannel.OFFLINE.id,
            entryId = "",
            name = name,
            channelKey = name.lowercase(Locale.ROOT),
            profileId = profileId,
            passwordHash = passwordHash,
            hashFormat = hashFormat,
            loginFailCount = loginFailCount,
            loginBlockedUntil = loginBlockedUntil,
            email = email,
            recoveryCodeHash = recoveryCodeHash,
            recoveryCodeExpireAt = recoveryCodeExpireAt,
            recoveryRequestedAt = recoveryRequestedAt,
            recoveryVerifyTries = recoveryVerifyTries,
            resetPasswordVerifiedUntil = resetPasswordVerifiedUntil,
            sessionIp = sessionIp,
            sessionIssuedAt = sessionIssuedAt,
            sessionExpiresAt = sessionExpiresAt,
            totpSecret = totpSecret,
        )
    }

    private fun validatePassword(username: String, password: String): Result? {
        val policy = AuthOfflineConfigLoader.getConfig().main.toChannelPasswordPolicy()
        return when (val error = passwordService.validateNewPassword(username, password, policy)) {
            null -> null
            is ChannelPasswordService.ValidationError.TooShort ->
                Result(false, OfflineAuthMessages.unsafePassword(policy.minLength, policy.maxLength))
            is ChannelPasswordService.ValidationError.TooLong ->
                Result(false, OfflineAuthMessages.unsafePassword(policy.minLength, policy.maxLength))
            is ChannelPasswordService.ValidationError.ContainsName ->
                Result(false, OfflineAuthMessages.passwordContainsName(username))
        }
    }

    private fun resolveLoginEntry(player: Player, explicitUsername: String?): OfflineAuthEntry? {
        val normalizedExplicitName = explicitUsername
            ?.trim()
            ?.takeUnless { it.isEmpty() }
            ?.lowercase(Locale.ROOT)
        if (normalizedExplicitName != null) {
            return repository.getByName(normalizedExplicitName)
        }

        return resolveEntryByPlayer(player)
    }

    /**
     * 档案 attach 成功后补全统一密码表的 profileId。
     *
     * 统一密码表允许在档案建立前先写入"仅密码行"（profileId 为空），
     * 旧库迁移的数据也可能缺失 profileId；这里在确认绑定后补齐，
     * 使 `getByProfileId` 这类按档案索引的查询重新可用。
     */
    private fun ensureProfileBinding(name: String, profileId: UUID) {
        if (profileId == UUID(0L, 0L)) return
        val entry = repository.getByName(name) ?: return
        if (entry.profileId == profileId) return
        runCatching {
            passwordRepository.bindProfileId(entry.id, profileId)
        }.onFailure { throwable ->
            logger.warning("补全统一密码记录 profileId 失败 name=$name pid=$profileId: ${throwable.message}")
        }
    }

    private fun missingLoginEntryMessage(currentName: String, explicitUsername: String?): Component {
        val requestedUsername = explicitUsername?.trim()?.takeUnless { it.isEmpty() }
        return if (requestedUsername != null) {
            OfflineAuthMessages.loginAccountNotFound(requestedUsername)
        } else {
            OfflineAuthMessages.loginCurrentNameNotRegistered(currentName)
        }
    }

    private fun resolveEntryByPlayer(player: Player, allowNameFallback: Boolean = true): OfflineAuthEntry? {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        val profileId = profileService.getAttachedProfile(hyperPlayer)?.id
        if (profileId != null) {
            repository.getByProfileId(profileId)?.let { return it }
        }

        if (!allowNameFallback) {
            return null
        }

        return repository.getByName(hyperPlayer.clientOriginalName.lowercase())
    }

    private fun normalizeEmail(email: String): String? {
        val candidate = email.trim().lowercase(Locale.ROOT)
        if (!EMAIL_PATTERN.matches(candidate)) {
            return null
        }
        return candidate
    }

    private fun ensureEmailFeatureEnabled(): Result? {
        if (!AuthOfflineConfigLoader.getConfig().main.email.enabled) {
            return Result(false, OfflineAuthMessages.EMAIL_DISABLED)
        }
        return null
    }

    private fun ensureTotpFeatureEnabled(): Result? {
        if (!AuthOfflineConfigLoader.getConfig().main.totp.enabled) {
            return Result(false, OfflineAuthMessages.TOTP_DISABLED_BY_CONFIG)
        }
        return null
    }

    private fun componentFromThrowable(throwable: Throwable, fallback: Component): Component {
        val reason = throwable.message?.takeUnless { it.isBlank() }
        return if (reason != null) {
            Component.text(reason)
        } else {
            fallback
        }
    }

    private fun generateRecoveryCode(length: Int): String {
        val resolvedLength = length.coerceAtLeast(4)
        return buildString(resolvedLength) {
            repeat(resolvedLength) {
                append(RECOVERY_CODE_CHARS[secureRandom.nextInt(RECOVERY_CODE_CHARS.length)])
            }
        }
    }

    private fun deliverRecoveryCode(
        playerName: String,
        email: String,
        code: String,
        expireAt: Long
    ): OfflineAuthEmailSender.DeliveryResult {
        val expireMinutes = ((expireAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
        val mailMessage = OfflineAuthEmailSender.RecoveryCodeMailMessage(
            playerName = playerName,
            email = email,
            recoveryCode = code,
            expireMinutes = expireMinutes
        )
        val result = emailSender.sendRecoveryCode(mailMessage)
        if (!result.success) {
            logger.warning("离线找回邮件发送失败: player=$playerName email=$email cause=${result.diagnosticMessage}")
        }
        return result
    }

    private fun issueSession(profileId: UUID, player: Player) {
        val config = AuthOfflineConfigLoader.getConfig()
        val policy = config.main.toChannelPasswordPolicy()
        val entry = repository.getByProfileId(profileId)
        if (entry != null && isTotpEnabled(entry) && !config.main.totp.allowSessionBypass) {
            repository.clearSession(profileId)
            return
        }
        if (!policy.sessionEnabled) {
            repository.clearSession(profileId)
            return
        }
        val currentIp = if (policy.sessionBindIp) getPlayerRemoteAddress(player) else null
        entry?.let { passwordService.issueSession(it.toChannelEntry(), policy, currentIp) }
    }

    private fun getPlayerRemoteAddress(player: Player): String {
        val hostAddress = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = hostAddress.indexOf('%')
        return if (ipv6ScopeIdx == -1) {
            hostAddress
        } else {
            hostAddress.substring(0, ipv6ScopeIdx)
        }
    }

    private fun publishAuthFailure(
        player: Player,
        authType: AuthenticationFailureEvent.AuthType,
        reason: AuthenticationFailureEvent.Reason,
        reasonMessage: String,
        providerId: String? = null,
        throwableSummary: String? = null
    ) {
        proxy.eventManager.fire(
            AuthenticationFailureEvent(
                userName = player.username,
                playerIp = getPlayerRemoteAddress(player),
                authType = authType,
                reason = reason,
                reasonMessage = reasonMessage,
                providerId = providerId,
                throwableSummary = throwableSummary
            )
        )
    }

    private fun isTotpEnabled(entry: OfflineAuthEntry): Boolean {
        return !entry.totpSecret.isNullOrBlank()
    }

    companion object {
        private const val HASH_FORMAT_SHA256 = "sha256"
        private val EMAIL_PATTERN = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
        private const val RECOVERY_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }

    private fun offlineCredential(
        normalizedName: String,
        registrationName: String = normalizedName,
        profileId: UUID? = null
    ): OfflineHyperZoneCredential {
        return OfflineHyperZoneCredential(
            repository = repository,
            registrationName = registrationName,
            normalizedName = normalizedName,
            knownProfileId = profileId,
            passProfileCreateUuid = AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve
        )
    }
}