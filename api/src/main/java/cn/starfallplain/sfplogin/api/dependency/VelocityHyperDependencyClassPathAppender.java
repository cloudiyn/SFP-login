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

package cn.starfallplain.sfplogin.api.dependency;

import com.velocitypowered.api.proxy.ProxyServer;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Adapted from LuckPerms' Velocity classpath appender.
 */
public final class VelocityHyperDependencyClassPathAppender implements HyperDependencyClassPathAppender {
    private final ProxyServer proxy;
    private final Object plugin;

    /**
     * Creates a Velocity-specific classpath appender.
     *
     * @param proxy active proxy instance
     * @param plugin plugin instance requesting the classpath change
     */
    public VelocityHyperDependencyClassPathAppender(ProxyServer proxy, Object plugin) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Adds a jar to the plugin classpath, preferring direct classloader access when available.
     */
    @Override
    public void addJarToClasspath(Path file) {
        ClassLoader classLoader = this.plugin.getClass().getClassLoader();

        try {
            Method addPath = classLoader.getClass().getDeclaredMethod("addPath", Path.class);
            addPath.setAccessible(true);
            addPath.invoke(classLoader, file);
            return;
        } catch (ReflectiveOperationException ignored) {
            // fall back to the public API once the plugin is registered with Velocity
        }

        this.proxy.getPluginManager().addToClasspath(this.plugin, file);
    }
}

