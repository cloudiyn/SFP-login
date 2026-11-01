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

package cn.starfallplain.sfplogin.util

import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.HyperZoneLogApi
import cn.starfallplain.sfplogin.api.log.HyperZoneLogger
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.api.log.debug as apiDebug
import cn.starfallplain.sfplogin.api.log.info as apiInfo

private object VelocityLoggerBridge : HyperZoneLogger {
    override fun info(message: String) {
        val logger = SFPLoginMain.getInstance().logger
        if (logger.isInfoEnabled) {
            logger.info(message)
        }
    }

    override fun debug(type: HyperZoneDebugType, message: String) {
        val logger = SFPLoginMain.getInstance().logger
        if (isDebugEnabled(type)) {
//            不要改成debug，不需要logger开启debug
            logger.info("[DEBUG][{}] {}", type.name, message)
        }
    }

    override fun isDebugEnabled(type: HyperZoneDebugType): Boolean {
        return SFPLoginMain.getCoreConfig().debug.isEnabled(type)
    }

    override fun warn(message: String) {
        SFPLoginMain.getInstance().logger.warn(message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            SFPLoginMain.getInstance().logger.error(message, throwable)
        } else {
            SFPLoginMain.getInstance().logger.error(message)
        }
    }
}

fun registerApiLogger() {
    HyperZoneLogApi.registerLogger(VelocityLoggerBridge)
}

internal inline fun info(block: () -> String) {
    apiInfo(block)
}

internal inline fun debug(block: () -> String) {
    apiDebug(block)
}

internal inline fun debug(type: HyperZoneDebugType, block: () -> String) {
    apiDebug(type, block)
}
