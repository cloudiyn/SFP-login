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

package cn.starfallplain.sfplogin.qq.whitelist.onebot

/**
 * 群消息回调。
 */
fun interface OneBotGroupMessageHandler {
    /**
     * @param groupId 群号
     * @param userId 发送者 QQ 号
     * @param rawMessage 原始文本消息
     */
    fun onGroupMessage(groupId: Long, userId: Long, rawMessage: String)
}

/**
 * OneBot v11 客户端抽象（正向 WebSocket）。
 *
 * 抽成接口是为了让业务层单元测试可以注入假实现。
 */
interface OneBotClient {
    /**
     * 建立连接并开始接收事件。
     */
    fun start()

    /**
     * 主动断开并停止重连。
     */
    fun stop()

    /**
     * 当前是否已连接。
     */
    fun isConnected(): Boolean

    /**
     * 发送群消息；未连接时实现方应记录日志并丢弃。
     */
    fun sendGroupMessage(groupId: Long, text: String)

    /**
     * 设置群消息回调。
     */
    fun setGroupMessageHandler(handler: OneBotGroupMessageHandler)
}
