package fun.prof_chen.teamviewer.main_code.network.abstraction;

public interface TransportListener {
    void onOpen(String negotiatedExtensions);

    void onTextMessage(String text);

    void onBinaryMessage(byte[] payload);

    /**
     * 下行 movement datagram(alpha.10):载荷为已解压的裸 WireEnvelope 字节
     * (合同恒为 {@code WireEnvelope{WebMap, Patch}},消费端按载荷类型识别、
     * 与连接角色无关)。尽力投递语义:解析失败静默丢弃,不断连。
     * 默认空实现——无 datagram 消费的门(WS/OkHttp)不受影响。
     */
    default void onMovementDatagram(byte[] payload) {
    }

    void onTrafficEvent(TransportTrafficEvent event);

    void onClosed(int statusCode, String reason);

    void onFailure(Throwable error);
}
