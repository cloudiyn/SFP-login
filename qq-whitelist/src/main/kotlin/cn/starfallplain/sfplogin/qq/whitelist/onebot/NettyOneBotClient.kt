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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.log.warn
import cn.starfallplain.sfplogin.qq.whitelist.config.OneBotConfig
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 基于 Netty 的 OneBot v11 正向 WebSocket 客户端。
 *
 * 只依赖 Netty 与 Gson（与主插件一致的运行时库），不需要引入额外 HTTP 客户端。
 */
class NettyOneBotClient(
    private val config: OneBotConfig,
    private val logPrefix: String = "qq-whitelist"
) : OneBotClient {
    private val group: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private var channel: Channel? = null
    private var handler: OneBotGroupMessageHandler? = null
    private var started = false

    @Volatile
    private var currentDelaySeconds: Long = 0

    override fun start() {
        if (started) return
        started = true
        connect()
    }

    override fun stop() {
        started = false
        channel?.close()
        channel = null
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
    }

    override fun isConnected(): Boolean = channel?.isActive == true

    override fun setGroupMessageHandler(handler: OneBotGroupMessageHandler) {
        this.handler = handler
    }

    override fun sendGroupMessage(groupId: Long, text: String) {
        val ch = channel
        if (ch == null || !ch.isActive) {
            warn { "[$logPrefix] OneBot 未连接，群 $groupId 的消息未发送: $text" }
            return
        }
        val payload = JsonObject().apply {
            addProperty("action", "send_group_msg")
            add("params", JsonObject().apply {
                addProperty("group_id", groupId)
                addProperty("message", text)
                addProperty("auto_escape", false)
            })
            addProperty("echo", "hzl-${System.nanoTime()}")
        }
        ch.writeAndFlush(TextWebSocketFrame(payload.toString()))
    }

    private fun connect() {
        val uri = runCatching { URI(config.url) }.getOrNull()
        if (uri == null || uri.host == null) {
            warn { "[$logPrefix] OneBot 地址无效: ${config.url}" }
            scheduleReconnect()
            return
        }
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80
        val ssl = uri.scheme.equals("wss", ignoreCase = true)

        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val pipeline = ch.pipeline()
                    if (ssl) {
                        pipeline.addLast(
                            io.netty.handler.ssl.SslContextBuilder.forClient().build()
                                .newHandler(ch.alloc(), uri.host, port)
                        )
                    }
                    pipeline.addLast(HttpClientCodec())
                    pipeline.addLast(HttpObjectAggregator(65536))
                    pipeline.addLast(
                        IdleStateHandler(0, 0, config.heartbeatTimeoutSeconds, TimeUnit.SECONDS)
                    )
                    pipeline.addLast(OneBotChannelHandler(uri))
                }
            })

        bootstrap.connect(uri.host, port).addListener(ChannelFutureListener { future ->
            if (!future.isSuccess) {
                warn { "[$logPrefix] OneBot 连接失败: ${future.cause()?.message}" }
                scheduleReconnect()
            } else {
                channel = future.channel()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!started || !config.reconnect.enable) return
        val min = config.reconnect.minDelaySeconds.coerceAtLeast(1)
        val max = config.reconnect.maxDelaySeconds.coerceAtLeast(min)
        currentDelaySeconds = if (currentDelaySeconds <= 0) min else (currentDelaySeconds * 2).coerceAtMost(max)
        val delay = currentDelaySeconds
        info { "[$logPrefix] OneBot 将在 $delay 秒后重连" }
        group.schedule({ if (started) connect() }, delay, TimeUnit.SECONDS)
    }

    private fun resetDelay() {
        currentDelaySeconds = 0
    }

    private inner class OneBotChannelHandler(private val uri: URI) :
        ChannelInboundHandlerAdapter() {
        private var handshaker: WebSocketClientHandshaker? = null

        override fun channelActive(ctx: ChannelHandlerContext) {
            val headers = DefaultHttpHeaders()
            if (config.token.isNotBlank()) {
                headers.add("Authorization", "Bearer ${config.token}")
            }
            val shaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri,
                WebSocketVersion.V13,
                null,
                true,
                headers
            )
            handshaker = shaker
            shaker.handshake(ctx.channel())
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            try {
                val shaker = handshaker
                if (shaker != null && !shaker.isHandshakeComplete) {
                    val response = msg as? FullHttpResponse
                    if (response == null) {
                        ctx.close()
                        return
                    }
                    shaker.finishHandshake(ctx.channel(), response)
                    channel = ctx.channel()
                    resetDelay()
                    info { "[$logPrefix] OneBot 已连接: ${config.url}" }
                    return
                }
                when (msg) {
                    is TextWebSocketFrame -> handleText(msg.text())
                    is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(msg.content().retain()))
                    is PongWebSocketFrame -> Unit
                    is CloseWebSocketFrame -> ctx.close()
                    is FullHttpResponse -> ctx.close()
                    else -> Unit
                }
            } finally {
                ReferenceCountUtil.release(msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                warn { "[$logPrefix] OneBot 心跳超时，主动断开重连" }
                ctx.close()
                return
            }
            super.userEventTriggered(ctx, evt)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            warn { "[$logPrefix] OneBot 连接已断开" }
            channel = null
            scheduleReconnect()
            super.channelInactive(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            warn { "[$logPrefix] OneBot 连接异常: ${cause.message}" }
            ctx.close()
        }
    }

    private fun handleText(text: String) {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return
        if (root.get("post_type")?.asString != "message") return
        if (root.get("message_type")?.asString != "group") return

        val groupId = root.get("group_id")?.asLongOrNull() ?: return
        val userId = root.get("user_id")?.asLongOrNull() ?: return
        val selfId = root.get("self_id")?.asLongOrNull()
        if (selfId != null && userId == selfId) return

        val rawMessage = root.get("raw_message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: extractText(root.get("message"))

        handler?.onGroupMessage(groupId, userId, rawMessage ?: "")
    }

    private fun extractText(message: JsonElement?): String? {
        val array = message as? JsonArray ?: return null
        val builder = StringBuilder()
        array.forEach { segment ->
            val obj = segment as? JsonObject ?: return@forEach
            if (obj.get("type")?.asString != "text") return@forEach
            val data = obj.getAsJsonObject("data") ?: return@forEach
            builder.append(data.get("text")?.asString ?: "")
        }
        return builder.toString()
    }

    private fun JsonElement.asLongOrNull(): Long? {
        if (!isJsonPrimitive) return null
        return runCatching { asLong }.getOrNull()
    }
}
