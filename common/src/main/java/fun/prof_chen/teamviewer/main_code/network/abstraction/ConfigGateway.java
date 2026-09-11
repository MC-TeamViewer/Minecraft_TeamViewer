package fun.prof_chen.teamviewer.main_code.network.abstraction;

public interface ConfigGateway {
    String getServerURL();

    void setServerURL(String serverURL);

    String getRoomCode();

    void setRoomCode(String roomCode);

    boolean isUseSystemProxy();

    void setUseSystemProxy(boolean useSystemProxy);

    boolean isAllowInsecureTls();

    void setAllowInsecureTls(boolean allowInsecureTls);

    boolean isEnableCompression();

    int getUpdateIntervalTicks();

    default String getTabHistorySyncMode() {
        return "on_demand";
    }

    default String getExternalRelationSyncMode() {
        return "on_demand";
    }

    /** 压缩协议套:plain / zstd / zstd-dict(默认)。 */
    default String getCompressionSuite() {
        return TransportOptions.SUITE_ZSTD_DICT;
    }

    /** plain 套下 WS 门不协商 permessage-deflate。 */
    default boolean isWsPlainNoDeflate() {
        return false;
    }
}
