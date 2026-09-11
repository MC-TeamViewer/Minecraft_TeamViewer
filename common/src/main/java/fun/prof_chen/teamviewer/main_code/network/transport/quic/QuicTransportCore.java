package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportTrafficEvent;
import fun.prof_chen.teamviewer.main_code.network.proto.door.DatagramDictOffer;
import fun.prof_chen.teamviewer.main_code.network.proto.door.DatagramDictReady;
import fun.prof_chen.teamviewer.main_code.network.proto.door.DoorControlFrame;
import fun.prof_chen.teamviewer.main_code.network.transport.ZstdStreamDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;
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
 * {@code [varint][payload]} 分帧,10 秒内首帧必须是合法握手。压缩套经 ALPN
 * 协商(偏好序 {@code teamviewrelay/v1+zstd-dict} 优先):+zstd* 套下行帧载荷
 * 是连续 zstd 流的压缩块(解压后即 envelope,上行仍 plain)。datagram 选项
 * 已开启:上行位置 upsert 经裸 WireEnvelope datagram 上送(恒 plain);下行
 * movement datagram(alpha.10)仅 +zstd-dict 套消费,字典经门控流协商——
 * 服务端第 2 条单向流承载 DictOffer,装字典后经客户端第 1 条单向流(全连接
 * 唯一)回 DictReady,datagram 按「激活字典 → 前任字典 → 无字典单帧」解码,
 * 失败按丢包静默丢弃。系统代理对 QUIC 无语义,忽略。</p>
 */
