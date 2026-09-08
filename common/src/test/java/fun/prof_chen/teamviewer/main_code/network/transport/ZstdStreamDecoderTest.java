package fun.prof_chen.teamviewer.main_code.network.transport;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩端用 zstd-jni 的 ZstdOutputStreamNoFinalizer + ZSTD_e_flush 生成
 * fixture,与后端 compress.rs StreamEncoder 逐 envelope flush 的线路语义
 * 同款;解压端走被测的 ZstdStreamDecoder。
 */
class ZstdStreamDecoderTest {

	@Test
	void flushChunkBoundaryYieldsExactlyOneEnvelopePerPush() throws IOException {
		byte[][] envelopes = sampleEnvelopes();
		List<byte[]> chunks = flushChunks(envelopes);
		assertEquals(envelopes.length, chunks.size(), "每条 envelope flush 出恰好一个压缩块");

		List<byte[]> deliveries = decodeAll(chunks);
		assertEquals(envelopes.length, deliveries.size());
		for (int i = 0; i < envelopes.length; i++) {
			assertArrayEquals(envelopes[i], deliveries.get(i), "块边界与 envelope 边界对齐");
		}
	}

	@Test
	void concatenatedChunksSplitAtArbitraryBoundariesDecodeInOrder() throws IOException {
		byte[][] envelopes = sampleEnvelopes();
		List<byte[]> chunks = flushChunks(envelopes);
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		for (byte[] chunk : chunks) {
			all.write(chunk);
		}
		byte[] wire = all.toByteArray();

		List<byte[]> pushes = new ArrayList<>();
		for (int offset = 0; offset < wire.length; offset += 997) {
			pushes.add(Arrays.copyOfRange(wire, offset, Math.min(offset + 997, wire.length)));
		}
		List<byte[]> deliveries = decodeAll(pushes);

		ByteArrayOutputStream restored = new ByteArrayOutputStream();
		for (byte[] delivery : deliveries) {
			restored.write(delivery);
		}
		assertArrayEquals(concat(envelopes), restored.toByteArray());
	}

	@Test
	void multipleChunksInOnePushDeliverConcatenatedEnvelopes() throws IOException {
		byte[][] envelopes = sampleEnvelopes();
		List<byte[]> chunks = flushChunks(envelopes);

		byte[] merged = concat(chunks.get(1), chunks.get(2));
		List<byte[]> deliveries = decodeAll(List.of(chunks.get(0), merged));

		assertEquals(2, deliveries.size());
		assertArrayEquals(envelopes[0], deliveries.get(0));
		assertArrayEquals(concat(envelopes[1], envelopes[2]), deliveries.get(1));
	}

	@Test
	void garbageChunkIsRejected() throws IOException {
		byte[] garbage = new byte[64];
		Arrays.fill(garbage, (byte) 0xFF);
		ZstdStreamDecoder decoder = assertDoesNotThrowCreate();
		assertThrows(IOException.class, () -> decoder.push(garbage));
	}

	@Test
	void decompressionPastChunkLimitIsRejected() throws IOException {
		// 三个 3MiB 全零一次性帧(每帧窗口 2^22,不触窗口上限)拼进一次
		// push:累计解压 9MiB 超过单次 push 8MiB 交付上限,必须显式拒绝
		byte[] zeros = new byte[3 * 1024 * 1024];
		byte[] wire = concat(Zstd.compress(zeros), Zstd.compress(zeros), Zstd.compress(zeros));
		List<byte[]> deliveries = new ArrayList<>();
		try (ZstdStreamDecoder decoder = new ZstdStreamDecoder(deliveries::add)) {
			IOException error = assertThrows(IOException.class, () -> decoder.push(wire));
			assertTrue(error.getMessage().contains("8 MiB"), "实际报错:" + error.getMessage());
		}
		assertTrue(deliveries.isEmpty(), "超限块不得交付任何部分产出");
	}

	private static ZstdStreamDecoder assertDoesNotThrowCreate() {
		try {
			return new ZstdStreamDecoder(data -> {
				throw new AssertionError("垃圾块不得产出");
			});
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static byte[][] sampleEnvelopes() {
		return new byte[][] {
				"tiny".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				new byte[4096],
				patterned(100_000),
				"snapshot:players{alice,bob};digest:aa11".getBytes(java.nio.charset.StandardCharsets.UTF_8),
		};
	}

	private static byte[] patterned(int length) {
		byte[] data = new byte[length];
		for (int i = 0; i < length; i++) {
			data[i] = (byte) (i % 251);
		}
		return data;
	}

	private static byte[] concat(byte[]... parts) throws IOException {
		ByteArrayOutputStream merged = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			merged.write(part);
		}
		return merged.toByteArray();
	}

	/** 逐 envelope write + flush(ZSTD_e_flush),按 flush 边界截出压缩块列表
	 * (close 的尾帧不截,后端线路从不发尾帧)。 */
	private static List<byte[]> flushChunks(byte[][] envelopes) throws IOException {
		FlushRecordingSink sink = new FlushRecordingSink();
		ZstdOutputStreamNoFinalizer compressor = new ZstdOutputStreamNoFinalizer(sink);
		compressor.setLevel(3);
		for (byte[] envelope : envelopes) {
			compressor.write(envelope);
			compressor.flush();
		}
		List<byte[]> chunks = new ArrayList<>(sink.chunks());
		sink.stopRecording();
		compressor.close();
		return chunks;
	}

	private static List<byte[]> decodeAll(List<byte[]> pushes) throws IOException {
		List<byte[]> deliveries = new ArrayList<>();
		try (ZstdStreamDecoder decoder = new ZstdStreamDecoder(deliveries::add)) {
			for (byte[] push : pushes) {
				decoder.push(push);
			}
		}
		return deliveries;
	}

	private static final class FlushRecordingSink extends OutputStream {

		private final List<byte[]> chunks = new ArrayList<>();
		private ByteArrayOutputStream current = new ByteArrayOutputStream();
		private boolean recording = true;

		List<byte[]> chunks() {
			return chunks;
		}

		void stopRecording() {
			recording = false;
		}

		@Override
		public void write(int b) {
			current.write(b);
		}

		@Override
		public void write(byte[] data, int offset, int length) {
			current.write(data, offset, length);
		}

		@Override
		public void flush() {
			if (!recording) {
				return;
			}
			// ZstdOutputStreamNoFinalizer.flush()(ZSTD_e_flush)写穿到此处:
			// current 恰为一个压缩块,边界与后端 compress_chunk 一致
			chunks.add(current.toByteArray());
			current = new ByteArrayOutputStream();
		}

		@Override
		public void close() {
		}
	}
}
