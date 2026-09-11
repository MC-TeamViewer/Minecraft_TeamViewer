package fun.prof_chen.teamviewer.main_code.network.capture;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class WebSocketCaptureWriter implements Closeable {
    private static final int LINKTYPE_ETHERNET = 1;
    private static final int ETHERTYPE_IPV4 = 0x0800;
    private static final int IP_PROTOCOL_TCP = 6;
    private static final int IP_PROTOCOL_UDP = 17;
    private static final int CLIENT_PORT = 39051;
    private static final int DEFAULT_CLIENT_IP = ipv4(127, 0, 0, 1);
    private static final int DEFAULT_SERVER_IP = ipv4(127, 0, 0, 1);
    private static final long DEFAULT_CLIENT_SEQ = 1_000L;
    private static final long DEFAULT_SERVER_SEQ = 5_000L;
    private static final int TCP_FLAG_FIN = 0x01;
    private static final int TCP_FLAG_SYN = 0x02;
    private static final int TCP_FLAG_PSH = 0x08;
    private static final int TCP_FLAG_ACK = 0x10;
    private static final String PCAPNG_EXTENSION = ".pcapng";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String SYNTHETIC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final Pattern SAFE_FILE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]+");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path outputPath;
    private final OutputStream output;
    private final int serverIp;
    private final int serverPort;
    private final String hostHeader;
    private final String requestPath;
    private final String negotiatedExtensions;

    private long clientSeq = DEFAULT_CLIENT_SEQ;
    private long serverSeq = DEFAULT_SERVER_SEQ;
    private boolean closed = false;
    private boolean tcpOpened = false;
    private boolean tcpClosed = false;

    private WebSocketCaptureWriter(
            Path outputPath,
            OutputStream output,
            int serverIp,
            int serverPort,
            String hostHeader,
            String requestPath,
            String negotiatedExtensions
    ) throws IOException {
        this.outputPath = outputPath;
        this.output = output;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.hostHeader = hostHeader;
        this.requestPath = requestPath;
        this.negotiatedExtensions = negotiatedExtensions == null ? "" : negotiatedExtensions.trim();
        writeSectionHeaderBlock();
        writeInterfaceDescriptionBlock();
        openSyntheticTcpSession();
    }

    public static WebSocketCaptureWriter open(
            Path logsDirectory,
            String serverUrl,
            String roomCode,
            String negotiatedExtensions
    ) throws IOException {
        Objects.requireNonNull(logsDirectory, "logsDirectory");
        Files.createDirectories(logsDirectory.resolve("teamviewer-network-dumps"));
        ConnectionInfo connectionInfo = ConnectionInfo.from(serverUrl);
        Path outputPath = logsDirectory
                .resolve("teamviewer-network-dumps")
                .resolve(buildFileName(roomCode));
        OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputPath));
        return new WebSocketCaptureWriter(
                outputPath,
                output,
                connectionInfo.serverIp(),
                connectionInfo.serverPort(),
                connectionInfo.hostHeader(),
                connectionInfo.requestPath(),
                negotiatedExtensions
        );
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public synchronized void writeClientBinaryMessage(byte[] payload) throws IOException {
        writeWebSocketMessage(true, false, payload);
    }

    public synchronized void writeServerBinaryMessage(byte[] payload) throws IOException {
        writeWebSocketMessage(false, false, payload);
    }

    public synchronized void writeClientTextMessage(String text) throws IOException {
        writeWebSocketMessage(true, true, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void writeServerTextMessage(String text) throws IOException {
        writeWebSocketMessage(false, true, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /** datagram(QUIC 门上下行)以独立 UDP 包呈现,载荷是应用层明文 envelope
     * (上行恒 plain、下行已在传输层解压),与同文件的 WS 帧共用 4 元组;
     * 无需序号跟踪,校验和按 UDP 伪头计算。 */
    public synchronized void writeClientDatagramMessage(byte[] payload) throws IOException {
        writeDatagram(true, payload);
    }

    public synchronized void writeServerDatagramMessage(byte[] payload) throws IOException {
        writeDatagram(false, payload);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (tcpOpened && !tcpClosed) {
            writeClientToServerPacket(TCP_FLAG_FIN | TCP_FLAG_ACK, new byte[0]);
            clientSeq++;
            writeServerToClientPacket(TCP_FLAG_ACK, new byte[0]);
            tcpClosed = true;
        }
        output.flush();
        output.close();
    }

    private static String buildFileName(String roomCode) {
        String timestamp = LocalDateTime.now().format(FILE_TS);
        String normalizedRoomCode = normalizeRoomCode(roomCode);
        return "teamviewer-ws-session-" + timestamp + "-" + normalizedRoomCode + PCAPNG_EXTENSION;
    }

    private static String normalizeRoomCode(String roomCode) {
        String normalized = roomCode == null ? "" : roomCode.trim();
        if (normalized.isEmpty()) {
            normalized = "default";
        }
        normalized = SAFE_FILE_CHARS.matcher(normalized).replaceAll("_");
        return normalized.isEmpty() ? "default" : normalized;
    }

    private void openSyntheticTcpSession() throws IOException {
        if (tcpOpened) {
            return;
        }
        writeClientToServerPacket(TCP_FLAG_SYN, new byte[0]);
        clientSeq++;
        writeServerToClientPacket(TCP_FLAG_SYN | TCP_FLAG_ACK, new byte[0]);
        serverSeq++;
        writeClientToServerPacket(TCP_FLAG_ACK, new byte[0]);
        writeClientToServerPacket(TCP_FLAG_PSH | TCP_FLAG_ACK, buildHandshakeRequest());
        clientSeq += buildHandshakeRequest().length;
        writeServerToClientPacket(TCP_FLAG_PSH | TCP_FLAG_ACK, buildHandshakeResponse());
        serverSeq += buildHandshakeResponse().length;
        tcpOpened = true;
    }

    private void writeWebSocketMessage(boolean clientToServer, boolean textMessage, byte[] payload) throws IOException {
        if (closed) {
            return;
        }
        byte[] frame = buildWebSocketFrame(clientToServer, textMessage, payload == null ? new byte[0] : payload);
        if (clientToServer) {
            writeClientToServerPacket(TCP_FLAG_PSH | TCP_FLAG_ACK, frame);
            clientSeq += frame.length;
            return;
        }
        writeServerToClientPacket(TCP_FLAG_PSH | TCP_FLAG_ACK, frame);
        serverSeq += frame.length;
    }

    private void writeDatagram(boolean clientToServer, byte[] payload) throws IOException {
        if (closed) {
            return;
        }
        byte[] data = payload == null ? new byte[0] : payload;
        if (clientToServer) {
            writeEnhancedPacketBlock(buildEthernetIpv4UdpPacket(
                    DEFAULT_CLIENT_IP, serverIp, CLIENT_PORT, serverPort, data));
            return;
        }
        writeEnhancedPacketBlock(buildEthernetIpv4UdpPacket(
                serverIp, DEFAULT_CLIENT_IP, serverPort, CLIENT_PORT, data));
    }

    private byte[] buildHandshakeRequest() {
        String request = "GET " + requestPath + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + SYNTHETIC_KEY + "\r\n"
                + "User-Agent: Minecraft-TeamViewer-Dump/1.0\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] buildHandshakeResponse() {
        String accept = buildWebSocketAccept(SYNTHETIC_KEY);
        StringBuilder response = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n");
        if (!negotiatedExtensions.isBlank()) {
            response.append("Sec-WebSocket-Extensions: ").append(negotiatedExtensions).append("\r\n");
        }
        response.append("\r\n");
        return response.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String buildWebSocketAccept(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    private static byte[] buildWebSocketFrame(boolean masked, boolean textMessage, byte[] payload) {
        int opcode = textMessage ? 0x1 : 0x2;
        int headerSize = 2;
        long payloadLength = payload.length;
        if (payloadLength >= 126 && payloadLength <= 0xFFFF) {
            headerSize += 2;
        } else if (payloadLength > 0xFFFF) {
            headerSize += 8;
        }
        int maskSize = masked ? 4 : 0;
        ByteBuffer buffer = ByteBuffer.allocate(headerSize + maskSize + payload.length);
        buffer.put((byte) (0x80 | opcode));
        if (payloadLength < 126) {
            buffer.put((byte) ((masked ? 0x80 : 0) | (int) payloadLength));
        } else if (payloadLength <= 0xFFFF) {
            buffer.put((byte) ((masked ? 0x80 : 0) | 126));
            buffer.putShort((short) payloadLength);
        } else {
            buffer.put((byte) ((masked ? 0x80 : 0) | 127));
            buffer.putLong(payloadLength);
        }

        if (masked) {
            byte[] mask = new byte[4];
            ThreadLocalRandom.current().nextBytes(mask);
            buffer.put(mask);
            for (int i = 0; i < payload.length; i++) {
                buffer.put((byte) (payload[i] ^ mask[i % 4]));
            }
        } else {
            buffer.put(payload);
        }
        return buffer.array();
    }

    private void writeClientToServerPacket(int tcpFlags, byte[] payload) throws IOException {
        writeEnhancedPacketBlock(buildEthernetIpv4TcpPacket(
                DEFAULT_CLIENT_IP,
                serverIp,
                CLIENT_PORT,
                serverPort,
                clientSeq,
                serverSeq,
                tcpFlags,
                payload
        ));
    }

    private void writeServerToClientPacket(int tcpFlags, byte[] payload) throws IOException {
        writeEnhancedPacketBlock(buildEthernetIpv4TcpPacket(
                serverIp,
                DEFAULT_CLIENT_IP,
                serverPort,
                CLIENT_PORT,
                serverSeq,
                clientSeq,
                tcpFlags,
                payload
        ));
    }

    private static byte[] buildEthernetIpv4TcpPacket(
            int sourceIp,
            int destinationIp,
            int sourcePort,
            int destinationPort,
            long sequenceNumber,
            long acknowledgementNumber,
            int tcpFlags,
            byte[] payload
    ) {
        byte[] applicationPayload = payload == null ? new byte[0] : payload;
        int ethernetLength = 14;
        int ipHeaderLength = 20;
        int tcpHeaderLength = 20;
        int packetLength = ethernetLength + ipHeaderLength + tcpHeaderLength + applicationPayload.length;
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Ethernet header
        putMac(buffer, new byte[] {0x02, 0x00, 0x00, 0x00, 0x00, 0x01});
        putMac(buffer, new byte[] {0x02, 0x00, 0x00, 0x00, 0x00, 0x02});
        buffer.putShort((short) ETHERTYPE_IPV4);

        int ipHeaderStart = buffer.position();
        buffer.put((byte) 0x45);
        buffer.put((byte) 0x00);
        buffer.putShort((short) (ipHeaderLength + tcpHeaderLength + applicationPayload.length));
        buffer.putShort((short) ThreadLocalRandom.current().nextInt(0, 0x10000));
        buffer.putShort((short) 0x4000);
        buffer.put((byte) 64);
        buffer.put((byte) IP_PROTOCOL_TCP);
        int ipChecksumPosition = buffer.position();
        buffer.putShort((short) 0);
        buffer.putInt(sourceIp);
        buffer.putInt(destinationIp);

        int tcpHeaderStart = buffer.position();
        buffer.putShort((short) sourcePort);
        buffer.putShort((short) destinationPort);
        buffer.putInt((int) sequenceNumber);
        buffer.putInt((int) acknowledgementNumber);
        buffer.put((byte) ((tcpHeaderLength / 4) << 4));
        buffer.put((byte) tcpFlags);
        buffer.putShort((short) 65_535);
        int tcpChecksumPosition = buffer.position();
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.put(applicationPayload);

        byte[] packet = buffer.array();
        short ipChecksum = checksum(packet, ipHeaderStart, ipHeaderLength);
        ByteBuffer.wrap(packet, ipChecksumPosition, 2).order(ByteOrder.BIG_ENDIAN).putShort(ipChecksum);

        short tcpChecksum = tcpChecksum(packet, tcpHeaderStart, tcpHeaderLength + applicationPayload.length, sourceIp, destinationIp);
        ByteBuffer.wrap(packet, tcpChecksumPosition, 2).order(ByteOrder.BIG_ENDIAN).putShort(tcpChecksum);
        return packet;
    }

    /** 包级私有仅供测试:Ethernet + IPv4(proto 17) + UDP,校验和按伪头计算。 */
    static byte[] buildEthernetIpv4UdpPacket(
            int sourceIp,
            int destinationIp,
            int sourcePort,
            int destinationPort,
            byte[] payload
    ) {
        byte[] applicationPayload = payload == null ? new byte[0] : payload;
        int ethernetLength = 14;
        int ipHeaderLength = 20;
        int udpHeaderLength = 8;
        int udpLength = udpHeaderLength + applicationPayload.length;
        int packetLength = ethernetLength + ipHeaderLength + udpLength;
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Ethernet header
        putMac(buffer, new byte[] {0x02, 0x00, 0x00, 0x00, 0x00, 0x01});
        putMac(buffer, new byte[] {0x02, 0x00, 0x00, 0x00, 0x00, 0x02});
        buffer.putShort((short) ETHERTYPE_IPV4);

        int ipHeaderStart = buffer.position();
        buffer.put((byte) 0x45);
        buffer.put((byte) 0x00);
        buffer.putShort((short) (ipHeaderLength + udpLength));
        buffer.putShort((short) ThreadLocalRandom.current().nextInt(0, 0x10000));
        buffer.putShort((short) 0x4000);
        buffer.put((byte) 64);
        buffer.put((byte) IP_PROTOCOL_UDP);
        int ipChecksumPosition = buffer.position();
        buffer.putShort((short) 0);
        buffer.putInt(sourceIp);
        buffer.putInt(destinationIp);

        int udpHeaderStart = buffer.position();
        buffer.putShort((short) sourcePort);
        buffer.putShort((short) destinationPort);
        buffer.putShort((short) udpLength);
        int udpChecksumPosition = buffer.position();
        buffer.putShort((short) 0);
        buffer.put(applicationPayload);

        byte[] packet = buffer.array();
        short ipChecksum = checksum(packet, ipHeaderStart, ipHeaderLength);
        ByteBuffer.wrap(packet, ipChecksumPosition, 2).order(ByteOrder.BIG_ENDIAN).putShort(ipChecksum);

        ByteBuffer pseudoHeader = ByteBuffer.allocate(12 + udpLength);
        pseudoHeader.order(ByteOrder.BIG_ENDIAN);
        pseudoHeader.putInt(sourceIp);
        pseudoHeader.putInt(destinationIp);
        pseudoHeader.put((byte) 0);
        pseudoHeader.put((byte) IP_PROTOCOL_UDP);
        pseudoHeader.putShort((short) udpLength);
        pseudoHeader.put(packet, udpHeaderStart, udpLength);
        short udpChecksum = checksum(pseudoHeader.array(), 0, pseudoHeader.array().length);
        ByteBuffer.wrap(packet, udpChecksumPosition, 2).order(ByteOrder.BIG_ENDIAN).putShort(udpChecksum);
        return packet;
    }

    private void writeSectionHeaderBlock() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(28);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x0A0D0D0A);
        buffer.putInt(28);
        buffer.putInt(0x1A2B3C4D);
        buffer.putShort((short) 1);
        buffer.putShort((short) 0);
        buffer.putLong(-1L);
        buffer.putInt(28);
        output.write(buffer.array());
        output.flush();
    }

    private void writeInterfaceDescriptionBlock() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(20);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x00000001);
        buffer.putInt(20);
        buffer.putShort((short) LINKTYPE_ETHERNET);
        buffer.putShort((short) 0);
        buffer.putInt(65_535);
        buffer.putInt(20);
        output.write(buffer.array());
        output.flush();
    }

    private void writeEnhancedPacketBlock(byte[] packetData) throws IOException {
        long timestampMicros = System.currentTimeMillis() * 1_000L;
        int paddedLength = alignTo32Bits(packetData.length);
        int blockTotalLength = 32 + paddedLength;
        ByteBuffer buffer = ByteBuffer.allocate(blockTotalLength);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x00000006);
        buffer.putInt(blockTotalLength);
        buffer.putInt(0);
        buffer.putInt((int) (timestampMicros >>> 32));
        buffer.putInt((int) (timestampMicros & 0xFFFFFFFFL));
        buffer.putInt(packetData.length);
        buffer.putInt(packetData.length);
        buffer.put(packetData);
        while ((buffer.position() % 4) != 0) {
            buffer.put((byte) 0);
        }
        buffer.putInt(blockTotalLength);
        output.write(buffer.array());
        output.flush();
    }

    private static int alignTo32Bits(int length) {
        return (length + 3) & ~3;
    }

    private static void putMac(ByteBuffer buffer, byte[] mac) {
        buffer.put(mac, 0, 6);
    }

    private static short checksum(byte[] bytes, int offset, int length) {
        long sum = 0L;
        int end = offset + length;
        for (int i = offset; i < end; i += 2) {
            int high = bytes[i] & 0xFF;
            int low = (i + 1) < end ? (bytes[i + 1] & 0xFF) : 0;
            sum += (high << 8) | low;
            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >>> 16);
            }
        }
        return (short) ~sum;
    }

    private static short tcpChecksum(byte[] packet, int tcpOffset, int tcpLength, int sourceIp, int destinationIp) {
        ByteBuffer pseudoHeader = ByteBuffer.allocate(12 + tcpLength);
        pseudoHeader.order(ByteOrder.BIG_ENDIAN);
        pseudoHeader.putInt(sourceIp);
        pseudoHeader.putInt(destinationIp);
        pseudoHeader.put((byte) 0);
        pseudoHeader.put((byte) IP_PROTOCOL_TCP);
        pseudoHeader.putShort((short) tcpLength);
        pseudoHeader.put(packet, tcpOffset, tcpLength);
        return checksum(pseudoHeader.array(), 0, pseudoHeader.array().length);
    }

    private static int ipv4(int a, int b, int c, int d) {
        return ((a & 0xFF) << 24)
                | ((b & 0xFF) << 16)
                | ((c & 0xFF) << 8)
                | (d & 0xFF);
    }

    private record ConnectionInfo(int serverIp, int serverPort, String hostHeader, String requestPath) {
        private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

        private static ConnectionInfo from(String serverUrl) {
            URI uri;
            try {
                uri = new URI(serverUrl == null ? "" : serverUrl.trim());
            } catch (URISyntaxException ignored) {
                uri = null;
            }

            String host = uri != null && uri.getHost() != null ? uri.getHost().trim() : "127.0.0.1";
            boolean secure = uri != null && "wss".equalsIgnoreCase(uri.getScheme());
            int port = uri != null && uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
            String path = "/";
            if (uri != null) {
                String rawPath = uri.getRawPath();
                String rawQuery = uri.getRawQuery();
                if (rawPath != null && !rawPath.isBlank()) {
                    path = rawPath;
                }
                if (rawQuery != null && !rawQuery.isBlank()) {
                    path += "?" + rawQuery;
                }
            }
            String hostHeader = port == 80 || port == 443 ? host : host + ":" + port;
            int ip = IPV4_LITERAL.matcher(host).matches() ? parseIpv4(host) : DEFAULT_SERVER_IP;
            return new ConnectionInfo(ip, port, hostHeader, path);
        }

        private static int parseIpv4(String ipv4) {
            String[] tokens = ipv4.split("\\.");
            if (tokens.length != 4) {
                return DEFAULT_SERVER_IP;
            }
            int[] parts = new int[4];
            for (int i = 0; i < tokens.length; i++) {
                try {
                    parts[i] = Integer.parseInt(tokens[i]);
                } catch (NumberFormatException ignored) {
                    return DEFAULT_SERVER_IP;
                }
                if (parts[i] < 0 || parts[i] > 255) {
                    return DEFAULT_SERVER_IP;
                }
            }
            return ipv4(parts[0], parts[1], parts[2], parts[3]);
        }
    }
}
