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

package cn.starfallplain.sfplogin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import cn.starfallplain.sfplogin.api.HyperZoneApi;
import cn.starfallplain.sfplogin.api.HyperZoneApiProvider;
import cn.starfallplain.sfplogin.api.command.HyperChatCommandManager;
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager;
import cn.starfallplain.sfplogin.api.dependency.*;
import cn.starfallplain.sfplogin.api.module.HyperSubModule;
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor;
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistry;
import cn.starfallplain.sfplogin.api.vServer.HyperZoneVServerAdapter;
import cn.starfallplain.sfplogin.util.LegacyDataDirectoryMigrator;
import cn.starfallplain.sfplogin.dependency.RelocatingDependencyPathProcessor;
import cn.starfallplain.sfplogin.metrics.BStatsMetricsBootstrap;
import cn.starfallplain.sfplogin.metrics.BStatsMetricsHandle;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SFPLoginBootstrap implements HyperZoneApi {
    private static final int BSTATS_PLUGIN_ID = 30691;
    private static final String BSTATS_GROUP = "org.bstats";
    private static final Map<String, String> BSTATS_RELOCATIONS = Map.of(
        BSTATS_GROUP,
        "cn.starfallplain.sfplogin.libs.bstats"
    );

    private final ProxyServer proxy;
    private final ComponentLogger logger;
    private final Logger slf4jLogger;
    private final Path dataDirectory;
    private final SFPLoginMain runtime;
    private BStatsMetricsHandle metrics;

    @Inject
    public SFPLoginBootstrap(
        ProxyServer proxy,
        ComponentLogger logger,
        Logger slf4jLogger,
        @DataDirectory Path dataDirectory
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.slf4jLogger = slf4jLogger;
        this.dataDirectory = dataDirectory;
        // 必须在读取配置之前完成旧数据目录迁移，否则配置会按空目录重新生成。
        LegacyDataDirectoryMigrator.migrate(dataDirectory, logger);
        loadRuntimeLibraries();
        this.runtime = new SFPLoginMain(proxy, logger, dataDirectory, this);
        HyperZoneApiProvider.INSTANCE.bind(this);
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) {
        this.runtime.onEnable(event);
        this.metrics = BStatsMetricsBootstrap.initialize(this, this.proxy, this.slf4jLogger, this.dataDirectory, BSTATS_PLUGIN_ID);
    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        if (this.metrics != null) {
            this.metrics.shutdown();
            this.metrics = null;
        }
    }

    @Override
    public ProxyServer getProxy() {
        return this.proxy;
    }

    public ComponentLogger getLogger() {
        return this.logger;
    }

    @Override
    public Path getDataDirectory() {
        return this.dataDirectory;
    }

    @Override
    public HyperZoneDatabaseManager getDatabaseManager() {
        return this.runtime.getDatabaseManager();
    }

    @Override
    public HyperZonePlayerAccessor getHyperZonePlayers() {
        return this.runtime.getHyperZonePlayers();
    }

    @Override
    public HyperChatCommandManager getChatCommandManager() {
        return this.runtime.getChatCommandManager();
    }

    @Override
    public HyperZoneVServerAdapter getServerAdapter() {
        return this.runtime.getServerAdapter();
    }

    @Override
    public CredentialChannelRegistry getCredentialChannelRegistry() {
        return this.runtime.getCredentialChannelRegistry();
    }

    @Override
    public void registerModule(HyperSubModule module) {
        this.runtime.registerModule(module, this);
    }

    private void loadRuntimeLibraries() {
        try {
            List<HyperDependency> dependencies = readRuntimeDependencies();
            new HyperDependencyManager(
                this.dataDirectory.resolve("libs"),
                new VelocityHyperDependencyClassPathAppender(this.proxy, this),
                HyperDependencyRepository.DEFAULT_REPOSITORIES,
                new HyperDependencyProgressListener() {
                    @Override
                    public void onDownloadStart(cn.starfallplain.sfplogin.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
                        logger.info("正在下载运行库: {} <- {}", dependency.id(), repository.getBaseUrl());
                    }

                    @Override
                    public void onDownloadSuccess(cn.starfallplain.sfplogin.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
                        logger.info("运行库下载完成: {}", dependency.id());
                    }

                    @Override
                    public void onDownloadFailure(cn.starfallplain.sfplogin.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Exception exception) {
                        logger.warn("运行库下载失败: {} <- {} ({})", dependency.id(), repository.getBaseUrl(), exception.getMessage());
                    }
                },
                new RelocatingDependencyPathProcessor(
                    RelocatingDependencyPathProcessor.Rule.forGroup(BSTATS_GROUP, BSTATS_RELOCATIONS)
                )
            ).loadDependencies(dependencies);
            this.logger.info("核心运行库已完成动态加载");
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 SFP-login 核心运行库", e);
        }
    }

    private List<HyperDependency> readRuntimeDependencies() throws IOException {
        List<HyperDependency> dependencies = new java.util.ArrayList<>();
        dependencies.addAll(HyperDependencyManifest.readFrom(getClass().getClassLoader()));
        if (dependencies.isEmpty()) {
            throw new IllegalStateException("插件 jar 内未找到构建生成的运行库清单: " + HyperDependencyManifest.RESOURCE_PATH);
        }
        return dependencies.stream()
            .sorted(Comparator.comparingInt(SFPLoginBootstrap::runtimeDependencyPriority))
            .toList();
    }

    private static int runtimeDependencyPriority(HyperDependency dependency) {
        String groupId = dependency.groupId();
        String artifactId = dependency.artifactId();
        if (("org.ow2.asm".equals(groupId) && ("asm".equals(artifactId) || "asm-commons".equals(artifactId)))
            || ("me.lucko".equals(groupId) && "jar-relocator".equals(artifactId))) {
            return 0;
        }
        return switch (groupId) {
            case BSTATS_GROUP -> 1;
            default -> 2;
        };
    }
}


