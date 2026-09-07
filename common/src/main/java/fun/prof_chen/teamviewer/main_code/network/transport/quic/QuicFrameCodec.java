package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import java.io.ByteArrayOutputStream;

/**
 * 流分帧实现副本之三: {@code [varint LEB128 长度][payload]}。
 * 实现副本之一为后端 Rust 的 frame.rs,之二为网页脚本 TS 的 FrameDecoder,
 * 合同真相源为 TeamViewRelay-Protocol README "传输门约定",三份副本不得漂移。
 *
 * <p>帧上限 8 MiB(按 payload 计),长度头最长 4 字节;非法头部属于协议违规,
 * 必须断连,不得静默等待或丢弃。WebSocket 门自带消息边界,不使用本分帧;
 * 本类仅服务于裸 QUIC 门的可靠流通道。</p>
 */
final class QuicFrameCodec {
    static final int MAX_FRAME_LEN = 8 * 1024 * 1024;
    static final int MAX_FRAME_HEADER_LEN = 4;

    private QuicFrameCodec() {
    }

    /** 给 payload 加长度头;调用方保证 payload 不超过 {@link #MAX_FRAME_LEN}。 */
    static byte[] frame(byte[] payload) {
        if (payload.length > MAX_FRAME_LEN) {
            throw new IllegalArgumentException("frame payload exceeds 8 MiB limit: " + payload.length);
        }
        int headerLength = 1;
        while ((payload.length >>> (7 * headerLength)) != 0) {
            headerLength++;
        }
        byte[] out = new byte[headerLength + payload.length];
        int value = payload.length;
        for (int index = 0; index < headerLength; index++) {
            out[index] = (byte) ((value & 0x7F) | (index == headerLength - 1 ? 0x00 : 0x80));
            value >>>= 7;
        }
        System.arraycopy(payload, 0, out, headerLength, payload.length);
        return out;
    }

    /**
     * 读方向的增量重组器:按到达顺序喂入流字节,凑齐一帧即取出。
     * 非法头部(LEB128 超 4 字节或声明超限)抛 {@link IllegalStateException},
     * 调用方必须按协议违规断连。
     */
    static final class Reassembler {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        void feed(byte[] chunk, int offset, int length) {
            pending.write(chunk, offset, length);
        }

        /** 返回完整 payload;头部或载荷未到齐时返回 null。 */
        byte[] tryTakeFrame() {
            byte[] all = pending.toByteArray();
            long length = 0;
            int headerLength = 0;
            while (true) {
                if (headerLength >= MAX_FRAME_HEADER_LEN) {
                    throw new IllegalStateException("frame header exceeds 4-byte varint limit");
                }
                if (headerLength >= all.length) {
                    return null; // 头部未到齐
                }
                int group = all[headerLength] & 0xFF;
                length |= (long) (group & 0x7F) << (7 * headerLength);
                headerLength++;
                if ((group & 0x80) == 0) {
                    break;
                }
            }
            if (length > MAX_FRAME_LEN) {
                throw new IllegalStateException("frame declares payload over 8 MiB limit: " + length);
            }
            if (all.length - headerLength < length) {
                return null; // 载荷未到齐
            }
            byte[] payload = new byte[(int) length];
            System.arraycopy(all, headerLength, payload, 0, (int) length);
            pending.reset();
            int remaining = all.length - headerLength - (int) length;
            if (remaining > 0) {
                pending.write(all, headerLength + (int) length, remaining);
            }
            return payload;
        }
    }
}
