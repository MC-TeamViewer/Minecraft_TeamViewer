package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;

import java.util.Arrays;

/**
 * 下行 movement datagram(alpha.10)解码器。datagram 自带报文边界,载荷恒为
 * 一个自包含 zstd 帧或裸 envelope:plain 套原样透传;+zstd 套为无字典独立
 * 单帧;+zstd-dict 套为字典单帧(ready 前仍是无字典独立单帧)。解码按
 * 「激活字典 → 前任字典 → 无字典单帧」顺序尝试,全部失败返回 null——
 * 调用方按尽力投递丢包静默丢弃,不触发断连。
 *
 * <p>字典轮换宽限:current+previous 双字典与服务端重训生命周期对齐,每次
 * 安装现任降级为 previous、前任的前任释放。in-flight 的旧字典 datagram 在
 * 轮换后仍可解出;解压失败等价丢包,下个 dirty tick 全量重发自愈。</p>
 *
 * <p>必须 public:本类定义在主 jar(knot loader),而 QuicTransportCore 运行
 * 在 QuicTransportProcess 的 child-first 隔离 loader 内,经父委派跨 loader
 * 引用——包私有跨 loader 即 IllegalAccessError(同 ZstdStreamDecoder 的
 * "共享同一类身份"先例,见 common/build.gradle quicCoreJar 注释)。</p>
 */
public final class MovementDatagramDecoder {
    /** 字典内容防御上限,与后端 MAX_DICT_CONTENT 对齐。 */
    static final int MAX_DICT_CONTENT = 16 * 1024;
    /** 解压产物防御上限:datagram 上限约 1.2KB,64KB 已是数量级冗余。 */
    private static final int MAX_DATAGRAM_PAYLOAD = 64 * 1024;

    private final Object lock = new Object();
    private ZstdDecompressCtx currentDict;
    private ZstdDecompressCtx previousDict;

    /** 安装新字典:现任降级为 previous。内容非法或加载失败返回 false,
     * 现役字典保持不动(服务端收不到 ready 即维持独立压缩,自愈)。 */
    boolean install(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_DICT_CONTENT) {
            return false;
        }
        ZstdDecompressCtx next = new ZstdDecompressCtx();
        try {
            next.loadDict(content);
        } catch (RuntimeException error) {
            next.close();
            return false;
        }
        synchronized (lock) {
            if (previousDict != null) {
                previousDict.close();
            }
            previousDict = currentDict;
            currentDict = next;
        }
        return true;
    }

    /** +zstd 套:无字典独立单帧。 */
    byte[] decodeZstd(byte[] data) {
        return tryFrameDecompress(data);
    }

    /** +zstd-dict 套:字典(current → previous)→ 无字典独立单帧。 */
    byte[] decodeDict(byte[] data) {
        synchronized (lock) {
            if (currentDict != null) {
                byte[] out = tryCtxDecompress(currentDict, data);
                if (out != null) {
                    return out;
                }
            }
            if (previousDict != null) {
                byte[] out = tryCtxDecompress(previousDict, data);
                if (out != null) {
                    return out;
                }
            }
        }
        return tryFrameDecompress(data);
    }

    void close() {
        synchronized (lock) {
            if (currentDict != null) {
                currentDict.close();
                currentDict = null;
            }
            if (previousDict != null) {
                previousDict.close();
                previousDict = null;
            }
        }
    }

    private static byte[] tryCtxDecompress(ZstdDecompressCtx ctx, byte[] data) {
        try {
            byte[] dst = allocate(data);
            if (dst == null) {
                return null;
            }
            int size = ctx.decompressByteArray(dst, 0, dst.length, data, 0, data.length);
            return size > 0 ? Arrays.copyOf(dst, size) : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static byte[] tryFrameDecompress(byte[] data) {
        try {
            byte[] dst = allocate(data);
            if (dst == null) {
                return null;
            }
            long size = Zstd.decompress(dst, data);
            return size > 0 ? Arrays.copyOf(dst, (int) size) : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 按帧内嵌内容大小分配;未知大小时按防御上限缓冲,声明超出上限直接拒绝。 */
    private static byte[] allocate(byte[] src) {
        long size = Zstd.getFrameContentSize(src);
        if (size > MAX_DATAGRAM_PAYLOAD) {
            return null;
        }
        if (size > 0) {
            return new byte[(int) size];
        }
        return new byte[MAX_DATAGRAM_PAYLOAD];
    }
}
