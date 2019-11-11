import bt.DefaultClient;
import bt.data.*;
import bt.data.digest.Digester;
import bt.data.digest.JavaSecurityDigester;
import bt.data.file.FileSystemStorage;
import bt.dht.*;
import bt.event.EventBus;
import bt.magnet.MagnetUri;
import bt.magnet.MagnetUriParser;
import bt.magnet.UtMetadataMessageHandler;
import bt.metainfo.IMetadataService;
import bt.metainfo.MetadataService;
import bt.net.*;
import bt.net.buffer.BufferManager;
import bt.net.buffer.IBufferManager;
import bt.net.extended.ExtendedProtocolHandshakeHandler;
import bt.net.pipeline.BufferedPieceRegistry;
import bt.net.pipeline.ChannelPipelineFactory;
import bt.net.pipeline.IBufferedPieceRegistry;
import bt.net.pipeline.IChannelPipelineFactory;
import bt.net.portmapping.PortMapper;
import bt.net.portmapping.impl.NoOpPortMapper;
import bt.peer.IPeerRegistry;
import bt.peer.PeerRegistry;
import bt.peer.PeerSourceFactory;
import bt.peer.lan.*;
import bt.peerexchange.PeerExchangeConfig;
import bt.peerexchange.PeerExchangeMessageHandler;
import bt.peerexchange.PeerExchangePeerSourceFactory;
import bt.processor.ProcessingContext;
import bt.processor.Processor;
import bt.processor.TorrentProcessorFactory;
import bt.processor.listener.ListenerSource;
import bt.processor.listener.ProcessingEvent;
import bt.processor.magnet.MagnetContext;
import bt.protocol.HandshakeFactory;
import bt.protocol.IHandshakeFactory;
import bt.protocol.StandardBittorrentProtocol;
import bt.protocol.extended.*;
import bt.protocol.handler.MessageHandler;
import bt.protocol.handler.PortMessageHandler;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.service.*;
import bt.torrent.AdhocTorrentRegistry;
import bt.torrent.TorrentRegistry;
import bt.torrent.data.BlockCache;
import bt.torrent.data.DataWorker;
import bt.torrent.data.DefaultDataWorker;
import bt.torrent.data.NoCache;
import bt.torrent.selector.PieceSelector;
import bt.torrent.selector.RarestFirstSelector;
import bt.tracker.ITrackerService;
import bt.tracker.TrackerFactory;
import bt.tracker.TrackerService;
import bt.tracker.http.HttpTrackerFactory;
import bt.tracker.udp.UdpTrackerFactory;
import com.google.inject.Module;
import com.google.inject.Provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class MagnetSample {

    public static IPeerRegistry peerRegistry;

    public static void main(String[] args) {
// get download directory
        Path targetDirectory = Paths.get("/Users", "vdg", "Downloads");

// create file system based backend for torrent data
        Storage storage = new FileSystemStorage(targetDirectory);
        download(storage);
    }

    public static void download(Storage storage) {
        // enable multithreaded verification of torrent data
        Config config = new Config() {
            @Override
            public int getNumOfHashingThreads() {
                return Runtime.getRuntime().availableProcessors() * 2;
            }
        };

// enable bootstrapping from public routers
        DHTConfig dhtConfig = new DHTConfig() {
            @Override
            public boolean shouldUseRouterBootstrap() {
                return true;
            }
        };

        int step = 2 << 22;
        Digester digester = new JavaSecurityDigester("SHA-1", step);
        EventBus eventSource = new EventBus();
        DataReaderFactory dataReaderFactory = new DataReaderFactory(eventSource);
        ChunkVerifier verifier = new DefaultChunkVerifier(digester, config.getNumOfHashingThreads());
        IDataDescriptorFactory dataDescriptorFactory = new DataDescriptorFactory(dataReaderFactory, verifier, config.getTransferBlockSize());
        IRuntimeLifecycleBinder lifecycleBinder = new RuntimeLifecycleBinder();
        TorrentRegistry torrentRegistry = new AdhocTorrentRegistry(dataDescriptorFactory, lifecycleBinder);

        BlockCache blockCache = new NoCache(torrentRegistry);
        DataWorker dataWorker = new DefaultDataWorker(lifecycleBinder, torrentRegistry, verifier, blockCache, config.getMaxIOQueueSize());

        IBufferedPieceRegistry bufferedPieceRegistry = new BufferedPieceRegistry();

        ApplicationService applicationService = new ClasspathApplicationService();
        IdentityService idService = new VersionAwareIdentityService(applicationService);
        TrackerFactory httpTrackerFactory = new HttpTrackerFactory(idService, null, config);
        TrackerFactory httpsTrackerFactory = new HttpTrackerFactory(idService, null, config);
        TrackerFactory updTrackerFactory = new UdpTrackerFactory(idService, lifecycleBinder, config);
        Map<String, TrackerFactory> trackerFactories = new HashMap<>();
        trackerFactories.put("http", httpTrackerFactory);
        trackerFactories.put("https", httpsTrackerFactory);
        trackerFactories.put("upd", updTrackerFactory);
        ITrackerService trackerService = new TrackerService(Collections.unmodifiableMap(trackerFactories));
        Set<PortMapper> portMappers = new HashSet<>();
        portMappers.add(new NoOpPortMapper());
        DHTService dhtService = new MldhtService(lifecycleBinder, config, dhtConfig, portMappers, torrentRegistry, eventSource);
        PeerSourceFactory dhtFactory = new DHTPeerSourceFactory(lifecycleBinder, dhtService);
        PeerExchangeConfig peerExchangeConfig = new PeerExchangeConfig();
        PeerSourceFactory peerExchangeFactory = new PeerExchangePeerSourceFactory(eventSource, lifecycleBinder, peerExchangeConfig);
        SharedSelector selector;
        try {
            selector = new SharedSelector(Selector.open());
        } catch (IOException e) {
            throw new RuntimeException("Failed to get I/O selector", e);
        }
        Provider<IPeerRegistry> peerRegistryProvider = () -> peerRegistry;
        IHandshakeFactory handshakeFactory = new HandshakeFactory(peerRegistryProvider);
        Map<String, MessageHandler<? extends ExtendedMessage>> handlersByTypeName = new HashMap<>();
        handlersByTypeName.put("ut_pex", new PeerExchangeMessageHandler());
        handlersByTypeName.put("ut_metadata", new UtMetadataMessageHandler());
        ExtendedMessageTypeMapping extendedMessageTypeMapping = new AlphaSortedMapping(handlersByTypeName);
        ExtendedHandshakeFactory extendedHandshakeFactory = new ExtendedHandshakeFactory(torrentRegistry, extendedMessageTypeMapping, applicationService, config);
        HandshakeHandler handshakeHandler = new DHTHandshakeHandler(dhtConfig);
        Set<HandshakeHandler> boundHandshakeHandlers = new HashSet<>();
        boundHandshakeHandlers.add(handshakeHandler);
        List<HandshakeHandler> handshakeHandlers = new ArrayList<>(boundHandshakeHandlers);
        // add default handshake handlers to the beginning of the connection handling chain
        handshakeHandlers.add(new BitfieldConnectionHandler(torrentRegistry));
        handshakeHandlers.add(new ExtendedProtocolHandshakeHandler(extendedHandshakeFactory));
        IConnectionHandlerFactory connectionHandlerFactory = new ConnectionHandlerFactory(handshakeFactory, torrentRegistry,
                handshakeHandlers, config.getPeerHandshakeTimeout());
        IBufferManager bufferManager = new BufferManager(config);
        IChannelPipelineFactory channelPipelineFactory = new ChannelPipelineFactory(bufferManager, bufferedPieceRegistry);
        Map<Integer, MessageHandler<?>> extraHandlers = new HashMap<>();
        extraHandlers.put(PortMessageHandler.PORT_ID, new PortMessageHandler());
        extraHandlers.put(ExtendedProtocol.EXTENDED_MESSAGE_ID, new ExtendedProtocol(extendedMessageTypeMapping, handlersByTypeName));
        StandardBittorrentProtocol bittorrentProtocol = new StandardBittorrentProtocol(extraHandlers);
        DataReceiver dataReceiver = new DataReceivingLoop(selector, lifecycleBinder);
        IPeerConnectionFactory connectionFactory = new PeerConnectionFactory(selector, connectionHandlerFactory, channelPipelineFactory,
                bittorrentProtocol, torrentRegistry, bufferManager, dataReceiver, eventSource, config);
        LocalServiceDiscoveryConfig localServiceDiscoveryConfig = new LocalServiceDiscoveryConfig();
        InetSocketAddress localAddress = new InetSocketAddress(config.getAcceptorAddress(), config.getAcceptorPort());
        SocketChannelConnectionAcceptor socketChannelConnectionAcceptor = new SocketChannelConnectionAcceptor(selector, connectionFactory, localAddress);
        Set<PeerConnectionAcceptor> connectionAcceptors = new HashSet<>();
        connectionAcceptors.add(socketChannelConnectionAcceptor);
        Set<SocketChannelConnectionAcceptor> socketAcceptors = connectionAcceptors.stream()
                .filter(a -> a instanceof SocketChannelConnectionAcceptor)
                .map(a -> (SocketChannelConnectionAcceptor) a)
                .collect(Collectors.toSet());
        ILocalServiceDiscoveryInfo info = new LocalServiceDiscoveryInfo(socketAcceptors, localServiceDiscoveryConfig.getLocalServiceDiscoveryAnnounceGroups());
        Collection<AnnounceGroupChannel> groupChannels = info.getCompatibleGroups().stream()
                .map(g -> new AnnounceGroupChannel(g, selector, info.getNetworkInterfaces()))
                .collect(Collectors.toList());
        Cookie cookie = Cookie.newCookie();
        PeerSourceFactory localServiceDiscoveryFactory = new LocalServiceDiscoveryPeerSourceFactory(groupChannels, lifecycleBinder, cookie, localServiceDiscoveryConfig);
        Set<PeerSourceFactory> extraPeerSourceFactories = new HashSet();
        extraPeerSourceFactories.add(dhtFactory);
        extraPeerSourceFactories.add(peerExchangeFactory);
        extraPeerSourceFactories.add(localServiceDiscoveryFactory);
        peerRegistry = new PeerRegistry(lifecycleBinder, idService, torrentRegistry, trackerService, eventSource, extraPeerSourceFactories, config);
        ExecutorService executor = (new ExecutorServiceProvider()).get();
        IPeerConnectionPool peerConnectionPool = new PeerConnectionPool(eventSource, lifecycleBinder, config);
        IConnectionSource connectionSource = new ConnectionSource(connectionAcceptors, connectionFactory, peerConnectionPool, lifecycleBinder, config);
        IMessageDispatcher messageDispatcher = new MessageDispatcher(lifecycleBinder, peerConnectionPool, torrentRegistry, config);
        Set<Object> messagingAgents = new HashSet<>();
        messagingAgents.add(extraPeerSourceFactories);
        IMetadataService metadataService = new MetadataService();
        TorrentProcessorFactory processorFactory = new TorrentProcessorFactory(torrentRegistry, dataWorker, bufferedPieceRegistry, trackerService, executor, peerRegistry,
                connectionSource, peerConnectionPool, messageDispatcher, messagingAgents, metadataService, eventSource, eventSource, config);

        MagnetUri magnetUri = MagnetUriParser.lenientParser().parse("magnet:?xt=urn:btih:9dab6d80a93725614fbc9853f1f420de98943b76&dn=IMG_20191024_142106.jpg");
        PieceSelector pieceSelector = RarestFirstSelector.randomizedRarest();
        ProcessingContext context = new MagnetContext(magnetUri, pieceSelector, storage);

        Class<? extends ProcessingContext> contextType = context.getClass();
        Processor processor = processorFactory.processor(contextType);

        ListenerSource<? extends ProcessingContext> listenerSource = new ListenerSource<>(contextType);
        listenerSource.addListener(ProcessingEvent.DOWNLOAD_COMPLETE, (context1, next) -> null);

        BtRuntime btRuntime = new BtRuntime(config, executor, lifecycleBinder);
        DefaultClient<MagnetContext> client = new DefaultClient(btRuntime, processor, context, listenerSource);
// create client with a private runtime
        /*BtClient client = Bt.client()
                .config(config)
                .storage(storage)
                .magnet("magnet:?xt=urn:btih:9dab6d80a93725614fbc9853f1f420de98943b76&dn=IMG_20191024_142106.jpg")
                .autoLoadModules()
                .module(dhtModule)
                .stopWhenDownloaded()
                .build();*/

// launch
        client.startAsync().join();
    }

}
