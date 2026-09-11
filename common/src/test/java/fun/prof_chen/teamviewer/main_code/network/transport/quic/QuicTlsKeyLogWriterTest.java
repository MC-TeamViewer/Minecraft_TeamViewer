package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuicTlsKeyLogWriterTest {

    private static final String CLIENT_SECRET =
            "CLIENT_HANDSHAKE_TRAFFIC_SECRET 5a5b6c7d8e9f0a1b2c3d4e5f60718293 a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a";
    private static final String SERVER_SECRET =
            "SERVER_TRAFFIC_SECRET_0 5a5b6c7d8e9f0a1b2c3d4e5f60718293 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @Test
    void appendsSslKeylogLinesAndCreatesParentDirectories(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("teamviewer-network-dumps").resolve("teamviewer-quic-keys.log");

        QuicTlsKeyLogWriter writer = new QuicTlsKeyLogWriter(target);
        writer.logKey((SSLEngine) null, CLIENT_SECRET);
        writer.logKey((SSLEngine) null, SERVER_SECRET);

        List<String> lines = Files.readAllLines(target);
        assertEquals(2, lines.size());
        assertEquals(CLIENT_SECRET, lines.get(0));
        assertEquals(SERVER_SECRET, lines.get(1));
    }

    @Test
    void appendsAcrossWriterInstancesWithoutTruncating(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("keys.log");

        QuicTlsKeyLogWriter first = new QuicTlsKeyLogWriter(target);
        first.logKey((SSLEngine) null, CLIENT_SECRET);
        QuicTlsKeyLogWriter second = new QuicTlsKeyLogWriter(target);
        second.logKey((SSLEngine) null, SERVER_SECRET);

        List<String> lines = Files.readAllLines(target);
        assertEquals(2, lines.size());
        assertEquals(CLIENT_SECRET, lines.get(0));
        assertEquals(SERVER_SECRET, lines.get(1));
    }

    @Test
    void silentlyDisablesItselfAfterWriteFailure(@TempDir Path tempDir) {
        // 父路径是一个普通文件:创建父目录必然失败,writer 进入永久静默
        Path blockingFile = tempDir.resolve("blocking");
        assertDoesNotThrow(() -> Files.writeString(blockingFile, "not a directory"));
        Path target = blockingFile.resolve("keys.log");

        QuicTlsKeyLogWriter writer = new QuicTlsKeyLogWriter(target);
        assertDoesNotThrow(() -> writer.logKey((SSLEngine) null, CLIENT_SECRET));
        assertDoesNotThrow(() -> writer.logKey((SSLEngine) null, SERVER_SECRET));

        assertFalse(Files.exists(target));
        assertTrue(Files.exists(blockingFile));
    }
}
