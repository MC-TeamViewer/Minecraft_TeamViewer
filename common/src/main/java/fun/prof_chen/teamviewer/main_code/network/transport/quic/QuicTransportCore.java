package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportTrafficEvent;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamFrame;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 裸 QUIC 门适配核,在 QuicTransportProcess 构造的 child-first ClassLoader
 * 内运行——本类(连同 netty 4.2 全套 bundle)不参与 relocation:quiche native
 * 的 JNI_OnLoad 用 RegisterNatives 按 {@code io/netty/handler/codec/quic/*}
 * 原名绑定,改名即断链;child-first 隔离则避免与 Minecraft 自带的 netty 4.1
 * 同包名冲突。仅由 {@link QuicTransportProcess} 反射进入。
 *
 * <p>门会话约定(与 WT 门同构,见 TeamViewRelay-Protocol README "传输门约定"):
 * 客户端开 1 条双向流只写(上行),服务端开 1 条单向流只读(下行),可靠流走
 * {@code [varint][payload]} 分帧,10 秒内首帧必须是合法握手。ALPN
 * {@code teamviewrelay/v1}。系统代理对 QUIC 无语义,忽略。</p>
 */
final class QuicTransportCore {
    static final String ALPN = "teamviewrelay/v1";
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long IO_TIMEOUT_SECONDS = 5L;
    private static final int IDLE_TIMEOUT_MS = 30_000;
    private static final int IO_THREADS = 1;

    private static final class IoHolder {
        private static final EventLoopGroup GROUP = new MultiThreadIoEventLoopGroup(
                IO_THREADS, IoHolder::newIoThread, NioIoHandler.newFactory());

        private static Thread newIoThread(Runnable runnable) {
            Thread worker = new Thread(runnable, "teamviewrelay-quic-io");
            worker.setDaemon(true);
            return worker;
        }
    }

    /** 仅供 {@link QuicTransportProcess} 反射调用(跨 loader,getMethod 只见 public)。 */
    public static SocketProcess connect(String uri, TransportOptions options, TransportListener listener)
            throws Exception {
        InetSocketAddress serverAddress = parseServerAddress(uri);
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(options.allowInsecureTls() ? trustAllManager() : systemTrustManager())
                .applicationProtocols(ALPN)
                .build();
        Quic.ensureAvailability();

        Bootstrap udpBootstrap = new Bootstrap()
                .group(IoHolder.GROUP)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1 << 20)
                // initialMaxStreamsUnidirectional 必须为服务端放开下行单向流配额:
                // netty-quic 缺省为 0,不放开则服务端 open_uni 永久阻塞。
                .handler(new QuicClientCodecBuilder()
                        .sslContext(sslContext)
                        .maxIdleTimeout(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .initialMaxData(1 << 20)
                        .initialMaxStreamDataBidirectionalLocal(1 << 18)
                        .initialMaxStreamDataBidirectionalRemote(1 << 18)
                        .initialMaxStreamDataUnidirectional(1 << 18)
                        .initialMaxStreamsBidirectional(16)
                        .initialMaxStreamsUnidirectional(16)
                        .build());

        ChannelFuture bindFuture = udpBootstrap.bind(0);
        if (!bindFuture.await(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !bindFuture.isSuccess()) {
            throw new IOException("local UDP bind failed: " + bindFuture.cause());
        }
        DatagramChannel udpChannel = (DatagramChannel) bindFuture.channel();
        QuicSession session = new QuicSession(listener, udpChannel);
        session.start(serverAddress);
        return session;
    }

    private static InetSocketAddress parseServerAddress(String uri) {
        URI parsed = URI.create(uri.trim());
        String host = parsed.getHost();
        int port = parsed.getPort();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("QUIC URL is missing a host: " + uri);
        }
        if (port <= 0) {
            throw new IllegalArgumentException("QUIC URL requires an explicit port: " + uri);
        }
        return new InetSocketAddress(host, port);
    }

    private static X509TrustManager trustAllManager() {
        // allowInsecureTls 语义与 WS 门一致:信任所有证书(自签名/试用场景)。
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static TrustManager systemTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return Arrays.stream(factory.getTrustManagers())
                .filter(manager -> manager instanceof X509TrustManager)
                .findFirst()
                .orElseThrow(() -> new SSLException("No X509TrustManager in the system trust store"));
    }

    /**
     * 一次 QUIC 门会话:uplink 双向流 + downlink 单向流。本地 close 不回调
     * onClosed(NetworkManager.disconnect 已自行处理断开状态);服务端关闭或
     * 传输故障分别回调 onClosed(应用错误码)/ onFailure。
     */
    private static final class QuicSession implements SocketProcess {
        private final TransportListener listener;
        private final DatagramChannel udpChannel;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean downlinkInstalled = new AtomicBoolean();
        private final CountDownLatch uplinkReady = new CountDownLatch(1);
        private final ConcurrentLinkedQueue<byte[]> outboundBacklog = new ConcurrentLinkedQueue<>();
        private volatile QuicChannel quicChannel;
        private volatile QuicStreamChannel uplink;

        private QuicSession(TransportListener listener, DatagramChannel udpChannel) {
            this.listener = listener;
            this.udpChannel = udpChannel;
        }

        void start(InetSocketAddress serverAddress) {
            Future<QuicChannel> connectFuture = QuicChannel.newBootstrap(udpChannel)
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext context) {
                            QuicStreamChannel stream = (QuicStreamChannel) context.channel();
                            boolean contractDownlink = stream.type() == QuicStreamType.UNIDIRECTIONAL
                                    && !stream.isLocalCreated()
                                    && downlinkInstalled.compareAndSet(false, true);
                            if (contractDownlink) {
                                installDownlinkPump(context);
                            } else {
                                // 合同外流:plain 版只有服务端一条下行单向流
                                context.close();
                            }
                        }
                    })
                    .remoteAddress(serverAddress)
                    .connect();
            connectFuture.addListener(this::onConnected);
        }

        private void onConnected(Future<? super QuicChannel> future) {
            if (!future.isSuccess()) {
                fail("QUIC connect failed: " + future.cause(), future.cause());
                return;
            }
            quicChannel = (QuicChannel) future.getNow();
            Future<QuicStreamChannel> uplinkFuture = quicChannel.createStream(
                    QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelInactive(ChannelHandlerContext context) {
                            notifyClosed(0, "connection closed");
                        }
                    });
            uplinkFuture.addListener(this::onUplinkCreated);
        }

        private void onUplinkCreated(Future<? super QuicStreamChannel> future) {
            if (!future.isSuccess()) {
                fail("QUIC uplink stream failed: " + future.cause(), future.cause());
                return;
            }
            uplink = (QuicStreamChannel) future.getNow();
            byte[] backlog;
            while ((backlog = outboundBacklog.poll()) != null) {
                writeUplink(backlog, backlog.length);
            }
            uplinkReady.countDown();
            String alpn = quicChannel.sslEngine().getApplicationProtocol();
            listener.onOpen(alpn == null ? "" : alpn);
        }

        private void installDownlinkPump(ChannelHandlerContext streamContext) {
            QuicFrameCodec.Reassembler reassembler = new QuicFrameCodec.Reassembler();
            streamContext.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext context, Object message) {
                    ByteBuf buffer;
                    if (message instanceof QuicStreamFrame streamFrame) {
                        buffer = streamFrame.content();
                    } else if (message instanceof ByteBuf byteBuf) {
                        buffer = byteBuf;
                    } else {
                        return;
                    }
                    byte[] chunk = new byte[buffer.readableBytes()];
                    buffer.readBytes(chunk);
                    buffer.release();
                    try {
                        reassembler.feed(chunk, 0, chunk.length);
                        byte[] payload;
                        while ((payload = reassembler.tryTakeFrame()) != null) {
                            listener.onTrafficEvent(new TransportTrafficEvent(
                                    TransportTrafficEvent.Direction.INBOUND,
                                    TransportTrafficEvent.FrameKind.BINARY,
                                    payload.length,
                                    payload.length));
                            listener.onBinaryMessage(payload);
                        }
                    } catch (RuntimeException error) {
                        // 非法帧头属协议违规:显式断连,不得静默等待
                        fail("downlink framing violation: " + error, error);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                    fail("downlink stream error: " + error, error);
                }
            });
            streamContext.channel().config().setAutoRead(true);
            ((QuicStreamChannel) streamContext.channel()).read();
        }

        @Override
        public void send(byte[] payload) {
            if (closed.get() || payload.length == 0) {
                return;
            }
            byte[] framed = QuicFrameCodec.frame(payload);
            if (uplinkReady.getCount() != 0) {
                // onOpen 之前不应有上行流量;兜底排队而非丢弃
                outboundBacklog.offer(framed);
                return;
            }
            writeUplink(framed, payload.length);
        }

        private void writeUplink(byte[] framed, int applicationBytes) {
            QuicStreamChannel stream = uplink;
            if (stream == null || closed.get()) {
                return;
            }
            stream.writeAndFlush(Unpooled.wrappedBuffer(framed)).addListener(future -> {
                if (!future.isSuccess()) {
                    fail("uplink write failed: " + future.cause(), future.cause());
                }
            });
            listener.onTrafficEvent(new TransportTrafficEvent(
                    TransportTrafficEvent.Direction.OUTBOUND,
                    TransportTrafficEvent.FrameKind.BINARY,
                    applicationBytes,
                    framed.length));
        }

        @Override
        public void close(int statusCode, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            QuicChannel channel = quicChannel;
            if (channel != null) {
                channel.close(true, statusCode, Unpooled.EMPTY_BUFFER);
            }
        }

        private void notifyClosed(int statusCode, String reason) {
            if (closed.getAndSet(true)) {
                return;
            }
            listener.onClosed(statusCode, reason);
        }

        private void fail(String message, Throwable error) {
            if (closed.getAndSet(true)) {
                return;
            }
            try {
                listener.onFailure(new IOException(message, error));
            } finally {
                QuicChannel channel = quicChannel;
                if (channel != null) {
                    channel.close(true, 0x1000, Unpooled.EMPTY_BUFFER);
                }
            }
        }
    }
}
