package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import io.netty.handler.codec.quic.BoringSSLKeylog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * SSLKEYLOGFILE 格式的 TLS 密钥落盘器:挂到 {@link QuicTransportCore} 建连
 * 时的 {@code QuicSslContextBuilder.keylog(...)} 上,BoringSSL 每导出一行
 * (CLIENT_HANDSHAKE_TRAFFIC_SECRET 等)就追加写入文件并立即 flush。产物供
 * Wireshark / tshark(-o tls.keylog_file)解密对应时段的 QUIC 抓包。
 * 纯调试用途:开关关闭时根本不会挂上回调,不收集任何密钥材料。
 * 必须与 QuicTransportCore 同包打进 quic-core jar:BoringSSLKeylog 接口在
 * child-first ClassLoader 的 netty bundle 里,主 jar 类路径上没有该接口。
 */
final class QuicTlsKeyLogWriter implements BoringSSLKeylog {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuicTlsKeyLogWriter.class);

    private final Path path;
    private final Object lock = new Object();
    private BufferedWriter writer;
    private boolean failed;

    QuicTlsKeyLogWriter(Path path) {
        this.path = path;
    }

    /** 写失败即永久静默(本连接内不再尝试):密钥导出绝不能影响连接本身。 */
    @Override
    public void logKey(SSLEngine engine, String line) {
        synchronized (lock) {
            if (failed) {
                return;
            }
            try {
                if (writer == null) {
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                failed = true;
                closeQuietly();
                LOGGER.warn("QUIC TLS key log disabled after write failure: {}", e.toString());
            }
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 静默:关闭失败不影响连接
            }
            writer = null;
        }
    }
}
