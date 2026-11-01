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

package cn.starfallplain.sfplogin.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class DatabaseSourceConfig {

    // 数据库类型\n支持的值: SQLITE, MYSQL, MARIADB
    @Comment("config.db.type")
    val type: String = "SQLITE"

    // SQLite 数据库配置
    @Comment("config.db.sqlite")
    val sqlite: SQLiteConfig = SQLiteConfig()

    // MySQL 数据库配置
    @Comment("config.db.mysql")
    val mysql: MySQLConfig = MySQLConfig()

    // MariaDB 数据库配置
    @Comment("config.db.mariadb")
    val mariadb: MariaDBConfig = MariaDBConfig()

    // 数据库表前缀
    @Comment("config.db.table-prefix")
    val tablePrefix: String = "hz_"

    // 连接池配置
    @Comment("config.db.pool")
    val pool: PoolConfig = PoolConfig()

    @ConfigSerializable
    class SQLiteConfig {
        // 数据库文件路径（相对于插件数据目录）
        @Comment("config.db.sqlite.path")
        val path: String = "data/hyperzone_login.db"
    }

    @ConfigSerializable
    class MySQLConfig {
        // 地址
        @Comment("config.db.host")
        val host: String = "localhost"

        // 端口
        @Comment("config.db.port")
        val port: Int = 3306

        // 库名
        @Comment("config.db.database-name")
        val database: String = "hyperzone_login"

        // 用户名
        @Comment("config.db.username")
        val username: String = "root"

        // 密码
        @Comment("config.db.password")
        val password: String = "password"

        // 额外的连接参数
        @Comment("config.db.parameters")
        val parameters: String = "useSSL=false&serverTimezone=UTC&characterEncoding=utf8"

        // JDBC 驱动类
        @Comment("config.db.driver-class-name")
        val driverClassName: String = "com.mysql.cj.jdbc.Driver"
    }

    @ConfigSerializable
    class MariaDBConfig {
        // 地址
        @Comment("config.db.host")
        val host: String = "localhost"

        // 端口
        @Comment("config.db.port")
        val port: Int = 3306

        // 库名
        @Comment("config.db.database-name")
        val database: String = "hyperzone_login"

        // 用户名
        @Comment("config.db.username")
        val username: String = "root"

        // 密码
        @Comment("config.db.password")
        val password: String = "password"

        // 额外的连接参数
        @Comment("config.db.parameters")
        val parameters: String = "useSSL=false&characterEncoding=utf8"

        // JDBC 驱动类
        @Comment("config.db.driver-class-name")
        val driverClassName: String = "org.mariadb.jdbc.Driver"
    }


    @ConfigSerializable
    class PoolConfig {
        // 最大连接数
        @Comment("config.db.pool.max-pool-size")
        val maximumPoolSize: Int = 10

        // 最小空闲连接数
        @Comment("config.db.pool.min-idle")
        val minimumIdle: Int = 2

        // 连接超时时间（毫秒）
        @Comment("config.db.pool.connection-timeout")
        val connectionTimeout: Long = 30000

        // 空闲连接超时时间（毫秒）
        @Comment("config.db.pool.idle-timeout")
        val idleTimeout: Long = 600000

        // 连接最大生命周期（毫秒）
        @Comment("config.db.pool.max-lifetime")
        val maxLifetime: Long = 1800000
    }
}
