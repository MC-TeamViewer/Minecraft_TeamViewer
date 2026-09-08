package fun.prof_chen.teamviewer.main_code.network.abstraction;

public interface SocketProcess {
    void send(byte[] payload);

    void close(int statusCode, String reason);

    /**
     * 本传输是否具备 datagram 上行能力(裸 QUIC 门开启 datagram 选项后为 true,
     * WebSocket 门恒 false)。仅影响握手声明;实际分流以 HandshakeAck 回执为准。
     */
    default boolean supportsDatagram() {
        return false;
    }

    /**
     * 经不可靠 datagram 上送一帧(裸 WireEnvelope,自带边界,恒 plain)。
     * 尽力投递:不排队、不重传;返回 false 表示当前不可用(调用方回落可靠流)。
     */
    default boolean sendDatagram(byte[] payload) {
        return false;
    }
}
