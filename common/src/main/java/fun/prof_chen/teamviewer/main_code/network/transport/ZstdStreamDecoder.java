package fun.prof_chen.teamviewer.main_code.network.transport;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 连续 zstd 流解压器(压缩套下行专用——压缩套语义为单向,上行恒 plain)。
 * 实现副本:之一为后端 compress.rs StreamDecoder(测试参照),之二为网页脚本
 * fzstd 封装,之三为本类;契约真相源为 TeamViewRelay-Protocol README
 * "压缩套"。放主 mod 包(不在 quic 子包)的原因:WS 门在父 loader 直接用,
 * QUIC 门的 child-first ClassLoader 对非 io.netty/quic 包一律委派父级,两门
 * 因此共享同一类身份与同一条 zstd-jni native。
 *
 * <p>线路模型:发送端每连接一个持久 CCtx,逐 envelope {@code write + flush}
 * (ZSTD_e_flush 保证即时可解码),压缩块作为一帧 payload/一条 WS binary
 * 消息送达;接收端把逐块持续喂进同一条持久 DCtx,读出的解压字节即 envelope
 * 本体——压缩块边界与 envelope 边界一一对应,但本实现不依赖该假设:输出按流
 * 推进,任意切开的输入与多 envelope 合批同样正确。</p>
 *
 * <p>非阻塞语义:{@link #push} 同步排干当前可解压字节后立即返回(底层
 * continuous 模式在输入暂时枯竭时以 -1 收场且流保持开启),因此可在单条读
 * 线程(WS 读线程 / QUIC netty IO 线程)内安全调用,不会为等下一块而挂起。
 * 单实例只允许单线程顺序使用(每门各自独占)。解压失败属协议违规:抛
 * {@link IOException} 由调用方显式断连,不得静默吞帧。</p>
 */
public final class ZstdStreamDecoder implements Closeable {

	/** 解压产出交付口:每次 {@link #push} 聚合出的一段解压字节(envelope 本体
	 * 或若干 envelope 拼接),恰好一次(无产出则不回调)。 */
	public interface Sink {

		void onDecompressed(byte[] data);
	}

	/** 单次 push 交付上限(解压炸弹防护),与可靠流帧上限同值:2^23 = 8 MiB。 */
	private static final int MAX_CHUNK_PLAIN_BYTES = 8 * 1024 * 1024;

	/** 解压窗口上限(对齐后端 MAX_DECOMPRESSION_WINDOW_LOG):防对端大窗口
	 * 帧逼出大内存分配,超限帧以 IOException 显式拒绝。 */
	private static final int MAX_DECOMPRESSION_WINDOW_LOG = 23;

	private static final int DRAIN_WINDOW_BYTES = 64 * 1024;

	private final Sink sink;
	private final FeedableSource source = new FeedableSource();
	private final ZstdInputStreamNoFinalizer stream;
	private final byte[] drainWindow = new byte[DRAIN_WINDOW_BYTES];

	public ZstdStreamDecoder(Sink sink) throws IOException {
		this.sink = sink;
		this.stream = new ZstdInputStreamNoFinalizer(source)
				.setContinuous(true)
				.setLongMax(MAX_DECOMPRESSION_WINDOW_LOG);
	}

	/**
	 * 喂入一个压缩块并同步排干可解压字节;本块的全部产出聚合为一次
	 * {@link Sink#onDecompressed} 回调(无产出则不回调)。
	 */
	public void push(byte[] chunk) throws IOException {
		source.append(chunk);
		ByteArrayOutputStream collected = null;
		int produced = 0;
		while (true) {
			int count;
			try {
				count = stream.read(drainWindow, 0, drainWindow.length);
			} catch (IOException error) {
				throw new IOException("zstd stream decode failed", error);
			}
			if (count < 0) {
				break;
			}
			if (collected == null) {
				collected = new ByteArrayOutputStream(Math.max(count * 2, 256));
			}
			collected.write(drainWindow, 0, count);
			produced += count;
			if (produced > MAX_CHUNK_PLAIN_BYTES) {
				throw new IOException("zstd stream decompressed past 8 MiB chunk limit");
			}
		}
		if (collected != null) {
			sink.onDecompressed(collected.toByteArray());
		}
	}

	@Override
	public void close() {
		try {
			stream.close();
		} catch (IOException ignored) {
		}
	}

	/**
	 * 可追加的无阻塞字节源:压入端 {@link #append},zstd 读端读空即 -1
	 * (continuous 模式据此保持解压流开启,后续 append 后继续推进)。
	 */
	private static final class FeedableSource extends InputStream {

		private byte[] buffer = new byte[8 * 1024];
		private int head;
		private int tail;

		void append(byte[] data) {
			if (data.length == 0) {
				return;
			}
			if (head > 0 && tail + data.length > buffer.length) {
				System.arraycopy(buffer, head, buffer, 0, tail - head);
				tail -= head;
				head = 0;
			}
			if (tail + data.length > buffer.length) {
				int capacity = Math.max(buffer.length * 2, head + tail + data.length);
				buffer = Arrays.copyOf(buffer, capacity);
			}
			System.arraycopy(data, 0, buffer, tail, data.length);
			tail += data.length;
		}

		@Override
		public int read(byte[] out, int offset, int length) {
			if (length == 0) {
				return 0;
			}
			int available = tail - head;
			if (available == 0) {
				return -1;
			}
			int count = Math.min(length, available);
			System.arraycopy(buffer, head, out, offset, count);
			head += count;
			if (head == tail) {
				head = 0;
				tail = 0;
			}
			return count;
		}

		@Override
		public int read() {
			byte[] single = new byte[1];
			return read(single, 0, 1) < 0 ? -1 : (single[0] & 0xFF);
		}

		@Override
		public int available() {
			return tail - head;
		}
	}
}
