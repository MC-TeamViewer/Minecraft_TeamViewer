package fun.prof_chen.teamviewer.main_code.network.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketCaptureWriterTest {
    private static final int CLIENT_IP = (127 << 24) | 1;
    private static final int SERVER_IP = (36 << 24) | (150 << 16) | (231 << 8) | 125;

    @Test
    void udpPacketCarriesEthernetIpv4UdpHeaders() {
        byte[] payload = {0x0A, 0x0B, 0x0C, 0x0D, 0x0E};
        byte[] packet = WebSocketCaptureWriter.buildEthernetIpv4UdpPacket(
                CLIENT_IP, SERVER_IP, 39051, 8767, payload);
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);

        assertEquals(14 + 20 + 8 + payload.length, packet.length);
        assertEquals(0x0800, buffer.getShort(12) & 0xFFFF);
        assertEquals(0x45, buffer.get(14));
        assertEquals(20 + 8 + payload.length, buffer.getShort(16) & 0xFFFF);
        assertEquals(17, buffer.get(23));
        assertEquals(39051, buffer.getShort(34) & 0xFFFF);
        assertEquals(8767, buffer.getShort(36) & 0xFFFF);
        assertEquals(8 + payload.length, buffer.getShort(38) & 0xFFFF);
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], buffer.get(42 + i));
        }
    }

    @Test
    void udpPacketChecksumsSurviveOddPayloadLengths() {
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        byte[] packet = WebSocketCaptureWriter.buildEthernetIpv4UdpPacket(
                CLIENT_IP, SERVER_IP, 39051, 8767, payload);

        assertTrue(internetChecksum(packet, 14, 20) == 0, "ipv4 checksum");
        assertTrue(udpChecksumMatches(packet, payload.length), "udp checksum");
    }

    @Test
    void datagramsAreWrittenAsEnhancedPacketBlocks(@TempDir Path logsDir) throws Exception {
        Path out;
        try (WebSocketCaptureWriter writer = WebSocketCaptureWriter.open(
                logsDir, "quic://127.0.0.1:8767", "room/1", "")) {
            writer.writeClientDatagramMessage(new byte[] {1, 2, 3});
            writer.writeServerDatagramMessage(new byte[] {4, 5, 6, 7});
            out = writer.getOutputPath();
        }
        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);

        // 逐块走 pcapng:SHB + IDB 之后,构造器写 5 个合成 TCP 握手包,随后
        // 两个 EPB 是本测试写入的 datagram(caplen = 42 + 载荷),close() 再写
        // 2 个 FIN 挥手包。TCP 包 caplen 为整帧长 54 = 14+20+20,握手载荷另计。
        assertEquals(0x0A0D0D0A, file.getInt(0));
        assertEquals(0x00000001, file.getInt(28));
        file.position(48);
        int[] expectedCaplens = {54, 54, 54, -1, -1, 45, 46, 54, 54};
        for (int expected : expectedCaplens) {
            assertEquals(0x00000006, file.getInt());
            int blockLength = file.getInt();
            file.position(file.position() + 12); // interface_id + timestamp
            int caplen = file.getInt();
            if (expected >= 0) {
                assertEquals(expected, caplen);
            }
            file.position(file.position() + 4 + align4(caplen)); // origlen + 对齐后的包数据
            assertEquals(blockLength, file.getInt()); // 块尾长度
        }
        assertEquals(file.limit(), file.position());
    }

    private static int align4(int length) {
        return (length + 3) & ~3;
    }

    private static long fold(long sum) {
        while ((sum & 0xFFFF0000L) != 0) {
            sum = (sum & 0xFFFFL) + (sum >>> 16);
        }
        return sum;
    }

    /** 对字段区间求反码和,结果为 0 即校验和自洽。 */
    private static long internetChecksum(byte[] bytes, int offset, int length) {
        long sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            int high = bytes[i] & 0xFF;
            int low = (i + 1) < offset + length ? (bytes[i + 1] & 0xFF) : 0;
            sum = fold(sum + ((high << 8) | low));
        }
        return ~sum & 0xFFFF;
    }

    private static boolean udpChecksumMatches(byte[] packet, int payloadLength) {
        int udpLength = 8 + payloadLength;
        ByteBuffer pseudo = ByteBuffer.allocate(12 + udpLength).order(ByteOrder.BIG_ENDIAN);
        pseudo.put(packet, 26, 8); // 源/目的 IP
        pseudo.put((byte) 0);
        pseudo.put((byte) 17);
        pseudo.putShort((short) udpLength);
        pseudo.put(packet, 34, udpLength);
        short stored = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getShort(40);
        byte[] data = pseudo.array();
        // UDP 校验和在伪头副本中的偏移 = 12(伪头) + 6(UDP 头内偏移)
        data[12 + 6] = (byte) ((stored >>> 8) & 0xFF);
        data[12 + 7] = (byte) (stored & 0xFF);
        return internetChecksum(data, 0, data.length) == 0;
    }
}
