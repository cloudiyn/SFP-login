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

package cn.starfallplain.sfplogin.api.auth

import cn.starfallplain.sfplogin.api.player.HyperZonePlayer

/**
 * 认证提示框的用途。
 */
enum class AuthPromptMode {
    /** 已注册账号，提示输入密码登录。 */
    LOGIN,

    /** 未注册账号，提示设置密码注册。 */
    REGISTER,
}

/**
 * 提示框内的一个文本输入控件。
 *
 * @property key 提交时使用的键，只允许字母、数字与下划线
 * @property label 展示给玩家的标签文案
 * @property maxLength 允许输入的最大长度
 */
data class AuthPromptInput(
    val key: String,
    val label: String,
    val maxLength: Int,
)

/**
 * 一次提示框渲染所需的完整内容。
 *
 * 所有文案都已由调用方渲染为纯文本，展示层不再做国际化处理。
 *
 * @property mode 提示框用途
 * @property title 窗口标题
 * @property bodyLines 正文行（按顺序展示）
 * @property errorLine 非空时作为错误行展示（重开提示框时使用）
 * @property inputs 输入控件，按展示顺序排列
 * @property submitLabel 提交按钮文案
 */
data class AuthPromptRequest(
    val mode: AuthPromptMode,
    val title: String,
    val bodyLines: List<String>,
    val errorLine: String? = null,
    val inputs: List<AuthPromptInput>,
    val submitLabel: String,
)

/**
 * 已打开的提示框句柄。
 */
interface AuthPromptSession {
    /**
     * 按新的请求重新渲染提示框（典型场景：提交失败后展示错误行）。
     */
    fun show(request: AuthPromptRequest)

    /**
     * 关闭提示框，玩家回到等待区的聊天命令交互。
     */
    fun close()
}

/**
 * 认证提示框展示层。
 *
 * 实现方负责某一种客户端形态（Java dialog、Floodgate 基岩表单等）的渲染与提交回收，
 * 不关心账号数据本身。
 */
interface AuthPromptPresenter {
    /**
     * 展示层标识，用于日志与测试（例如 `dialog`、`floodgate-form`）。
     */
    val id: String

    /**
     * 选择优先级，数值越大越优先。基岩表单应高于 Java dialog。
     */
    val priority: Int get() = 0

    /**
     * 本展示层支持的输入长度上限。
     *
     * 调用方用它裁剪 [AuthPromptInput.maxLength]，避免超出客户端控件限制。
     */
    val inputLengthLimit: Int get() = DEFAULT_INPUT_LENGTH_LIMIT

    /**
     * 判定该玩家能否使用本展示层。
     */
    fun isSupported(player: HyperZonePlayer): Boolean

    /**
     * 打开提示框。
     *
     * @param player 目标玩家
     * @param request 首次渲染的内容
     * @param onSubmit 玩家提交时回调，键与 [AuthPromptRequest.inputs] 的 `key` 一致
     * @param onClosed 玩家关闭提示框时回调
     * @return 提示框句柄；无法展示时返回 null
     */
    fun present(
        player: HyperZonePlayer,
        request: AuthPromptRequest,
        onSubmit: (Map<String, String>) -> Unit,
        onClosed: () -> Unit,
    ): AuthPromptSession?

    companion object {
        /** 客户端文本控件默认最大长度（Minecraft dialog `text` 输入默认值）。 */
        const val DEFAULT_INPUT_LENGTH_LIMIT: Int = 32
    }
}

/**
 * 全局提示框展示层注册表。
 *
 * 核心层在启用 dialog 支持时注册 [AuthPromptPresenter]，
 * 基岩表单、第三方展示层可追加注册。
 */
object AuthPromptPresenterRegistry {

    private val presenters = LinkedHashSet<AuthPromptPresenter>()

    @Synchronized
    fun register(presenter: AuthPromptPresenter) {
        presenters.removeAll { it.id == presenter.id }
        presenters += presenter
    }

    @Synchronized
    fun unregister(presenterId: String) {
        presenters.removeAll { it.id == presenterId }
    }

    @Synchronized
    fun all(): List<AuthPromptPresenter> = presenters.toList()

    /**
     * 选择该玩家可用的最高优先级展示层。
     */
    fun select(player: HyperZonePlayer): AuthPromptPresenter? =
        all()
            .filter { runCatching { it.isSupported(player) }.getOrDefault(false) }
            .maxByOrNull { it.priority }

    @Synchronized
    fun clear() {
        presenters.clear()
    }
}
