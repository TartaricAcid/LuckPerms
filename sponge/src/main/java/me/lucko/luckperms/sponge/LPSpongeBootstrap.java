/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge;

import com.google.inject.Inject;

import me.lucko.luckperms.api.platform.PlatformType;
import me.lucko.luckperms.common.dependencies.classloader.PluginClassLoader;
import me.lucko.luckperms.common.dependencies.classloader.ReflectionClassLoader;
import me.lucko.luckperms.common.plugin.SchedulerAdapter;
import me.lucko.luckperms.common.plugin.bootstrap.LuckPermsBootstrap;
import me.lucko.luckperms.common.utils.MoreFiles;
import me.lucko.luckperms.sponge.utils.VersionData;

import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.scheduler.AsynchronousExecutor;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.SpongeExecutorService;
import org.spongepowered.api.scheduler.SynchronousExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Bootstrap plugin for LuckPerms running on Sponge.
 */
@Plugin(
        id = "luckperms",
        name = "LuckPerms",
        version = VersionData.VERSION,
        authors = "Luck",
        description = "A permissions plugin",
        url = "https://github.com/lucko/LuckPerms"
)
public class LPSpongeBootstrap implements LuckPermsBootstrap {

    /**
     * A scheduler adapter for the platform
     */
    private final SchedulerAdapter schedulerAdapter;

    /**
     * The plugin classloader
     */
    private final PluginClassLoader classLoader;

    /**
     * The plugin instance
     */
    private final LPSpongePlugin plugin;

    /**
     * The time when the plugin was enabled
     */
    private long startTime;

    // load/enable latches
    private final CountDownLatch loadLatch = new CountDownLatch(1);
    private final CountDownLatch enableLatch = new CountDownLatch(1);

    /**
     * Injected plugin logger
     */
    @Inject
    private Logger logger;

    /**
     * Reference to the central {@link Game} instance in the API
     */
    @Inject
    private Game game;

    /**
     * Reference to the sponge scheduler
     */
    private final Scheduler spongeScheduler;

    /**
     * Injected configuration directory for the plugin
     */
    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDirectory;

    /**
     * Injected plugin container for the plugin
     */
    @Inject
    private PluginContainer pluginContainer;

    @Inject
    public LPSpongeBootstrap(@SynchronousExecutor SpongeExecutorService syncExecutor, @AsynchronousExecutor SpongeExecutorService asyncExecutor) {
        this.spongeScheduler = Sponge.getScheduler();
        this.schedulerAdapter = new SpongeSchedulerAdapter(this, this.spongeScheduler, syncExecutor, asyncExecutor);
        this.classLoader = new ReflectionClassLoader(this);
        this.plugin = new LPSpongePlugin(this);
    }

    // provide adapters

    @Override
    public SchedulerAdapter getScheduler() {
        return this.schedulerAdapter;
    }

    @Override
    public PluginClassLoader getPluginClassLoader() {
        return this.classLoader;
    }

    // lifecycle

    @Listener(order = Order.FIRST)
    public void onEnable(GamePreInitializationEvent event) {
        this.startTime = System.currentTimeMillis();
        try {
            this.plugin.load();
        } finally {
            this.loadLatch.countDown();
        }

        try {
            this.plugin.enable();
        } finally {
            this.enableLatch.countDown();
        }
    }

    @Listener(order = Order.LATE)
    public void onLateEnable(GamePreInitializationEvent event) {
        this.plugin.lateEnable();
    }

    @Listener
    public void onDisable(GameStoppingServerEvent event) {
        this.plugin.disable();
    }

    @Override
    public CountDownLatch getEnableLatch() {
        return this.enableLatch;
    }

    @Override
    public CountDownLatch getLoadLatch() {
        return this.loadLatch;
    }

    // getters for the injected sponge instances

    public Logger getLogger() {
        return this.logger;
    }

    public Game getGame() {
        return this.game;
    }

    public Scheduler getSpongeScheduler() {
        return this.spongeScheduler;
    }

    public Path getConfigPath() {
        return this.configDirectory;
    }

    public PluginContainer getPluginContainer() {
        return this.pluginContainer;
    }

    // provide information about the plugin

    @Override
    public String getVersion() {
        return VersionData.VERSION;
    }

    @Override
    public long getStartupTime() {
        return this.startTime;
    }

    // provide information about the platform

    @Override
    public PlatformType getType() {
        return PlatformType.SPONGE;
    }

    @Override
    public String getServerBrand() {
        return getGame().getPlatform().getContainer(Platform.Component.IMPLEMENTATION).getName();
    }

    @Override
    public String getServerVersion() {
        PluginContainer api = getGame().getPlatform().getContainer(Platform.Component.API);
        PluginContainer impl = getGame().getPlatform().getContainer(Platform.Component.IMPLEMENTATION);
        return api.getName() + ": " + api.getVersion().orElse("null") + " - " + impl.getName() + ": " + impl.getVersion().orElse("null");
    }
    
    @Override
    public Path getDataDirectory() {
        Path dataDirectory = this.game.getGameDirectory().toAbsolutePath().resolve("luckperms");
        try {
            MoreFiles.createDirectoriesIfNotExists(dataDirectory);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return dataDirectory;
    }

    @Override
    public Path getConfigDirectory() {
        return this.configDirectory.toAbsolutePath();
    }

    @Override
    public InputStream getResourceStream(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }

    @Override
    public Optional<Player> getPlayer(UUID uuid) {
        if (!getGame().isServerAvailable()) {
            return Optional.empty();
        }

        return getGame().getServer().getPlayer(uuid);
    }

    @Override
    public Optional<UUID> lookupUuid(String username) {
        if (!getGame().isServerAvailable()) {
            return Optional.empty();
        }

        return getGame().getServer().getGameProfileManager().get(username)
                .thenApply(p -> Optional.of(p.getUniqueId()))
                .exceptionally(x -> Optional.empty())
                .join();
    }

    @Override
    public Optional<String> lookupUsername(UUID uuid) {
        if (!getGame().isServerAvailable()) {
            return Optional.empty();
        }

        return getGame().getServer().getGameProfileManager().get(uuid)
                .thenApply(GameProfile::getName)
                .exceptionally(x -> Optional.empty())
                .join();
    }

    @Override
    public int getPlayerCount() {
        return getGame().isServerAvailable() ? getGame().getServer().getOnlinePlayers().size() : 0;
    }

    @Override
    public Stream<String> getPlayerList() {
        return getGame().isServerAvailable() ? getGame().getServer().getOnlinePlayers().stream().map(Player::getName) : Stream.empty();
    }

    @Override
    public Stream<UUID> getOnlinePlayers() {
        return getGame().isServerAvailable() ? getGame().getServer().getOnlinePlayers().stream().map(Player::getUniqueId) : Stream.empty();
    }

    @Override
    public boolean isPlayerOnline(UUID uuid) {
        return getGame().isServerAvailable() ? getGame().getServer().getPlayer(uuid).map(Player::isOnline).orElse(false) : false;
    }
    
}
