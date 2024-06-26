/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe;

import dev.waterdog.waterdogpe.command.*;
import dev.waterdog.waterdogpe.command.utils.CommandUtils;
import dev.waterdog.waterdogpe.console.TerminalConsole;
import dev.waterdog.waterdogpe.event.EventManager;
import dev.waterdog.waterdogpe.event.defaults.DispatchCommandEvent;
import dev.waterdog.waterdogpe.event.defaults.ProxyStartEvent;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.network.EventLoops;
import dev.waterdog.waterdogpe.network.NetworkMetrics;
import dev.waterdog.waterdogpe.network.connection.codec.compression.CompressionType;
import dev.waterdog.waterdogpe.network.connection.codec.initializer.OfflineServerChannelInitializer;
import dev.waterdog.waterdogpe.network.connection.codec.initializer.ProxiedServerSessionInitializer;
import dev.waterdog.waterdogpe.network.connection.codec.initializer.ProxiedSessionInitializer;
import dev.waterdog.waterdogpe.network.connection.codec.query.QueryHandler;
import dev.waterdog.waterdogpe.network.connection.handler.DefaultForcedHostHandler;
import dev.waterdog.waterdogpe.network.connection.handler.IForcedHostHandler;
import dev.waterdog.waterdogpe.network.connection.handler.IJoinHandler;
import dev.waterdog.waterdogpe.network.connection.handler.IReconnectHandler;
import dev.waterdog.waterdogpe.network.protocol.ProtocolCodecs;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.updaters.CodecUpdaterCommands;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoMap;
import dev.waterdog.waterdogpe.packs.PackManager;
import dev.waterdog.waterdogpe.player.PlayerManager;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.plugin.PluginManager;
import dev.waterdog.waterdogpe.scheduler.WaterdogScheduler;
import dev.waterdog.waterdogpe.security.SecurityManager;
import dev.waterdog.waterdogpe.utils.ConfigurationManager;
import dev.waterdog.waterdogpe.utils.ThreadFactoryBuilder;
import dev.waterdog.waterdogpe.utils.bstats.Metrics;
import dev.waterdog.waterdogpe.utils.config.LangConfig;
import dev.waterdog.waterdogpe.utils.config.proxy.NetworkSettings;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import dev.waterdog.waterdogpe.utils.reporting.ErrorReporting;
import dev.waterdog.waterdogpe.utils.types.TextContainer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.unix.UnixChannelOption;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import net.cubespace.Yamler.Config.InvalidConfigurationException;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@Getter
public class ProxyServer {
    @Getter
    private static ProxyServer instance;

    private final Path dataPath;
    private final Path pluginPath;
    private final Path packsPath;

    private final MainLogger logger;

    @Getter(AccessLevel.NONE)
    private final TerminalConsole console;

    private final ConfigurationManager configurationManager;
    private final WaterdogScheduler scheduler;
    private final PlayerManager playerManager;
    private final PluginManager pluginManager;
    private final EventManager eventManager;
    private final PackManager packManager;

    private final ServerInfoMap serverInfoMap = new ServerInfoMap();

    @Getter(AccessLevel.NONE)
    private final long serverId;
    @Getter(AccessLevel.NONE)
    private final List<Channel> serverChannels = new ObjectArrayList<>();

    private QueryHandler queryHandler;

    private CommandMap commandMap;

    private final ConsoleCommandSender consoleSender;

    private final SecurityManager securityManager;
    private final ErrorReporting errorReporting;

    @Setter
    private IReconnectHandler reconnectHandler;
    @Setter
    private IJoinHandler joinHandler;
    @Setter
    private IForcedHostHandler forcedHostHandler;

    private NetworkMetrics networkMetrics;
    private final EventLoopGroup bossEventLoopGroup;
    private final EventLoopGroup workerEventLoopGroup;
    @Getter(AccessLevel.NONE)
    private final ScheduledExecutorService tickExecutor;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> tickFuture;
    @Getter(AccessLevel.NONE)
    private volatile boolean shutdown = false;
    private int currentTick = 0;