final class QuicTransportCore {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuicTransportCore.class);
    static final String ALPN = "teamviewrelay/v1";
    static final String ALPN_ZSTD = ALPN + "+zstd";
    static final String ALPN_ZSTD_DICT = ALPN + "+zstd-dict";
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long IO_TIMEOUT_SECONDS = 5L;
    private static final int DATAGRAM_QUEUE_LEN = 32;
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
                .applicationProtocols(ALPN_ZSTD_DICT, ALPN_ZSTD, ALPN)
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
                        // datagram 上下行队列:上行位置通道(alpha.5)与下行
                        // movement datagram(alpha.10);收发均为尽力投递,不重传
                        .datagram(DATAGRAM_QUEUE_LEN, DATAGRAM_QUEUE_LEN)
                        .congestionControlAlgorithm(QuicCongestionControlAlgorithm.BBR)
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
        /** 服务端第 2 条单向流 = 门控下行(+zstd-dict 套才消费)。 */
        private final AtomicBoolean doorDownlinkInstalled = new AtomicBoolean();
        /** 门控上行流已创建标志:全连接仅此一条,失败/写坏后不重开(重开即
         * 第 2 条客户端单向流,违反门合同)。 */
        private final AtomicBoolean doorUplinkCreated = new AtomicBoolean();
        private final CountDownLatch uplinkReady = new CountDownLatch(1);
        private final ConcurrentLinkedQueue<byte[]> outboundBacklog = new ConcurrentLinkedQueue<>();
        private final MovementDatagramDecoder datagramDecoder = new MovementDatagramDecoder();
        private volatile QuicChannel quicChannel;
        private volatile QuicStreamChannel uplink;
        private volatile QuicStreamChannel doorUplink;
        private volatile ZstdStreamDecoder zstdDecoder;

        private QuicSession(TransportListener listener, DatagramChannel udpChannel) {
            this.listener = listener;
            this.udpChannel = udpChannel;
        }

        void start(InetSocketAddress serverAddress) {
            Future<QuicChannel> connectFuture = QuicChannel.newBootstrap(udpChannel)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext context, Object msg) {
                            try {
                                // 连接级 datagram(尽力投递):下行 movement
                                // datagram(alpha.10)解码后上抛,解不开的
                                // (含 bulk 心跳等非位置载荷)按丢包静默排空
                                byte[] data = datagramBytes(msg);
                                if (data != null && data.length > 0) {
                                    byte[] envelope = decodeMovementDatagram(data);
                                    if (envelope != null) {
                                        listener.onMovementDatagram(envelope);
                                    }
                                }
                            } finally {
                                ReferenceCountUtil.release(msg);
                            }
                        }
                    })
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext context) {
                            QuicStreamChannel stream = (QuicStreamChannel) context.channel();
                            if (stream.type() == QuicStreamType.UNIDIRECTIONAL
                                    && !stream.isLocalCreated()) {
                                if (downlinkInstalled.compareAndSet(false, true)) {
                                    installDownlinkPump(context);
                                    return;
                                }
                                if (ALPN_ZSTD_DICT.equals(negotiatedAlpn())
                                        && doorDownlinkInstalled.compareAndSet(false, true)) {
                                    installDoorControlPump(context);
                                    return;
                                }
                            }
                            // 合同外流(mod 未消费的 bulk 流等):一律关闭
                            context.close();
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
            LOGGER.info("QUIC connection established: local={}",
                    udpChannel.localAddress());
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
            // ALPN 在 TLS 握手内已定(连接级),服务端下行流只会晚于握手打开,
            // 此处读协商结果不存在竞态。
            QuicChannel connection = (QuicChannel) streamContext.channel().parent();
            String alpn = connection.sslEngine().getApplicationProtocol();
            // +zstd 与 +zstd-dict 套的可靠下行同为连续 zstd 流(字典只作用于
            // datagram 单帧),两种套都要装流式解码器
            boolean zstdNegotiated = ALPN_ZSTD.equals(alpn) || ALPN_ZSTD_DICT.equals(alpn);
            long[] chunkWireBytes = new long[1];
            ZstdStreamDecoder decoder = null;
            if (zstdNegotiated) {
                try {
                    decoder = new ZstdStreamDecoder(decompressed -> {
                        listener.onTrafficEvent(new TransportTrafficEvent(
                                TransportTrafficEvent.Direction.INBOUND,
                                TransportTrafficEvent.FrameKind.BINARY,
                                decompressed.length,
                                chunkWireBytes[0]));
                        listener.onBinaryMessage(decompressed);
                    });
                } catch (IOException error) {
                    fail("zstd decoder init failed: " + error, error);
                    streamContext.close();
                    return;
                }
                zstdDecoder = decoder;
            }
            final ZstdStreamDecoder downlinkDecoder = decoder;
            LOGGER.info("QUIC downlink pump installed: alpn='{}', zstdDecoder={}",
                    alpn, decoder != null ? "installed" : "none(plain)");
            final java.util.concurrent.atomic.AtomicBoolean firstBytesLogged =
                    new java.util.concurrent.atomic.AtomicBoolean();
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
                    if (firstBytesLogged.compareAndSet(false, true)) {
                        LOGGER.info("QUIC downlink first bytes received: {} bytes", chunk.length);
                    }
                    try {
                        reassembler.feed(chunk, 0, chunk.length);
                        byte[] payload;
                        while ((payload = reassembler.tryTakeFrame()) != null) {
                            if (downlinkDecoder != null) {
                                // +zstd 套:帧载荷 = 连续 zstd 流的压缩块,
                                // 解压产出即 envelope 本体,经 sink 交付;上行
                                // 恒 plain 分帧(压缩套单向语义)。
                                chunkWireBytes[0] = payload.length;
                                downlinkDecoder.push(payload);
                            } else {
                                listener.onTrafficEvent(new TransportTrafficEvent(
                                        TransportTrafficEvent.Direction.INBOUND,
                                        TransportTrafficEvent.FrameKind.BINARY,
                                        payload.length,
                                        payload.length));
                                listener.onBinaryMessage(payload);
                            }
                        }
                    } catch (IOException | RuntimeException error) {
                        // 非法帧头/解压失败均属协议违规:显式断连,不得静默等待
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

        private void closeDecoder() {
            ZstdStreamDecoder current = zstdDecoder;
            zstdDecoder = null;
            if (current != null) {
                current.close();
            }
            datagramDecoder.close();
        }

        /** 读取协商的 ALPN 套(连接建立前为空串)。 */
        private String negotiatedAlpn() {
            QuicChannel channel = quicChannel;
            if (channel == null || channel.sslEngine() == null) {
                return "";
            }
            String alpn = channel.sslEngine().getApplicationProtocol();
            return alpn == null ? "" : alpn;
        }

        private static byte[] datagramBytes(Object msg) {
            if (msg instanceof ByteBuf buffer) {
                byte[] data = new byte[buffer.readableBytes()];
                buffer.readBytes(data);
                return data;
            }
            return null;
        }

        /** 下行 movement datagram 解码分派:plain 原样透传;+zstd* 走
         * {@link MovementDatagramDecoder}(字典套含轮换宽限),失败返回 null
         * 按丢包丢弃。 */
        private byte[] decodeMovementDatagram(byte[] data) {
            String alpn = negotiatedAlpn();
            if (ALPN_ZSTD_DICT.equals(alpn)) {
                return datagramDecoder.decodeDict(data);
            }
            if (ALPN_ZSTD.equals(alpn)) {
                return datagramDecoder.decodeZstd(data);
            }
            return data;
        }

        /** 门控下行流(服务端第 2 条单向流,+zstd-dict 套独有消费)的泵:
         * {@code [varint][DoorControlFrame]} 分帧,DictOffer → 装字典 →
         * 回 DictReady。分帧/protobuf 解析失败均属协议违规(与下行应用流
         * 同一纪律):显式断连,不得静默等待。 */
        private void installDoorControlPump(ChannelHandlerContext streamContext) {
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
                            handleDoorFrame(payload);
                        }
                    } catch (IOException | RuntimeException error) {
                        fail("door-control framing violation: " + error, error);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                    fail("door-control stream error: " + error, error);
                }
            });
            streamContext.channel().config().setAutoRead(true);
            ((QuicStreamChannel) streamContext.channel()).read();
        }

        /** 门控帧分发:仅消费 DictOffer(装字典 + 回执);无 payload 的合法
         * protobuf 帧按应用层帧混入断连(与后端 door 流纪律一致);未知
         * oneof 成员前向兼容忽略。字典内容异常(超限/加载失败)只拒装不回执
         * ——服务端维持独立压缩,数据问题自愈而非断连。 */
        private void handleDoorFrame(byte[] payload) throws IOException {
            DoorControlFrame frame = DoorControlFrame.parseFrom(payload);
            if (!frame.hasDictOffer()) {
                if (frame.getPayloadCase() == DoorControlFrame.PayloadCase.PAYLOAD_NOT_SET) {
                    throw new IOException("door frame without payload");
                }
                return;
            }
            DatagramDictOffer offer = frame.getDictOffer();
            if (datagramDecoder.install(offer.getContent().toByteArray())) {
                sendDictReady(offer.getDictionaryId());
            }
        }

        /** 门控上行(客户端第 1 条单向流):全连接仅此一条,后续 ready 帧依次
         * 写入同一条流(合同:第 2 条起客户端单向流一律违规断连)。开流失败
         * 后不再重试——字典保持未确认,服务端 datagram 维持独立压缩,链路照常。 */
        private void sendDictReady(String dictionaryId) {
            byte[] framed = QuicFrameCodec.frame(DoorControlFrame.newBuilder()
                    .setDictReady(DatagramDictReady.newBuilder()
                            .setDictionaryId(dictionaryId)
                            .build())
                    .build()
                    .toByteArray());
            QuicStreamChannel stream = doorUplink;
            if (stream != null && stream.isActive()) {
                stream.writeAndFlush(Unpooled.wrappedBuffer(framed));
                return;
            }
            if (!doorUplinkCreated.compareAndSet(false, true)) {
                return;
            }
            QuicChannel channel = quicChannel;
            if (channel == null) {
                return;
            }
            channel.createStream(QuicStreamType.UNIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            QuicStreamChannel created = (QuicStreamChannel) future.getNow();
                            doorUplink = created;
                            created.writeAndFlush(Unpooled.wrappedBuffer(framed));
                        }
                        // 开流失败:doorUplinkCreated 已置位,不再重开,不写 ready
                    });
        }

        @Override
        public boolean supportsDatagram() {
            return true;
        }

        @Override
        public boolean sendDatagram(byte[] payload) {
            QuicChannel channel = quicChannel;
            if (closed.get() || payload.length == 0 || channel == null) {
                return false;
            }
            // datagram 自带边界,裸 WireEnvelope 直发(无 varint 分帧、恒 plain);
            // 与可靠流发送不同:入队即视为成功,后续写失败就是包丢失
            // (尽力投递语义),不重试、不触发断连
            channel.writeAndFlush(Unpooled.wrappedBuffer(payload));
            return true;
        }

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
            closeDecoder();
            QuicChannel channel = quicChannel;
            if (channel != null) {
                channel.close(true, statusCode, Unpooled.EMPTY_BUFFER);
            }
        }

        private void notifyClosed(int statusCode, String reason) {
            if (closed.getAndSet(true)) {
                return;
            }
            closeDecoder();
            listener.onClosed(statusCode, reason);
        }

        private void fail(String message, Throwable error) {
            if (closed.getAndSet(true)) {
                return;
            }
            closeDecoder();
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
