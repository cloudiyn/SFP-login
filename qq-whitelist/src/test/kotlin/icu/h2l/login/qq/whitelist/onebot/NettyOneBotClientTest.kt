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

package icu.h2l.login.qq.whitelist.onebot

import com.google.gson.JsonParser
import icu.h2l.login.qq.whitelist.config.OneBotConfig
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用本地 Netty WebSocket 服务端模拟 OneBot，验证客户端握手、令牌、事件解析与发送格式。
 */
class NettyOneBotClientTest {

    private lateinit var bossGroup: EventLoopGroup
    private lateinit var workerGroup: EventLoopGroup
    private lateinit var serverChannel: Channel

    private val handshakeCompleted = LinkedBlockingQueue<Channel>()
    private val receivedFrames = LinkedBlockingQueue<String>()
    private val authorization = AtomicReference<String?>()
    private val events = LinkedBlockingQueue<Triple<Long, Long, String>>()

    private var port = 0

    @BeforeEach
    fun startServer() {
        bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        workerGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        serverChannel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(65536))
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is FullHttpRequest) {
                                authorization.set(msg.headers().get("Authorization"))
                            }
                            ctx.fireChannelRead(msg)
                        }
                    })
                    ch.pipeline().addLast(WebSocketServerProtocolHandler("/", null, true))
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                            if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
                                handshakeCompleted.add(ctx.channel())
                            }
                            ctx.fireUserEventTriggered(evt)
                        }
                    })
                    ch.pipeline().addLast(object : SimpleChannelInboundHandler<TextWebSocketFrame>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
                            receivedFrames.add(frame.text())
                        }
                    })
                }
            })
            .bind("127.0.0.1", 0)
            .sync()
            .channel()
        port = (serverChannel.localAddress() as InetSocketAddress).port
    }

    @AfterEach
    fun stopServer() {
        serverChannel.close().syncUninterruptibly()
        bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    }

    @Test
    fun `connects with token parses group events and sends group messages`() {
        val config = OneBotConfig().apply {
            url = "ws://127.0.0.1:$port"
            token = "secret-token"
            heartbeatTimeoutSeconds = 30
        }
        val client = NettyOneBotClient(config)
        client.setGroupMessageHandler { groupId, userId, rawMessage ->
            events.add(Triple(groupId, userId, rawMessage))
        }
        try {
            client.start()
            val serverSide = awaitHandshake()
            assertEquals("Bearer secret-token", authorization.get())

            serverSide.writeAndFlush(
                TextWebSocketFrame(
                    """{"post_type":"message","message_type":"group","group_id":100,"user_id":42,"self_id":1,"raw_message":"绑定 123456"}"""
                )
            )
            assertEquals(Triple(100L, 42L, "绑定 123456"), events.poll(3, TimeUnit.SECONDS))

            serverSide.writeAndFlush(
                TextWebSocketFrame(
                    """{"post_type":"message","message_type":"private","group_id":100,"user_id":42,"self_id":1,"raw_message":"忽略我"}"""
                )
            )
            assertNull(events.poll(300, TimeUnit.MILLISECONDS), "私聊消息不应回调到群消息处理器")

            client.sendGroupMessage(100L, "请先绑定 QQ")
            val sent = receivedFrames.poll(3, TimeUnit.SECONDS)
            assertNotNull(sent, "客户端应发出 send_group_msg 请求")
            val json = JsonParser.parseString(sent).asJsonObject
            assertEquals("send_group_msg", json.get("action").asString)
            val params = json.getAsJsonObject("params")
            assertEquals(100L, params.get("group_id").asLong)
            assertEquals("请先绑定 QQ", params.get("message").asString)
        } finally {
            client.stop()
        }
    }

    @Test
    fun `extracts plain text from message segments`() {
        val config = OneBotConfig().apply {
            url = "ws://127.0.0.1:$port"
            heartbeatTimeoutSeconds = 30
        }
        val client = NettyOneBotClient(config)
        client.setGroupMessageHandler { groupId, userId, rawMessage ->
            events.add(Triple(groupId, userId, rawMessage))
        }
        try {
            client.start()
            val serverSide = awaitHandshake()
            serverSide.writeAndFlush(
                TextWebSocketFrame(
                    """{"post_type":"message","message_type":"group","group_id":200,"user_id":7,"self_id":1,"message":[{"type":"text","data":{"text":"绑定 "}},{"type":"text","data":{"text":"654321"}}]}"""
                )
            )
            assertEquals(Triple(200L, 7L, "绑定 654321"), events.poll(3, TimeUnit.SECONDS))
        } finally {
            client.stop()
        }
    }

    @Test
    fun `ignores events sent by the bot itself`() {
        val config = OneBotConfig().apply {
            url = "ws://127.0.0.1:$port"
            heartbeatTimeoutSeconds = 30
        }
        val client = NettyOneBotClient(config)
        client.setGroupMessageHandler { groupId, userId, rawMessage ->
            events.add(Triple(groupId, userId, rawMessage))
        }
        try {
            client.start()
            val serverSide = awaitHandshake()
            serverSide.writeAndFlush(
                TextWebSocketFrame(
                    """{"post_type":"message","message_type":"group","group_id":300,"user_id":9,"self_id":9,"raw_message":"自己发的"}"""
                )
            )
            assertNull(events.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            client.stop()
        }
    }

    private fun awaitHandshake(): Channel {
        val channel = handshakeCompleted.poll(5, TimeUnit.SECONDS)
        assertTrue(channel != null, "服务端应在 5 秒内完成 WebSocket 握手")
        return channel!!
    }
}