    public ProxyServer(MainLogger logger, String filePath, String pluginPath) throws InvalidConfigurationException {
        instance = this;
        this.logger = logger;
        this.dataPath = Paths.get(filePath);
        this.pluginPath = Paths.get(pluginPath);
        this.packsPath = this.dataPath.resolve("packs");

        if (!this.pluginPath.toFile().exists()) {
            if (this.pluginPath.toFile().mkdirs())
                this.logger.info("Created Plugin Folder at " + this.pluginPath);
            else
                this.logger.warning("Could not create Plugin Folder at " + this.pluginPath);
        }

        if (!this.packsPath.toFile().exists()) {
            if (this.packsPath.toFile().mkdirs())
                this.logger.info("Created Packs Folder at " + this.packsPath);
            else
                this.logger.warning("Could not create Packs Folder at " + this.packsPath);
        }

        this.configurationManager = new ConfigurationManager(this);
        this.configurationManager.loadProxyConfig();
        this.configurationManager.loadLanguage();
        this.errorReporting = new ErrorReporting(this);

        if (!this.getNetworkSettings().enableIpv6()) {
            // Some devices and networks may not support IPv6
            System.setProperty("java.net.preferIPv4Stack", "true");
        }

        if (this.getConfiguration().isDebug()) {
            WaterdogPE.version().debug(true);
        }

        CompressionType compression = this.getConfiguration().getCompression();
        if (compression.getBedrockAlgorithm() == null) {
            this.logger.error("Bedrock compression supports only ZLIB or Snappy! Currently provided " + compression + ", defaulting to ZLIB!");
            this.getConfiguration().setCompression(CompressionType.ZLIB);
        }

        ThreadFactoryBuilder builder = ThreadFactoryBuilder
                .builder()
                .format("WaterdogTick Executor - #%d")
                .build();
        this.tickExecutor = Executors.newScheduledThreadPool(1, builder);

        EventLoops.ChannelType channelType = EventLoops.getChannelType();
        this.logger.info("Using " + channelType.name() + " channel implementation as default!");
        for (EventLoops.ChannelType type : EventLoops.ChannelType.values()) {
            this.logger.debug("Supported " + type.name() + " channels: " + type.isAvailable());
        }

        ThreadFactoryBuilder workerFactory = ThreadFactoryBuilder.builder()
                .format("Bedrock Listener - #%d")
                .priority(5)
                .daemon(true)
                .build();
        ThreadFactoryBuilder bossFactory = ThreadFactoryBuilder.builder()
                .format("RakNet Listener - #%d")
                .priority(8)
                .daemon(true)
                .build();
        this.workerEventLoopGroup = channelType.newEventLoopGroup(0, workerFactory);
        this.bossEventLoopGroup = channelType.newEventLoopGroup(0, bossFactory);

        // Default Handlers
        this.forcedHostHandler = new DefaultForcedHostHandler();
        this.pluginManager = new PluginManager(this);
        this.scheduler = new WaterdogScheduler(this);
        this.playerManager = new PlayerManager(this);
        this.eventManager = new EventManager(this);
        this.packManager = new PackManager(this);
        this.securityManager = new SecurityManager(this);
        this.consoleSender = new ConsoleCommandSender(this);
        this.commandMap = new DefaultCommandMap(this, SimpleCommandMap.DEFAULT_PREFIX);
        this.console = new TerminalConsole(this);
        this.serverId = ThreadLocalRandom.current().nextLong();

        this.pluginManager.loadAllPlugins();
        this.configurationManager.loadServerInfos(this.serverInfoMap);
        this.reconnectHandler = this.configurationManager.loadServiceProvider(this.getConfiguration().getReconnectHandler(), IReconnectHandler.class, this.pluginManager);
        this.joinHandler = this.configurationManager.loadServiceProvider(this.getConfiguration().getJoinHandler(), IJoinHandler.class, this.pluginManager);

        this.boot();
    }

