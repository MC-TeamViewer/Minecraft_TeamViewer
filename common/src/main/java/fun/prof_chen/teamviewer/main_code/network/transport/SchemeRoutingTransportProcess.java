package fun.prof_chen.teamviewer.main_code.network.transport;

import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportProcess;

/**
 * 按 URL scheme 显式选择传输门:{@code quic://} 走裸 QUIC 门,其余
 * ({@code ws://}/{@code wss://} 等)走 OkHttp WebSocket 门。每次连接尝试
 * 重新判定,配置改 URL 后无需重启即生效;不自动回退——所选门启动失败就是
 * 失败(由 NetworkManager 记录并重连)。
 */
public final class SchemeRoutingTransportProcess implements TransportProcess {
    private static final String QUIC_SCHEME = "quic://";

    private final TransportProcess webSocketProcess;
    private final TransportProcess quicProcess;

    public SchemeRoutingTransportProcess(TransportProcess webSocketProcess, TransportProcess quicProcess) {
        this.webSocketProcess = webSocketProcess;
        this.quicProcess = quicProcess;
    }

    @Override
    public SocketProcess connect(String uri, TransportOptions options, TransportListener listener) {
        TransportProcess process = uri != null && uri.regionMatches(true, 0, QUIC_SCHEME, 0, QUIC_SCHEME.length())
                ? quicProcess
                : webSocketProcess;
        return process.connect(uri, options, listener);
    }
}
