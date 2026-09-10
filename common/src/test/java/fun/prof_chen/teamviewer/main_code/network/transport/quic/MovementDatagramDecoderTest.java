package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictTrainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 下行 movement datagram 解码器:三套形态(裸 envelope / 无字典单帧 /
 * 字典单帧)、current+previous 轮换宽限与失败丢包语义。压缩侧用 zstd-jni
 * 自训字典(与服务端 Rust zstd crate 同为标准 zstd 训练字典格式)。 */
class MovementDatagramDecoderTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 训练一份能压缩 movement 样本的字典(样本形态对齐服务端 movement 批)。 */
    private static byte[] trainDict() {
        ZstdDictTrainer trainer = new ZstdDictTrainer(16 * 1024, 4096);
        for (int index = 0; index < 64; index++) {
            trainer.addSample(bytes(
                    "movement:players{probe-%d:(100.5,64.0,200.25),other:(101.0,64.0,201.0)}"
                            .formatted(index % 8)));
        }
        return trainer.trainSamples();
    }

    private static byte[] compressWithDict(byte[] dict, byte[] payload) {
        byte[] dst = new byte[payload.length + 4096];
        long size = Zstd.compressUsingDict(dst, 0, payload, 0, payload.length, dict, 3);
        assertTrue(size > 0);
        byte[] frame = new byte[(int) size];
        System.arraycopy(dst, 0, frame, 0, frame.length);
        return frame;
    }

    @Test
    void decodesPlainPassThroughAndDictFreeFrames() {
        MovementDatagramDecoder decoder = new MovementDatagramDecoder();
        try {
            byte[] envelope = bytes("movement:players{a:(1,2,3)}");
            // +zstd 套的 datagram 恒为压缩帧:裸字节不是合法 zstd 帧,按丢包拒收
            assertNull(decoder.decodeZstd(envelope));
            byte[] frame = Zstd.compress(envelope, 3);
            assertArrayEquals(envelope, decoder.decodeZstd(frame));

            // 字典帧不能当无字典单帧解:libzstd 报字典缺失,等价丢包
            byte[] dictFrame = compressWithDict(trainDict(), envelope);
            assertNull(decoder.decodeZstd(dictFrame));
        } finally {
            decoder.close();
        }
    }

    @Test
    void decodesDictFramesAndSurvivesRotationWithPreviousDict() {
        byte[] firstDict = trainDict();
        byte[] secondDict = trainDict();
        MovementDatagramDecoder decoder = new MovementDatagramDecoder();
        try {
            byte[] payload = bytes("movement:players{probe-1:(100.5,64.0,200.25),other:(101.0,64.0,201.0)}");
            byte[] firstFrame = compressWithDict(firstDict, payload);
            byte[] secondFrame = compressWithDict(secondDict, payload);

            assertTrue(decoder.install(firstDict));
            assertArrayEquals(payload, decoder.decodeDict(firstFrame));
            // 字典帧不能当无字典单帧解:zstd-jni 报字典缺失,等价丢包
            assertNull(decoder.decodeZstd(firstFrame));

            // 轮换:现任降级 previous,in-flight 的旧字典 datagram 仍可解出
            assertTrue(decoder.install(secondDict));
            assertArrayEquals(payload, decoder.decodeDict(secondFrame));
            assertArrayEquals(payload, decoder.decodeDict(firstFrame));
            // 无字典独立单帧在字典激活后仍走兜底解出(轮换竞态在途帧)
            assertArrayEquals(payload, decoder.decodeDict(Zstd.compress(payload, 3)));
        } finally {
            decoder.close();
        }
    }

    @Test
    void rejectsInvalidDictContentWithoutDisplacingCurrent() {
        MovementDatagramDecoder decoder = new MovementDatagramDecoder();
        try {
            // 尺寸防御:空与超上限在安装时即拒绝
            assertFalse(decoder.install(new byte[0]));
            assertFalse(decoder.install(new byte[17 * 1024]));
            // 短垃圾字典 libzstd 是懒校验(装得上、解压时才失败):不测安装拒绝,
            // 由解码链的「失败回退无字典单帧」兜住

            byte[] dict = trainDict();
            assertTrue(decoder.install(dict));
            // 超上限内容不 displacement:现任字典继续可用
            assertFalse(decoder.install(new byte[17 * 1024]));
            byte[] payload = bytes("movement:players{probe-2:(100.5,64.0,200.25)}");
            assertArrayEquals(payload, decoder.decodeDict(compressWithDict(dict, payload)));
        } finally {
            decoder.close();
        }
    }

    @Test
    void dropsGarbageAndUndecodableFramesSilently() {
        MovementDatagramDecoder decoder = new MovementDatagramDecoder();
        try {
            assertTrue(decoder.install(trainDict()));
            assertNull(decoder.decodeDict(new byte[] {9, 9, 9}));
            assertNull(decoder.decodeZstd(new byte[] {9, 9, 9}));
            assertNull(decoder.decodeDict(new byte[0]));
        } finally {
            decoder.close();
        }
    }

    @Test
    void rejectsHugeJunkFrames() {
        MovementDatagramDecoder decoder = new MovementDatagramDecoder();
        try {
            // 合法 zstd 魔数开头但语义为垃圾的超大帧:拒绝且不抛出
            byte[] junk = new byte[128 * 1024];
            junk[0] = 0x28;
            junk[1] = (byte) 0xb5;
            junk[2] = 0x2f;
            junk[3] = (byte) 0xfd;
            assertNull(decoder.decodeZstd(junk));
        } finally {
            decoder.close();
        }
    }
}
