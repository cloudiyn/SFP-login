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

package cn.starfallplain.sfplogin.metrics;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import cn.starfallplain.sfplogin.libs.bstats.velocity.Metrics;
import org.slf4j.Logger;

import java.nio.file.Path;

public final class BStatsMetricsBootstrap {
    private BStatsMetricsBootstrap() {
    }

    public static BStatsMetricsHandle initialize(
        Object plugin,
        ProxyServer proxy,
        Logger logger,
        Path dataDirectory,
        int pluginId
    ) {
        Metrics.Factory factory = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ProxyServer.class).toInstance(proxy);
                bind(Logger.class).toInstance(logger);
                bind(Path.class).annotatedWith(DataDirectory.class).toInstance(dataDirectory);
            }
        }).getInstance(Metrics.Factory.class);

        Metrics metrics = factory.make(plugin, pluginId);
        return metrics::shutdown;
    }
}

