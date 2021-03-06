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

package me.lucko.luckperms.bungee;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.bungee.calculators.BungeeCalculatorFactory;
import me.lucko.luckperms.bungee.contexts.BackendServerCalculator;
import me.lucko.luckperms.bungee.contexts.BungeeContextManager;
import me.lucko.luckperms.bungee.contexts.RedisBungeeCalculator;
import me.lucko.luckperms.bungee.listeners.BungeeConnectionListener;
import me.lucko.luckperms.bungee.listeners.BungeePermissionCheckListener;
import me.lucko.luckperms.bungee.messaging.BungeeMessagingFactory;
import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.calculators.PlatformCalculatorFactory;
import me.lucko.luckperms.common.command.CommandManager;
import me.lucko.luckperms.common.config.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.contexts.ContextManager;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.listener.ConnectionListener;
import me.lucko.luckperms.common.managers.group.StandardGroupManager;
import me.lucko.luckperms.common.managers.track.StandardTrackManager;
import me.lucko.luckperms.common.managers.user.StandardUserManager;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;

import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * LuckPerms implementation for the BungeeCord API.
 */
public class LPBungeePlugin extends AbstractLuckPermsPlugin {
    private final LPBungeeBootstrap bootstrap;

    private BungeeSenderFactory senderFactory;
    private BungeeConnectionListener connectionListener;
    private CommandManager commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private ContextManager<ProxiedPlayer> contextManager;

    public LPBungeePlugin(LPBungeeBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public LPBungeeBootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Override
    protected void setupSenderFactory() {
        this.senderFactory = new BungeeSenderFactory(this);
    }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        return EnumSet.of(Dependency.CAFFEINE, Dependency.OKIO, Dependency.OKHTTP);
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new BungeeConfigAdapter(this, resolveConfig());
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new BungeeConnectionListener(this);
        this.bootstrap.getProxy().getPluginManager().registerListener(this.bootstrap, this.connectionListener);
        this.bootstrap.getProxy().getPluginManager().registerListener(this.bootstrap, new BungeePermissionCheckListener(this));
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new BungeeMessagingFactory(this);
    }

    @Override
    protected void registerCommands() {
        this.commandManager = new CommandManager(this);
        this.bootstrap.getProxy().getPluginManager().registerCommand(this.bootstrap, new BungeeCommandExecutor(this, this.commandManager));

        // disable the default Bungee /perms command so it gets handled by the Bukkit plugin
        this.bootstrap.getProxy().getDisabledCommands().add("perms");
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected PlatformCalculatorFactory provideCalculatorFactory() {
        return new BungeeCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new BungeeContextManager(this);
        this.contextManager.registerCalculator(new BackendServerCalculator(this));

        if (this.bootstrap.getProxy().getPluginManager().getPlugin("RedisBungee") != null) {
            this.contextManager.registerStaticCalculator(new RedisBungeeCalculator());
        }
    }

    @Override
    protected void setupPlatformHooks() {

    }

    @Override
    protected AbstractEventBus provideEventBus(LuckPermsApiProvider apiProvider) {
        return new BungeeEventBus(this, apiProvider);
    }

    @Override
    protected void registerApiOnPlatform(LuckPermsApi api) {
        // BungeeCord doesn't have a services manager
    }

    @Override
    protected void registerHousekeepingTasks() {
        this.bootstrap.getScheduler().asyncRepeating(new ExpireTemporaryTask(this), 60L);
        this.bootstrap.getScheduler().asyncRepeating(new CacheHousekeepingTask(this), 2400L);
    }

    @Override
    protected void performFinalSetup() {

    }

    private File resolveConfig() {
        File configFile = new File(this.bootstrap.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            this.bootstrap.getDataFolder().mkdirs();
            try (InputStream is = this.bootstrap.getResourceAsStream("config.yml")) {
                Files.copy(is, configFile.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return configFile;
    }

    @Override
    public Optional<Contexts> getContextForUser(User user) {
        return this.bootstrap.getPlayer(user.getUuid()).map(player -> this.contextManager.getApplicableContexts(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        return Stream.concat(
                Stream.of(getConsoleSender()),
                this.bootstrap.getProxy().getPlayers().stream().map(p -> this.senderFactory.wrap(p))
        );
    }

    @Override
    public Sender getConsoleSender() {
        return this.senderFactory.wrap(this.bootstrap.getProxy().getConsole());
    }

    public BungeeSenderFactory getSenderFactory() {
        return this.senderFactory;
    }

    @Override
    public ConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    @Override
    public StandardUserManager getUserManager() {
        return this.userManager;
    }

    @Override
    public StandardGroupManager getGroupManager() {
        return this.groupManager;
    }

    @Override
    public StandardTrackManager getTrackManager() {
        return this.trackManager;
    }

    @Override
    public ContextManager<ProxiedPlayer> getContextManager() {
        return this.contextManager;
    }

}
