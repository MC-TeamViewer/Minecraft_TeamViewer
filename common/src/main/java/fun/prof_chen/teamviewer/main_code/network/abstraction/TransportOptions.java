package fun.prof_chen.teamviewer.main_code.network.abstraction;

import java.util.Locale;

/**
 * 传输层连接参数。压缩套(compressionSuite)取 {@link #SUITE_PLAIN} /
 * {@link #SUITE_ZSTD} / {@link #SUITE_ZSTD_DICT} 之一;总开关关闭时一律
 * 明文直连(QUIC 仅提供基础 ALPN,WS 不提供子协议与 permessage-deflate)。
 * wsPlainNoDeflate 仅作用于 plain 套的 WS 门:连 permessage-deflate 也不
 * 协商,抓包/排障时得到纯明文流。
 */
public record TransportOptions(
        boolean useSystemProxy,
        boolean enableCompression,
        boolean allowInsecureTls,
        String compressionSuite,
        boolean wsPlainNoDeflate
) {
    public static final String SUITE_PLAIN = "plain";
    public static final String SUITE_ZSTD = "zstd";
    public static final String SUITE_ZSTD_DICT = "zstd-dict";

    /** 非法/缺失值一律归 zstd-dict(既有行为的等价命名)。 */
    public static String normalizeSuite(String value) {
        if (value == null) {
            return SUITE_ZSTD_DICT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case SUITE_PLAIN -> SUITE_PLAIN;
            case SUITE_ZSTD -> SUITE_ZSTD;
            default -> SUITE_ZSTD_DICT;
        };
    }
}