    private void boot() {
        this.console.getConsoleThread().start();
        this.pluginManager.enableAllPlugins();
        if (Boolean.parseBoolean(System.getProperty("disableFastCodec", "false"))) {
            this.logger.warning("Fast codec is disabled! This may impact the proxy performance!");
        } else {
            this.logger.info("Using fast codec for improved performance and stability!");
            if (this.getConfiguration().injectCommands()) {
                ProtocolCodecs.addUpdater(new CodecUpdaterCommands());
            }

            for (ProtocolVersion version : ProtocolVersion.values()) {
                version.setBedrockCodec(ProtocolCodecs.buildCodec(version.getDefaultCodec()));
            }
        }

        if (this.getConfiguration().isEnableAnonymousStatistics()) {
            this.getLogger().info("Enabling anonymous statistics.");
            Metrics.startMetrics(this, this.getConfiguration());
        }

        if (this.getConfiguration().enableResourcePacks()) {
            this.packManager.loadPacks(this.packsPath);
        }

        InetSocketAddress bindAddress = this.getConfiguration().getBindAddress();
        this.logger.info("Binding to {}", bindAddress);

        if (this.getConfiguration().enableQuery()) {
            this.queryHandler = new QueryHandler(this);
        }

        this.bindChannels(bindAddress);
        for (Integer port : this.getConfiguration().getAdditionalPorts()) {
            InetSocketAddress additionalBind = new InetSocketAddress(bindAddress.getAddress(), port);
            this.bindChannels(additionalBind);
        }

        ProxyStartEvent event = new ProxyStartEvent(this);
        this.eventManager.callEvent(event);

        this.logger.debug("Upstream <-> Proxy compression level " + this.getConfiguration().getUpstreamCompression());
        this.logger.debug("Downstream <-> Proxy compression level " + this.getConfiguration().getDownstreamCompression());
        this.logger.debug("MTU Settings: max_user=" + this.getNetworkSettings().getMaximumMtu() + " max_server=" + this.getNetworkSettings().getMaximumDownstreamMtu());
        this.logger.debug("RakNet Cookies: enabled=" + this.getNetworkSettings().enableCookies());

        ProxiedSessionInitializer.ZLIB_RAW_STRATEGY.getDefaultCompression().setLevel(this.getConfiguration().getUpstreamCompression());
        ProxiedSessionInitializer.ZLIB_STRATEGY.getDefaultCompression().setLevel(this.getConfiguration().getUpstreamCompression());
        // TODO: support downstream compression level too

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        this.tickFuture = this.tickExecutor.scheduleAtFixedRate(this::tickProcessor, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void bindChannels(InetSocketAddress address) {
        boolean allowEpoll = Epoll.isAvailable();
        int bindCount = allowEpoll && EventLoops.getChannelType() != EventLoops.ChannelType.NIO
                ? Runtime.getRuntime().availableProcessors() : 1;

        for (int i = 0; i < bindCount; i++) {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .channelFactory(RakChannelFactory.server(EventLoops.getChannelType().getDatagramChannel()))
                    .group(this.bossEventLoopGroup, this.workerEventLoopGroup)
                    // .option(CustomChannelOption.IP_DONT_FRAG, 2 /* IP_PMTUDISC_DO */)
                    .option(RakChannelOption.RAK_GUID, this.serverId)
                    .option(RakChannelOption.RAK_HANDLE_PING, true)
                    .option(RakChannelOption.RAK_MAX_MTU, this.getNetworkSettings().getMaximumMtu())
                    .option(RakChannelOption.RAK_SEND_COOKIE, this.getNetworkSettings().enableCookies())
                    .childOption(RakChannelOption.RAK_SESSION_TIMEOUT, 10000L)
                    .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                    .handler(new OfflineServerChannelInitializer(this))
                    .childHandler(new ProxiedServerSessionInitializer(this));
            if (allowEpoll) {
                bootstrap.option(UnixChannelOption.SO_REUSEPORT, true);
            }
            ChannelFuture future = bootstrap
                    .bind(address)
                    .syncUninterruptibly();
            if (future.isSuccess()) {
                this.serverChannels.add(future.channel());
            } else {
                throw new IllegalStateException("Could not start server on " + address, future.cause());
            }
        }

        this.getLogger().info(new TranslationContainer("waterdog.query.start", address.toString()).getTranslated());
    }

    private void tickProcessor() {
        if (this.shutdown && !this.tickFuture.isCancelled()) {
            this.tickFuture.cancel(false);
            this.serverChannels.forEach(Channel::close);
        }

        try {
            this.onTick(++this.currentTick);
        } catch (Exception e) {
            this.logger.error("Error while ticking proxy!", e);
        }
    }

    private void onTick(int currentTick) {
        this.scheduler.onTick(currentTick);
    }

    public void shutdown() {
        if (this.shutdown) {
            return;
        }
        this.shutdown = true;

        try {
            this.shutdown0();
        } catch (Exception e) {
            this.logger.error("Unable to shutdown proxy gracefully", e);
        } finally {
            WaterdogPE.shutdownHook();
        }
    }

    private void shutdown0() throws Exception {
        this.pluginManager.disableAllPlugins();
        String disconnectReason = new TranslationContainer("waterdog.server.shutdown").getTranslated();
        for (ProxiedPlayer player : this.playerManager.getPlayers().values()) {
            this.logger.info("Disconnecting " + player.getName());
            player.disconnect(disconnectReason);
        }
        Thread.sleep(500); // Give small delay to send packet

        this.console.getConsoleThread().interrupt();
        this.tickExecutor.shutdown();
        this.scheduler.shutdown();
        this.eventManager.getThreadedExecutor().shutdown();

        if (Metrics.get() != null) {
            Metrics.get().shutdown();
        }

        try {
            for (Channel channel : this.serverChannels) {
                if (channel.isOpen()) {
                    channel.close().syncUninterruptibly();
                }
            }
        } catch (Exception e) {
            this.getLogger().error("Error while shutting down ProxyServer", e);
        }

        if (!this.tickFuture.isCancelled()) {
            this.logger.info("Interrupting scheduler!");
            this.tickFuture.cancel(true);
        }
        this.logger.info("Shutdown complete!");
    }

    public String translate(TextContainer textContainer) {
        return this.getLanguageConfig().translateContainer(textContainer);
    }

    public boolean handlePlayerCommand(ProxiedPlayer player, String message) {
        if (!this.commandMap.handleMessage(player, message)) {
            return false;
        }
        return this.dispatchCommand(player, message.substring(this.commandMap.getCommandPrefix().length()));
    }

    public boolean dispatchCommand(CommandSender sender, String message) {
        if (message.trim().isEmpty()) {
            return false;
        }

        String[] args = message.split(" (?=([^\"]*\"[^\"]*\")*[^\"]*$)");
        for(int i = 0; i < args.length; i++) args[i] = args[i].replace("\"", "");
        if (args.length < 1) {
            return false;
        }

        Command command = this.getCommandMap().getCommand(args[0]);
        if (command == null) {
            return false;
        }

        String[] shiftedArgs;
        if (command.getSettings().isQuoteAware()) { // Quote aware parsing
            List<String> arguments = CommandUtils.parseArguments(message);
            arguments.remove(0);
            shiftedArgs = arguments.toArray(String[]::new);
        } else {
            shiftedArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        }

        DispatchCommandEvent event = new DispatchCommandEvent(sender, args[0], shiftedArgs);
        this.eventManager.callEvent(event);
        return !event.isCancelled() && this.commandMap.handleCommand(event.getSender(), event.getCommand(), event.getArgs());
    }

    public boolean isRunning() {
        return !this.shutdown;
    }

    public ProxyConfig getConfiguration() {
        return this.configurationManager.getProxyConfig();
    }

    public NetworkSettings getNetworkSettings() {
        return this.configurationManager.getProxyConfig().getNetworkSettings();
    }

    public LangConfig getLanguageConfig() {
        return this.configurationManager.getLangConfig();
    }

    public ProxiedPlayer getPlayer(UUID uuid) {
        return this.playerManager.getPlayer(uuid);
    }

    public ProxiedPlayer getPlayer(String playerName) {
        return this.playerManager.getPlayer(playerName);
    }

    public Map<UUID, ProxiedPlayer> getPlayers() {
        return this.playerManager.getPlayers();
    }

    @Deprecated
    public ServerInfo getServer(String serverName) {
        return this.serverInfoMap.get(serverName.toLowerCase());
    }

    /**
     * Allows to add servers dynamically to server map
     *
     * @return if server was registered
     */
    public boolean registerServerInfo(ServerInfo serverInfo) {
        Preconditions.checkNotNull(serverInfo, "ServerInfo cannot be null!");
        return this.serverInfoMap.putIfAbsent(serverInfo.getServerName(), serverInfo) == null;
    }

    /**
     * Remove server from server map
     *
     * @return removed ServerInfo or null
     */
    public ServerInfo removeServerInfo(String serverName) {
        Preconditions.checkNotNull(serverName, "ServerName cannot be null!");
        return this.serverInfoMap.remove(serverName);
    }

    public ServerInfo getServerInfo(String serverName) {
        Preconditions.checkNotNull(serverName, "ServerName cannot be null!");
        return this.serverInfoMap.get(serverName);
    }

    public <T extends ServerInfo> T getServerInfo(String serverName, Class<T> implementation) {
        Preconditions.checkNotNull(serverName, "ServerName cannot be null!");
        Preconditions.checkNotNull(implementation, "Implementation class cannot be null!");

        ServerInfo serverInfo = this.serverInfoMap.get(serverName);
        if (serverInfo != null && !implementation.isAssignableFrom(serverInfo.getClass())) {
            throw new IllegalStateException("Server " + serverName + " is not type of " + implementation.getSimpleName());
        }
        return (T) serverInfo;
    }

    /**
     * Get ServerInfo by address and port
     *
     * @return ServerInfo instance of matched server
     */
    public ServerInfo getServerInfo(String address, int port) {
        Preconditions.checkNotNull(address, "Address cannot be null!");
        for (ServerInfo serverInfo : this.getServers()) {
            if (serverInfo.matchAddress(address, port)) {
                return serverInfo;
            }
        }
        return null;
    }

    /**
     * Get ServerInfo instance using hostname
     *
     * @return ServerInfo assigned to forced host
     */
    public ServerInfo getForcedHost(String serverHostname) {
        Preconditions.checkNotNull(serverHostname, "ServerHostname cannot be null!");
        String serverName = null;

        for (String forcedHost : this.getConfiguration().getForcedHosts().keySet()) {
            if (forcedHost.equalsIgnoreCase(serverHostname)) {
                serverName = this.getConfiguration().getForcedHosts().get(forcedHost);
                break;
            }
        }
        return serverName == null ? null : this.serverInfoMap.get(serverName);
    }

    /**
     * Get all registered ServerInfo instances
     *
     * @return an unmodifiable collection containing all registered ServerInfo instances
     */
    public Collection<ServerInfo> getServers() {
        return this.serverInfoMap.values();
    }

    public void setCommandMap(CommandMap commandMap) {
        Preconditions.checkNotNull(commandMap, "Command map cannot be null!");
        this.commandMap = commandMap;
    }

    public void setNetworkMetrics(NetworkMetrics metrics) {
        Preconditions.checkNotNull(metrics, "You cannot set the metricsHandler to null!");
        this.networkMetrics = metrics;
    }

    @Deprecated
    public boolean isDebug() {
        return WaterdogPE.version().debug();
    }
}
