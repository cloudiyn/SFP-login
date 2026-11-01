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

package cn.starfallplain.sfplogin.manager

import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 登录阶段事件派发的统一入口。
 *
 * 登录阶段的几个关键节点（初始 GameProfile 校验、档案替换、登录成功包改写）都靠
 * Velocity 事件把决定权交给模块。事件派发是异步的，但登录流程本身是同步的，
 * 所以必须在**有界时间**内拿到结果：
 *
 * - 事件正常完成 → [Outcome.COMPLETED]，调用方读取事件对象上的修改；
 * - 事件超过 [DEFAULT_TIMEOUT_MILLIS] 未返回 → [Outcome.TIMEOUT]，记警告并按“不改写”降级，
 *   绝不让一个卡住的模块拖死整条登录链路；
 * - 事件处理器抛错 → [Outcome.FAILED]，记错误并按“不改写”降级。
 *
 * 三个结果都不允许把异常抛回调用方：登录阶段抛异常会直接断开玩家，
 * 而这里的语义是“最差也只是保持原样放行”。
 */
internal object LoginRewriteEvents {

    /** 等待一次登录事件的默认上限（毫秒）。 */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000L

    enum class Outcome {
        /** 事件在时限内正常完成。 */
        COMPLETED,

        /** 事件超时，已按降级处理。 */
        TIMEOUT,

        /** 事件处理器抛错，已按降级处理。 */
        FAILED,
    }

    private val logger = LoggerFactory.getLogger(LoginRewriteEvents::class.java)

    /**
     * 派发事件并等待结果，最多 [timeoutMillis] 毫秒。
     *
     * @param label 日志里标识这次派发用途的短标签
     */
    fun fire(
        proxy: ProxyServer,
        event: Any,
        label: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): Outcome {
        val future = proxy.eventManager.fire(event)
        return try {
            future.get(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            Outcome.COMPLETED
        } catch (e: TimeoutException) {
            logger.warn("登录事件 {} 超过 {}ms 未返回，按不改写降级继续登录", label, timeoutMillis)
            Outcome.TIMEOUT
        } catch (e: ExecutionException) {
            logger.error("登录事件 {} 执行失败，按不改写降级继续登录", label, e.cause ?: e)
            Outcome.FAILED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("登录事件 {} 等待被中断，按不改写降级继续登录", label)
            Outcome.FAILED
        } catch (t: Throwable) {
            logger.error("登录事件 {} 派发异常，按不改写降级继续登录", label, t)
            Outcome.FAILED
        }
    }
}
