package fun.prof_chen.teamviewer.main_code.network.transport;

import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportTrafficEvent;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.connection.Exchange;
import okhttp3.internal.connection.RealCall;
import okhttp3.internal.ws.RealWebSocket;
import okio.BufferedSink;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class OkHttpTransportProcess implements TransportProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(OkHttpTransportProcess.class);
    private static final long CLOSE_TIMEOUT_MS = 60_000L;
    private static final long DEFAULT_MINIMUM_DEFLATE_SIZE = 1_024L;

    /**
     * 压缩套 WS 子协议(与后端命名表一致)。客户端按偏好序提供候选,服务端
     * 择一并原样回执 101 的 Sec-WebSocket-Protocol(确认制,零额外 RTT);
     * 不回执即未协商,按既有回退链降级(deflate/明文)。WS 门无 datagram,
     * 故只提供 zstd/plain 两套、绝不提供 zstd-dict——服务端回执必须取自
     * 客户端列表(RFC 6455),字典压缩的帧永远不会到达 mod。
     */
    private static final String SUBPROTOCOL_ZSTD = "teamviewrelay.zstd.v1";
    private static final String SUBPROTOCOL_PLAIN = "teamviewrelay.plain.v1";
    private static final String OFFERED_SUBPROTOCOLS = SUBPROTOCOL_ZSTD + ", " + SUBPROTOCOL_PLAIN;

    @Override
    public SocketProcess connect(String uri, TransportOptions options, TransportListener listener) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.protocols(List.of(Protocol.HTTP_1_1));
        builder.eventListener(EventListener.NONE);
        if (options.useSystemProxy()) {
            builder.proxySelector(ProxySelector.getDefault());
        } else {
            builder.proxy(Proxy.NO_PROXY);
        }
        if (options.allowInsecureTls() && isSecureWebSocketUri(uri)) {
            UnsafeTlsComponents unsafeTls = createUnsafeTlsComponents();
            builder.sslSocketFactory(unsafeTls.socketFactory(), unsafeTls.trustManager());
            builder.hostnameVerifier((hostname, session) -> true);
        }

        InstrumentedWebSocketSession session = new InstrumentedWebSocketSession(
                builder.build(),
                uri,
                options.enableCompression(),
                listener
        );
        session.connect();
        return session;
    }

    private static boolean isSecureWebSocketUri(String uri) {
        return uri != null && uri.regionMatches(true, 0, "wss://", 0, 6);
    }

    static int parseServerMaxWindowBits(String value) throws ProtocolException {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            ProtocolException failure = new ProtocolException("Invalid server_max_window_bits: " + value);
            failure.initCause(error);
            throw failure;
        }
        if (parsed < 8 || parsed > 15) {
            throw new ProtocolException("Unsupported server_max_window_bits: " + value);
        }
        return parsed;
    }

    private static UnsafeTlsComponents createUnsafeTlsComponents() {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
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
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { trustManager }, new SecureRandom());
            return new UnsafeTlsComponents(sslContext.getSocketFactory(), trustManager);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize insecure TLS context", e);
        }
    }

    private record UnsafeTlsComponents(
            SSLSocketFactory socketFactory,
            X509TrustManager trustManager
    ) {
    }

    private static final class InstrumentedWebSocketSession implements SocketProcess, TrackingWebSocketReader.FrameCallback {
        private final OkHttpClient client;
        private final String uri;
        private final boolean compressionRequested;
        private final TransportListener listener;
        private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(namedFactory("tv-ws-writer"));
        private final ScheduledExecutorService closeScheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("tv-ws-close"));
        private final Random random = new Random();
        private final Object stateLock = new Object();

        private volatile RealCall call;
        private volatile boolean failed = false;
        private volatile boolean open = false;
        private volatile boolean closeRequested = false;
        private volatile boolean closeFrameSent = false;
        private volatile boolean closeFrameReceived = false;
        private volatile int receivedCloseCode = -1;
        private volatile String receivedCloseReason = "";
        private volatile TrackingWebSocketWriter writer;
        private volatile TrackingWebSocketReader reader;
        private volatile ZstdStreamDecoder zstdDecoder;
        private volatile RealWebSocket.Streams streams;
        private volatile WebSocketExtensionsInfo extensions = WebSocketExtensionsInfo.disabled();
        private final AtomicBoolean terminalNotified = new AtomicBoolean(false);

        private InstrumentedWebSocketSession(
                OkHttpClient client,
                String uri,
                boolean compressionRequested,
                TransportListener listener
        ) {
            this.client = client;
            this.uri = uri;
            this.compressionRequested = compressionRequested;
            this.listener = listener;
        }

        private void connect() {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(uri)
                    .header("Upgrade", "websocket")
                    .header("Connection", "Upgrade")
                    .header("Sec-WebSocket-Version", "13");
            String webSocketKey = WebSocketProtocolUtil.randomWebSocketKey(random);
            requestBuilder.header("Sec-WebSocket-Key", webSocketKey);
            if (compressionRequested) {
                // 压缩套与 permessage-deflate 同头竞争,回退链:zstd → deflate
                // →明文。新后端择 teamviewrelay 子协议并回执(不再回执扩展);
                // 旧后端忽略该头、照常回执 permessage-deflate,走原路径。
                requestBuilder.header("Sec-WebSocket-Extensions", "permessage-deflate");
                requestBuilder.header("Sec-WebSocket-Protocol", OFFERED_SUBPROTOCOLS);
            }
            Request request = requestBuilder.build();

            RealCall realCall = new RealCall(client, request, true);
            this.call = realCall;
            realCall.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    fail(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    Exchange exchange = response.exchange();
                    RealWebSocket.Streams openedStreams = null;
                    try {
                        checkUpgradeSuccess(response, exchange, webSocketKey);
                        openedStreams = exchange.newWebSocketStreams();
                        // 服务端确认制:回执值必须为空(未协商,旧后端)或取自
                        // 客户端列表(RFC 6455);zstd 与 permessage-deflate 互斥,
                        // 同回执属服务端违规。
                        String selectedSubprotocol = response.header("Sec-WebSocket-Protocol", "");
                        boolean zstdNegotiated = SUBPROTOCOL_ZSTD.equals(selectedSubprotocol);
                        if (!zstdNegotiated && !selectedSubprotocol.isEmpty()
                                && !SUBPROTOCOL_PLAIN.equals(selectedSubprotocol)) {
                            throw new ProtocolException(
                                    "Server selected unexpected WebSocket subprotocol: " + selectedSubprotocol);
                        }
                        WebSocketExtensionsInfo negotiated = WebSocketExtensionsInfo.parse(response, compressionRequested);
                        if (zstdNegotiated && negotiated.perMessageDeflate()) {
                            throw new ProtocolException(
                                    "Server negotiated permessage-deflate together with the zstd subprotocol");
                        }
                        if (zstdNegotiated) {
                            negotiated = WebSocketExtensionsInfo.disabled();
                        }
                        initStreams(openedStreams, negotiated, zstdNegotiated);
                        String negotiatedExtensions = response.header("Sec-WebSocket-Extensions", "");
                        listener.onOpen(negotiatedExtensions == null ? "" : negotiatedExtensions);
                        loopReader();
                    } catch (Exception e) {
                        closeQuietly(openedStreams);
                        if (exchange != null) {
                            try {
                                exchange.webSocketUpgradeFailed();
                            } catch (Exception ignored) {
                            }
                        }
                        fail(e);
                    }
                }
            });
        }

        private void initStreams(RealWebSocket.Streams openedStreams, WebSocketExtensionsInfo negotiated, boolean zstdNegotiated) {
            configureWebSocketStreamTimeouts(openedStreams);
            synchronized (stateLock) {
                this.streams = openedStreams;
                this.extensions = negotiated;
                if (zstdNegotiated) {
                    try {
                        this.zstdDecoder = new ZstdStreamDecoder(this::deliverZstdChunk);
                    } catch (IOException error) {
                        throw new IllegalStateException("zstd decoder init failed", error);
                    }
                }
                this.writer = new TrackingWebSocketWriter(
                        true,
                        openedStreams.getSink(),
                        random,
                        negotiated.perMessageDeflate(),
                        negotiated.clientNoContextTakeover(),
                        DEFAULT_MINIMUM_DEFLATE_SIZE,
                        event -> listener.onTrafficEvent(event)
                );
                this.reader = new TrackingWebSocketReader(
                        true,
                        openedStreams.getSource(),
                        this,
                        negotiated.perMessageDeflate(),
                        negotiated.serverNoContextTakeover(),
                        event -> listener.onTrafficEvent(event)
                );
                this.open = true;
            }
        }

        private void loopReader() {
            TrackingWebSocketReader currentReader = this.reader;
            if (currentReader == null) {
                return;
            }
            try {
                while (!closeFrameReceived && !failed) {
                    currentReader.processNextFrame();
                }
            } catch (Exception e) {
                fail(e);
            }
        }

        @Override
        public void send(byte[] payload) {
            if (payload == null) {
                return;
            }
            enqueueWriterTask(() -> {
                TrackingWebSocketWriter currentWriter = writer;
                if (currentWriter == null || failed || closeRequested) {
                    return;
                }
                currentWriter.writeMessageFrame(TrackingWebSocketWriter.OPCODE_BINARY, payload);
            });
        }

        @Override
        public void close(int statusCode, String reason) {
            synchronized (stateLock) {
                if (closeRequested || failed) {
                    return;
                }
                closeRequested = true;
            }

            if (!open) {
                RealCall realCall = call;
                if (realCall != null) {
                    realCall.cancel();
                }
                shutdownExecutors();
                return;
            }

            enqueueWriterTask(() -> {
                TrackingWebSocketWriter currentWriter = writer;
                if (currentWriter == null || failed) {
                    return;
                }
                currentWriter.writeClose(statusCode, reason);
                closeFrameSent = true;
                scheduleForcedClose();
                if (closeFrameReceived) {
                    finishClose(receivedCloseCode, receivedCloseReason);
                }
            });
        }

        private void scheduleForcedClose() {
            closeScheduler.schedule(() -> {
                if (terminalNotified.get()) {
                    return;
                }
                RealCall realCall = call;
                if (realCall != null) {
                    realCall.cancel();
                }
                finishClose(receivedCloseCode == -1 ? 1000 : receivedCloseCode, receivedCloseReason);
            }, CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private void enqueueWriterTask(WriterAction action) {
            try {
                writerExecutor.execute(() -> {
                    try {
                        action.run();
                    } catch (IOException e) {
                        fail(e);
                    } catch (Exception e) {
                        fail(new IOException("WebSocket writer failure", e));
                    }
                });
            } catch (Exception e) {
                fail(new IOException("Failed to schedule WebSocket writer task", e));
            }
        }

        @Override
        public void onReadMessage(String text) {
            listener.onTextMessage(text);
        }

        @Override
        public void onReadMessage(byte[] bytes) {
            ZstdStreamDecoder currentDecoder = zstdDecoder;
            if (currentDecoder != null) {
                // zstd 套下行:每条 binary 消息 = 连续 zstd 流的一个压缩块,
                // 解压产出即 envelope 本体。解压失败属协议违规:显式断连,
                // 不得静默吞帧。上行恒 plain(压缩套单向语义),writer 未改。
                try {
                    currentDecoder.push(bytes);
                } catch (IOException error) {
                    fail(error);
                }
                return;
            }
            listener.onBinaryMessage(bytes);
        }

        private void deliverZstdChunk(byte[] envelope) {
            listener.onBinaryMessage(envelope);
        }

        @Override
        public void onReadPing(byte[] payload) {
            if (failed) {
                return;
            }
            enqueueWriterTask(() -> {
                TrackingWebSocketWriter currentWriter = writer;
                if (currentWriter == null || failed) {
                    return;
                }
                currentWriter.writePong(payload);
            });
        }

        @Override
        public void onReadClose(int code, String reason) {
            receivedCloseCode = code;
            receivedCloseReason = reason == null ? "" : reason;
            closeFrameReceived = true;

            if (closeRequested && closeFrameSent) {
                finishClose(code, receivedCloseReason);
                return;
            }

            if (!closeRequested) {
                closeRequested = true;
                enqueueWriterTask(() -> {
                    TrackingWebSocketWriter currentWriter = writer;
                    if (currentWriter == null || failed) {
                        return;
                    }
                    if (!closeFrameSent) {
                        currentWriter.writeClose(code, reason);
                        closeFrameSent = true;
                    }
                    finishClose(code, receivedCloseReason);
                });
            }
        }

        private void checkUpgradeSuccess(Response response, Exchange exchange, String webSocketKey) throws IOException {
            if (response.code() != 101) {
                throw new ProtocolException("Expected HTTP 101 response but was '" + response.code() + " " + response.message() + "'");
            }

            String headerConnection = response.header("Connection");
            if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
                throw new ProtocolException("Expected 'Connection' header value 'Upgrade' but was '" + headerConnection + "'");
            }

            String headerUpgrade = response.header("Upgrade");
            if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
                throw new ProtocolException("Expected 'Upgrade' header value 'websocket' but was '" + headerUpgrade + "'");
            }

            String headerAccept = response.header("Sec-WebSocket-Accept");
            String acceptExpected = WebSocketProtocolUtil.acceptHeader(webSocketKey);
            if (!acceptExpected.equals(headerAccept)) {
                throw new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '" + acceptExpected + "' but was '" + headerAccept + "'");
            }

            if (exchange == null) {
                throw new ProtocolException("Web Socket exchange missing: bad interceptor?");
            }
        }

        private void configureWebSocketStreamTimeouts(RealWebSocket.Streams openedStreams) {
            try {
                openedStreams.getSource().timeout().clearTimeout().clearDeadline();
                openedStreams.getSink().timeout().clearTimeout().clearDeadline();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to disable inherited WebSocket stream timeouts", e);
            }
        }

        private void fail(Throwable error) {
            if (isExpectedPostCloseFailure(error)) {
                LOGGER.debug("Ignoring post-close transport exception for {}: {}", uri, error == null ? "unknown" : error.toString());
                finishClose(receivedCloseCode == -1 ? 1000 : receivedCloseCode, receivedCloseReason);
                return;
            }
            Throwable normalizedError = normalizeFailure(error);
            if (!terminalNotified.compareAndSet(false, true)) {
                return;
            }
            failed = true;
            shutdownResources();
            listener.onFailure(normalizedError instanceof Exception ? (Exception) normalizedError : new IOException(normalizedError));
        }

        private void finishClose(int code, String reason) {
            if (!terminalNotified.compareAndSet(false, true)) {
                return;
            }
            shutdownResources();
            listener.onClosed(code, reason == null ? "" : reason);
        }

        private void shutdownResources() {
            closeQuietly(reader);
            closeQuietly(writer);
            closeQuietly(zstdDecoder);
            closeQuietly(streams);
            shutdownExecutors();
        }

        private void shutdownExecutors() {
            writerExecutor.shutdownNow();
            closeScheduler.shutdownNow();
        }

        private static void closeQuietly(Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }

        private Throwable normalizeFailure(Throwable error) {
            if (error instanceof SocketTimeoutException socketTimeoutException) {
                return new IOException(
                        "websocket_read_timeout_after_upgrade: upgraded WebSocket stream unexpectedly timed out",
                        socketTimeoutException
                );
            }
            return error;
        }

        private boolean isExpectedPostCloseFailure(Throwable error) {
            return closeRequested && containsClosedSignal(error);
        }

        private boolean containsClosedSignal(Throwable error) {
            Throwable current = error;
            int depth = 0;
            while (current != null && depth < 6) {
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.trim().toLowerCase();
                    if ("closed".equals(normalized) || "socket closed".equals(normalized)) {
                        return true;
                    }
                }
                current = current.getCause();
                depth++;
            }
            return false;
        }
    }

    @FunctionalInterface
    private interface WriterAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface TrafficEventConsumer {
        void accept(TransportTrafficEvent event);
    }

    private static final class TrackingWebSocketWriter implements Closeable {
        private static final int OPCODE_TEXT = 0x1;
        private static final int OPCODE_BINARY = 0x2;
        private static final int OPCODE_CLOSE = 0x8;
        private static final int OPCODE_PING = 0x9;
        private static final int OPCODE_PONG = 0xA;
        private static final byte[] EMPTY_DEFLATE_BLOCK = new byte[] {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

        private final boolean client;
        private final BufferedSink sink;
        private final Random random;
        private final boolean perMessageDeflate;
        private final boolean noContextTakeover;
        private final long minimumDeflateSize;
        private final TrafficEventConsumer trafficConsumer;
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        private boolean writerClosed = false;

        private TrackingWebSocketWriter(
                boolean client,
                BufferedSink sink,
                Random random,
                boolean perMessageDeflate,
                boolean noContextTakeover,
                long minimumDeflateSize,
                TrafficEventConsumer trafficConsumer
        ) {
            this.client = client;
            this.sink = sink;
            this.random = random;
            this.perMessageDeflate = perMessageDeflate;
            this.noContextTakeover = noContextTakeover;
            this.minimumDeflateSize = minimumDeflateSize;
            this.trafficConsumer = trafficConsumer;
        }

        private void writePing(byte[] payload) throws IOException {
            writeControlFrame(OPCODE_PING, payload, TransportTrafficEvent.FrameKind.PING);
        }

        private void writePong(byte[] payload) throws IOException {
            writeControlFrame(OPCODE_PONG, payload, TransportTrafficEvent.FrameKind.PONG);
        }

        private void writeClose(int code, String reason) throws IOException {
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            if (code == 0 && reasonBytes.length > 0) {
                throw new IllegalArgumentException("WebSocket close reason requires a close code.");
            }
            if (code != 0) {
                WebSocketProtocolUtil.validateCloseCode(code);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(2 + reasonBytes.length);
            if (code != 0) {
                output.write((code >>> 8) & 0xFF);
                output.write(code & 0xFF);
            }
            output.write(reasonBytes);
            try {
                writeControlFrame(OPCODE_CLOSE, output.toByteArray(), TransportTrafficEvent.FrameKind.CLOSE);
            } finally {
                writerClosed = true;
            }
        }

        private void writeControlFrame(int opcode, byte[] payload, TransportTrafficEvent.FrameKind kind) throws IOException {
            byte[] safePayload = payload == null ? new byte[0] : payload;
            if (writerClosed) {
                throw new IOException("closed");
            }
            if (safePayload.length > WebSocketProtocolUtil.CONTROL_FRAME_MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Payload size must be less than or equal to " + WebSocketProtocolUtil.CONTROL_FRAME_MAX_PAYLOAD_BYTES
                );
            }
            writeFrame(opcode, safePayload, false, kind, safePayload.length);
        }

        private void writeMessageFrame(int opcode, byte[] payload) throws IOException {
            byte[] safePayload = payload == null ? new byte[0] : payload;
            if (writerClosed) {
                throw new IOException("closed");
            }
            byte[] wirePayload = safePayload;
            boolean compressed = false;
            if (perMessageDeflate && safePayload.length >= minimumDeflateSize) {
                wirePayload = deflate(safePayload);
                compressed = true;
            }
            writeFrame(
                    opcode,
                    wirePayload,
                    compressed,
                    opcode == OPCODE_TEXT ? TransportTrafficEvent.FrameKind.TEXT : TransportTrafficEvent.FrameKind.BINARY,
                    safePayload.length
            );
        }

        private void writeFrame(
                int opcode,
                byte[] wirePayload,
                boolean compressed,
                TransportTrafficEvent.FrameKind kind,
                long applicationPayloadBytes
        ) throws IOException {
            int payloadLength = wirePayload.length;
            int headerSize = WebSocketProtocolUtil.headerSize(payloadLength, client);
            int firstByte = 0x80 | opcode;
            if (compressed) {
                firstByte |= 0x40;
            }
            sink.writeByte(firstByte);

            int secondByte = client ? 0x80 : 0x00;
            if (payloadLength <= 125) {
                sink.writeByte(secondByte | payloadLength);
            } else if (payloadLength <= 0xFFFF) {
                sink.writeByte(secondByte | 126);
                sink.writeShort(payloadLength);
            } else {
                sink.writeByte(secondByte | 127);
                sink.writeLong(payloadLength);
            }

            byte[] payloadToWrite = wirePayload;
            if (client) {
                byte[] maskKey = new byte[4];
                random.nextBytes(maskKey);
                sink.write(maskKey);
                payloadToWrite = Arrays.copyOf(wirePayload, wirePayload.length);
                WebSocketProtocolUtil.toggleMask(payloadToWrite, maskKey);
            }

            if (payloadToWrite.length > 0) {
                sink.write(payloadToWrite);
            }
            sink.emit();

            trafficConsumer.accept(new TransportTrafficEvent(
                    TransportTrafficEvent.Direction.OUTBOUND,
                    kind,
                    applicationPayloadBytes,
                    headerSize + payloadLength
            ));
        }

        private byte[] deflate(byte[] input) throws IOException {
            if (noContextTakeover) {
                deflater.reset();
            }
            deflater.setInput(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
            byte[] buffer = new byte[Math.max(256, input.length + 64)];
            while (true) {
                int count = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
                if (count <= 0) {
                    break;
                }
                output.write(buffer, 0, count);
                if (count < buffer.length && deflater.needsInput()) {
                    break;
                }
            }
            byte[] deflated = output.toByteArray();
            if (endsWith(deflated, EMPTY_DEFLATE_BLOCK)) {
                return Arrays.copyOf(deflated, deflated.length - 4);
            }
            byte[] fallback = Arrays.copyOf(deflated, deflated.length + 1);
            fallback[fallback.length - 1] = 0x00;
            return fallback;
        }

        private boolean endsWith(byte[] payload, byte[] suffix) {
            if (payload.length < suffix.length) {
                return false;
            }
            for (int i = 0; i < suffix.length; i++) {
                if (payload[payload.length - suffix.length + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            deflater.end();
        }
    }

    private static final class TrackingWebSocketReader implements Closeable {
        private static final int OPCODE_CONTINUATION = 0x0;
        private static final int OPCODE_TEXT = 0x1;
        private static final int OPCODE_BINARY = 0x2;
        private static final int OPCODE_CLOSE = 0x8;
        private static final int OPCODE_PING = 0x9;
        private static final int OPCODE_PONG = 0xA;
        private static final byte[] INFLATER_TRAILER = new byte[] {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

        private final boolean client;
        private final BufferedSource source;
        private final FrameCallback frameCallback;
        private final boolean perMessageDeflate;
        private final boolean noContextTakeover;
        private final TrafficEventConsumer trafficConsumer;
        private final Inflater inflater = new Inflater(true);

        private boolean closed = false;

        private TrackingWebSocketReader(
                boolean client,
                BufferedSource source,
                FrameCallback frameCallback,
                boolean perMessageDeflate,
                boolean noContextTakeover,
                TrafficEventConsumer trafficConsumer
        ) {
            this.client = client;
            this.source = source;
            this.frameCallback = frameCallback;
            this.perMessageDeflate = perMessageDeflate;
            this.noContextTakeover = noContextTakeover;
            this.trafficConsumer = trafficConsumer;
        }

        private void processNextFrame() throws IOException {
            FrameHeader header = readHeader();
            if (header.controlFrame()) {
                readControlFrame(header);
                return;
            }
            readMessageFrame(header);
        }

        private FrameHeader readHeader() throws IOException {
            if (closed) {
                throw new IOException("closed");
            }

            int b0 = source.readByte() & 0xFF;
            int opcode = b0 & 0x0F;
            boolean finalFrame = (b0 & 0x80) != 0;
            boolean controlFrame = (opcode & 0x08) != 0;
            boolean reserved1 = (b0 & 0x40) != 0;
            boolean reserved2 = (b0 & 0x20) != 0;
            boolean reserved3 = (b0 & 0x10) != 0;

            if (controlFrame && !finalFrame) {
                throw new ProtocolException("Control frames must be final.");
            }
            if (reserved2 || reserved3) {
                throw new ProtocolException("Unexpected reserved flags.");
            }

            int b1 = source.readByte() & 0xFF;
            boolean masked = (b1 & 0x80) != 0;
            if (masked == client) {
                throw new ProtocolException(client
                        ? "Server-sent frames must not be masked."
                        : "Client-sent frames must be masked.");
            }

            long frameLength = b1 & 0x7FL;
            int headerSize = 2;
            if (frameLength == 126L) {
                frameLength = source.readShort() & 0xFFFFL;
                headerSize += 2;
            } else if (frameLength == 127L) {
                frameLength = source.readLong();
                if (frameLength < 0L) {
                    throw new ProtocolException("Negative frame length.");
                }
                headerSize += 8;
            }
            if (controlFrame && frameLength > WebSocketProtocolUtil.CONTROL_FRAME_MAX_PAYLOAD_BYTES) {
                throw new ProtocolException(
                        "Control frame must be less than " + WebSocketProtocolUtil.CONTROL_FRAME_MAX_PAYLOAD_BYTES + "B."
                );
            }

            byte[] maskKey = null;
            if (masked) {
                maskKey = source.readByteArray(4);
                headerSize += 4;
            }

            if ((opcode == OPCODE_TEXT || opcode == OPCODE_BINARY) && reserved1 && !perMessageDeflate) {
                throw new ProtocolException("Unexpected rsv1 flag");
            }
            if (!(opcode == OPCODE_TEXT || opcode == OPCODE_BINARY) && reserved1) {
                throw new ProtocolException("Unexpected rsv1 flag");
            }

            return new FrameHeader(opcode, finalFrame, controlFrame, reserved1, masked, frameLength, headerSize, maskKey);
        }

        private void readControlFrame(FrameHeader header) throws IOException {
            byte[] payload = readPayload(header);
            trafficConsumer.accept(new TransportTrafficEvent(
                    TransportTrafficEvent.Direction.INBOUND,
                    mapFrameKind(header.opcode()),
                    payload.length,
                    header.headerSize() + header.frameLength()
            ));

            switch (header.opcode()) {
                case OPCODE_PING -> frameCallback.onReadPing(payload);
                case OPCODE_PONG -> {
                    // Pong payloads have no application callback; traffic was recorded above.
                }
                case OPCODE_CLOSE -> {
                    int code = 1005;
                    String reason = "";
                    if (payload.length == 1) {
                        throw new ProtocolException("Malformed close payload length of 1.");
                    } else if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        String codeExceptionMessage = WebSocketProtocolUtil.closeCodeExceptionMessage(code);
                        if (codeExceptionMessage != null) {
                            throw new ProtocolException(codeExceptionMessage);
                        }
                        if (payload.length > 2) {
                            reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                        }
                    }
                    closed = true;
                    frameCallback.onReadClose(code, reason);
                }
                default -> throw new ProtocolException("Unknown control opcode: " + header.opcode());
            }
        }

        private void readMessageFrame(FrameHeader firstHeader) throws IOException {
            if (firstHeader.opcode() != OPCODE_TEXT && firstHeader.opcode() != OPCODE_BINARY) {
                throw new ProtocolException("Unknown opcode: " + firstHeader.opcode());
            }

            ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
            long wireBytes = 0L;
            FrameHeader currentHeader = firstHeader;
            boolean compressed = firstHeader.reserved1();
            while (true) {
                wireBytes += currentHeader.headerSize() + currentHeader.frameLength();
                messageBytes.write(readPayload(currentHeader));
                if (currentHeader.finalFrame()) {
                    break;
                }
                currentHeader = readUntilNextDataFrame();
            }

            byte[] payload = messageBytes.toByteArray();
            if (compressed) {
                payload = inflate(payload);
            }

            trafficConsumer.accept(new TransportTrafficEvent(
                    TransportTrafficEvent.Direction.INBOUND,
                    mapFrameKind(firstHeader.opcode()),
                    payload.length,
                    wireBytes
            ));

            if (firstHeader.opcode() == OPCODE_TEXT) {
                frameCallback.onReadMessage(new String(payload, StandardCharsets.UTF_8));
            } else {
                frameCallback.onReadMessage(payload);
            }
        }

        private FrameHeader readUntilNextDataFrame() throws IOException {
            while (true) {
                FrameHeader header = readHeader();
                if (header.controlFrame()) {
                    readControlFrame(header);
                    continue;
                }
                if (header.opcode() != OPCODE_CONTINUATION) {
                    throw new ProtocolException("Expected continuation opcode. Got: " + header.opcode());
                }
                return header;
            }
        }

        private byte[] readPayload(FrameHeader header) throws IOException {
            if (header.frameLength() == 0L) {
                return new byte[0];
            }
            byte[] payload = source.readByteArray(header.frameLength());
            if (header.masked()) {
                WebSocketProtocolUtil.toggleMask(payload, header.maskKey());
            }
            return payload;
        }

        private byte[] inflate(byte[] payload) throws IOException {
            if (noContextTakeover) {
                inflater.reset();
            }
            byte[] input = Arrays.copyOf(payload, payload.length + INFLATER_TRAILER.length);
            System.arraycopy(INFLATER_TRAILER, 0, input, payload.length, INFLATER_TRAILER.length);
            inflater.setInput(input);
            long totalBytesToRead = inflater.getBytesRead() + input.length;
            ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length * 2 + 16);
            byte[] buffer = new byte[Math.max(256, payload.length * 2 + 16)];
            try {
                while (inflater.getBytesRead() < totalBytesToRead) {
                    int count = inflater.inflate(buffer);
                    if (count > 0) {
                        output.write(buffer, 0, count);
                        continue;
                    }
                    if (inflater.needsDictionary()) {
                        throw new IOException("Unexpected compressed WebSocket frame dictionary requirement");
                    }
                    if (inflater.needsInput()) {
                        break;
                    }
                }
            } catch (DataFormatException e) {
                throw new IOException("Failed to inflate compressed WebSocket message", e);
            }
            return output.toByteArray();
        }

        @Override
        public void close() {
            inflater.end();
        }

        private TransportTrafficEvent.FrameKind mapFrameKind(int opcode) {
            return switch (opcode) {
                case OPCODE_TEXT -> TransportTrafficEvent.FrameKind.TEXT;
                case OPCODE_BINARY -> TransportTrafficEvent.FrameKind.BINARY;
                case OPCODE_PING -> TransportTrafficEvent.FrameKind.PING;
                case OPCODE_PONG -> TransportTrafficEvent.FrameKind.PONG;
                case OPCODE_CLOSE -> TransportTrafficEvent.FrameKind.CLOSE;
                default -> TransportTrafficEvent.FrameKind.BINARY;
            };
        }

        private record FrameHeader(
                int opcode,
                boolean finalFrame,
                boolean controlFrame,
                boolean reserved1,
                boolean masked,
                long frameLength,
                int headerSize,
                byte[] maskKey
        ) {
        }

        private interface FrameCallback {
            void onReadMessage(String text) throws IOException;

            void onReadMessage(byte[] bytes) throws IOException;

            void onReadPing(byte[] payload);

            void onReadClose(int code, String reason);
        }
    }

    private static final class WebSocketExtensionsInfo {
        private final boolean perMessageDeflate;
        private final boolean clientNoContextTakeover;
        private final boolean serverNoContextTakeover;

        private WebSocketExtensionsInfo(
                boolean perMessageDeflate,
                boolean clientNoContextTakeover,
                boolean serverNoContextTakeover
        ) {
            this.perMessageDeflate = perMessageDeflate;
            this.clientNoContextTakeover = clientNoContextTakeover;
            this.serverNoContextTakeover = serverNoContextTakeover;
        }

        private boolean perMessageDeflate() {
            return perMessageDeflate;
        }

        private boolean clientNoContextTakeover() {
            return clientNoContextTakeover;
        }

        private boolean serverNoContextTakeover() {
            return serverNoContextTakeover;
        }

        private static WebSocketExtensionsInfo disabled() {
            return new WebSocketExtensionsInfo(false, false, false);
        }

        private static WebSocketExtensionsInfo parse(Response response, boolean compressionRequested) throws IOException {
            String header = response.header("Sec-WebSocket-Extensions", "");
            if (header == null || header.isBlank()) {
                return disabled();
            }
            if (!compressionRequested) {
                throw new ProtocolException("Server returned unexpected Sec-WebSocket-Extensions: " + header);
            }

            boolean compressionEnabled = false;
            boolean clientNoContextTakeover = false;
            boolean serverNoContextTakeover = false;
            String[] extensions = header.split(",");
            for (String extensionPart : extensions) {
                String[] tokens = extensionPart.trim().split(";");
                if (tokens.length == 0) {
                    continue;
                }
                String extensionName = tokens[0].trim();
                if (!"permessage-deflate".equalsIgnoreCase(extensionName)) {
                    throw new ProtocolException("Unexpected WebSocket extension: " + extensionName);
                }
                if (compressionEnabled) {
                    throw new ProtocolException("Repeated permessage-deflate extension");
                }
                compressionEnabled = true;
                for (int i = 1; i < tokens.length; i++) {
                    String parameter = tokens[i].trim();
                    if (parameter.isEmpty()) {
                        continue;
                    }
                    if ("client_no_context_takeover".equalsIgnoreCase(parameter)) {
                        clientNoContextTakeover = true;
                        continue;
                    }
                    if ("server_no_context_takeover".equalsIgnoreCase(parameter)) {
                        serverNoContextTakeover = true;
                        continue;
                    }
                    if (parameter.startsWith("client_max_window_bits")) {
                        String value = extractParameterValue(parameter);
                        if (value != null && !"15".equals(value)) {
                            throw new ProtocolException("Unsupported client_max_window_bits: " + value);
                        }
                        continue;
                    }
                    if (parameter.startsWith("server_max_window_bits")) {
                        String value = extractParameterValue(parameter);
                        if (value != null) {
                            parseServerMaxWindowBits(value);
                        }
                        continue;
                    }
                    throw new ProtocolException("Unexpected WebSocket extension parameter: " + parameter);
                }
            }
            return new WebSocketExtensionsInfo(compressionEnabled, clientNoContextTakeover, serverNoContextTakeover);
        }

        private static String extractParameterValue(String parameter) {
            int equalsIndex = parameter.indexOf('=');
            if (equalsIndex < 0 || equalsIndex == parameter.length() - 1) {
                return null;
            }
            String value = parameter.substring(equalsIndex + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
    }

    private static final class WebSocketProtocolUtil {
        private static final String ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private static final long CONTROL_FRAME_MAX_PAYLOAD_BYTES = 125L;

        private static String randomWebSocketKey(Random random) {
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            return java.util.Base64.getEncoder().encodeToString(nonce);
        }

        private static String acceptHeader(String key) {
            okio.ByteString value = okio.ByteString.Companion.encodeUtf8(key + ACCEPT_MAGIC);
            return value.sha1().base64();
        }

        private static int headerSize(long payloadLength, boolean client) {
            int headerSize = 2;
            if (payloadLength >= 126 && payloadLength <= 0xFFFFL) {
                headerSize += 2;
            } else if (payloadLength > 0xFFFFL) {
                headerSize += 8;
            }
            if (client) {
                headerSize += 4;
            }
            return headerSize;
        }

        private static void toggleMask(byte[] payload, byte[] maskKey) {
            if (payload == null || maskKey == null || maskKey.length == 0) {
                return;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % maskKey.length]);
            }
        }

        private static String closeCodeExceptionMessage(int code) {
            if (code < 1000 || code >= 5000) {
                return "Code must be in range [1000,5000): " + code;
            }
            if ((code >= 1004 && code <= 1006) || (code >= 1015 && code <= 2999)) {
                return "Code " + code + " is reserved and may not be used.";
            }
            return null;
        }

        private static void validateCloseCode(int code) {
            String message = closeCodeExceptionMessage(code);
            if (message != null) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static ThreadFactory namedFactory(String baseName) {
        return runnable -> {
            Thread thread = new Thread(runnable, baseName);
            thread.setDaemon(true);
            return thread;
        };
    }
}
