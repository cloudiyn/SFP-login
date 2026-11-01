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

package cn.starfallplain.sfplogin.database

/**
 * 数据库配置
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    val tablePrefix: String = "",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeout: Long = 30000,
    val idleTimeout: Long = 600000,
    val maxLifetime: Long = 1800000
) {
    companion object {
        /**
         * 创建 MySQL 配置
         */
        fun mysql(
            host: String,
            port: Int = 3306,
            database: String,
            username: String,
            password: String,
            tablePrefix: String = "",
            parameters: String = "useSSL=false&serverTimezone=UTC&characterEncoding=utf8",
            driverClassName: String = "com.mysql.cj.jdbc.Driver",
            maximumPoolSize: Int = 10,
            minimumIdle: Int = 2,
            connectionTimeout: Long = 30000,
            idleTimeout: Long = 600000,
            maxLifetime: Long = 1800000
        ) = DatabaseConfig(
            jdbcUrl = "jdbc:mysql://$host:$port/$database?$parameters",
            username = username,
            password = password,
            driverClassName = driverClassName,
            tablePrefix = tablePrefix,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeout = connectionTimeout,
            idleTimeout = idleTimeout,
            maxLifetime = maxLifetime
        )

        /**
         * 创建 MariaDB 配置
         */
        fun mariadb(
            host: String,
            port: Int = 3306,
            database: String,
            username: String,
            password: String,
            tablePrefix: String = "",
            parameters: String = "useSSL=false&characterEncoding=utf8",
            driverClassName: String = "org.mariadb.jdbc.Driver",
            maximumPoolSize: Int = 10,
            minimumIdle: Int = 2,
            connectionTimeout: Long = 30000,
            idleTimeout: Long = 600000,
            maxLifetime: Long = 1800000
        ) = DatabaseConfig(
            jdbcUrl = "jdbc:mariadb://$host:$port/$database?$parameters",
            username = username,
            password = password,
            driverClassName = driverClassName,
            tablePrefix = tablePrefix,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeout = connectionTimeout,
            idleTimeout = idleTimeout,
            maxLifetime = maxLifetime
        )
        
        /**
         * 创建 SQLite 配置（推荐用于单机部署）
         * SQLite 使用单线程模式避免并发问题
         */
        fun sqlite(
            path: String = "./data/hyperzone_login.db",
            tablePrefix: String = "",
            connectionTimeout: Long = 30000,
            idleTimeout: Long = 600000,
            maxLifetime: Long = 1800000
        ) = DatabaseConfig(
            jdbcUrl = "jdbc:sqlite:$path?journal_mode=WAL&busy_timeout=30000",
            username = "",
            password = "",
            driverClassName = "org.sqlite.JDBC",
            tablePrefix = tablePrefix,
            // SQLite 必须使用单连接避免并发问题
            maximumPoolSize = 1,
            minimumIdle = 1,
            connectionTimeout = connectionTimeout,
            idleTimeout = idleTimeout,
            maxLifetime = maxLifetime
        )
    }
}
