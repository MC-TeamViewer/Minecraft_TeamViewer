// 玩家渲染网络通信管理器
// 负责处理与服务器的WebSocket连接和数据同步
package fun.prof_chen.teamviewer.main_code.bridge;

import fun.prof_chen.teamviewer.api.PlayerRelation;
import fun.prof_chen.teamviewer.api.RemotePlayerPositionSource;
import fun.prof_chen.teamviewer.api.RemotePlayerPositionSourceKind;
import fun.prof_chen.teamviewer.api.RemotePlayerSnapshot;
import fun.prof_chen.teamviewer.api.LastSeenPlayerSnapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import fun.prof_chen.teamviewer.main_code.model.Position3D;
import fun.prof_chen.teamviewer.main_code.model.RemotePlayerInfo;
import fun.prof_chen.teamviewer.main_code.model.LastSeenPlayerInfo;
import fun.prof_chen.teamviewer.main_code.model.SharedWaypointInfo;
import fun.prof_chen.teamviewer.main_code.model.BattleChunkRefData;
import fun.prof_chen.teamviewer.main_code.client.model.TabPlayerSnapshot;
import fun.prof_chen.teamviewer.main_code.network.abstraction.ConfigGateway;
import fun.prof_chen.teamviewer.main_code.network.abstraction.RuntimeGateway;
import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportTrafficEvent;
import fun.prof_chen.teamviewer.main_code.network.capture.WebSocketCaptureWriter;
import fun.prof_chen.teamviewer.main_code.network.protocol.MessageCodec;
import fun.prof_chen.teamviewer.main_code.network.protocol.EntityPatchView;
import fun.prof_chen.teamviewer.main_code.network.protocol.ProtocolVersionUtil;
import fun.prof_chen.teamviewer.main_code.network.protocol.ProtocolPackets;
import fun.prof_chen.teamviewer.main_code.network.protocol.ProtobufMessageCodec;
import fun.prof_chen.teamviewer.main_code.network.protocol.UuidBinaryCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TeamViewRelay 网络层管理器 - 核心网络通信组件
 * 
 * ## 核心功能
 * 1) WebSocket连接管理：建立、维护、重连、断开连接
 * 2) 数据双向同步：玩家位置、实体信息、路标数据的上传下载
 * 3) 增量更新机制：支持差分同步，减少网络流量
 * 4) 数据一致性保障：通过摘要校验和强制刷新机制确保数据同步
 * 
 * ## 协议特性
 * - 支持版本协商和协议兼容性检测
 * - 实现心跳保活和自动重连机制
 * - 提供完整的错误处理和状态监控
 * 
 * ## 线程安全设计
 * - 网络回调在OkHttp工作线程执行
 * - 状态变更通过任务队列串行化到Minecraft主线程
 * - 避免跨线程直接修改共享数据结构
 */
// 网络管理器主类：协议状态机 + 数据同步，不直接依赖具体网络栈
public class NetworkManager {
	/**
	 * 连接状态监听器接口
	 * 用于通知UI和其他模块网络连接状态变化
	 */
	public interface ConnectionStatusListener {
		void onConnectionStatusChanged(boolean connected);
	}

	public enum ConnectionStage {
		DISCONNECTED,
		CONNECTING,
		CONNECTED_HANDSHAKING,
		CONNECTED,
		FAILED
	}

	/**
	 * 路标更新监听器接口
	 * 处理远程路标数据的接收和删除事件
	 */
	public interface WaypointUpdateListener {
		/**
		 * 当接收到新的路标数据时调用
		 * @param waypoints 新增或更新的路标映射
		 */
		void onWaypointsReceived(Map<String, SharedWaypointInfo> waypoints);

		/**
		 * 当路标被删除时调用
		 * @param waypointIds 被删除的路标ID列表
		 */
		default void onWaypointsDeleted(List<String> waypointIds) {
		}
	}

	public static final class TrafficStatsSnapshot {
		private final long uploadApplicationBytesTotal;
		private final long downloadApplicationBytesTotal;
		private final long uploadWireBytesTotal;
		private final long downloadWireBytesTotal;
		private final long uploadApplicationBytesPerSecond;
		private final long downloadApplicationBytesPerSecond;
		private final long uploadWireBytesPerSecond;
		private final long downloadWireBytesPerSecond;
		private final boolean connected;

		public TrafficStatsSnapshot(
				long uploadApplicationBytesTotal,
				long downloadApplicationBytesTotal,
				long uploadWireBytesTotal,
				long downloadWireBytesTotal,
				long uploadApplicationBytesPerSecond,
				long downloadApplicationBytesPerSecond,
				long uploadWireBytesPerSecond,
				long downloadWireBytesPerSecond,
				boolean connected) {
			this.uploadApplicationBytesTotal = uploadApplicationBytesTotal;
			this.downloadApplicationBytesTotal = downloadApplicationBytesTotal;
			this.uploadWireBytesTotal = uploadWireBytesTotal;
			this.downloadWireBytesTotal = downloadWireBytesTotal;
			this.uploadApplicationBytesPerSecond = uploadApplicationBytesPerSecond;
			this.downloadApplicationBytesPerSecond = downloadApplicationBytesPerSecond;
			this.uploadWireBytesPerSecond = uploadWireBytesPerSecond;
			this.downloadWireBytesPerSecond = downloadWireBytesPerSecond;
			this.connected = connected;
		}

		public long getUploadApplicationBytesTotal() {
			return uploadApplicationBytesTotal;
		}

		public long getDownloadApplicationBytesTotal() {
			return downloadApplicationBytesTotal;
		}

		public long getUploadWireBytesTotal() {
			return uploadWireBytesTotal;
		}

		public long getDownloadWireBytesTotal() {
			return downloadWireBytesTotal;
		}

		public long getUploadApplicationBytesPerSecond() {
			return uploadApplicationBytesPerSecond;
		}

		public long getDownloadApplicationBytesPerSecond() {
			return downloadApplicationBytesPerSecond;
		}

		public long getUploadWireBytesPerSecond() {
			return uploadWireBytesPerSecond;
		}

		public long getDownloadWireBytesPerSecond() {
			return downloadWireBytesPerSecond;
		}

		public boolean isConnected() {
			return connected;
		}
	}

	private static final class TrafficSample {
		private final long timestampMs;
		private final long applicationBytes;
		private final long wireBytes;

		private TrafficSample(long timestampMs, long applicationBytes, long wireBytes) {
			this.timestampMs = timestampMs;
			this.applicationBytes = applicationBytes;
			this.wireBytes = wireBytes;
		}
	}

	// 日志记录器
	private static final Logger LOGGER = LoggerFactory.getLogger(NetworkManager.class);

	// 重同步冷却时间(毫秒) - 防止频繁重同步请求
	private static final long RESYNC_COOLDOWN_MS = 3_000L;
	
	// 强制全量刷新间隔(毫秒) - 确保数据最终一致性
	private static final long FORCE_FULL_REFRESH_MS = 60_000L;
	
	// 对象级保活默认间隔(毫秒) - 若握手未下发 timeout，则使用该值
	private static final long DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS = 12_000L;
	private static final int UNLIMITED_RECONNECT_ATTEMPTS = -1;
	private static final long DEFAULT_RECONNECT_DELAY_MS = 5_000L;
	private static final long TRAFFIC_RATE_WINDOW_MS = 3_000L;
	private static final List<String> SOURCE_STATE_CLEAR_SCOPES = List.of("players", "entities", "tab_players", "waypoints", "battle_chunks");

	// 单次 keepalive 报文最多携带对象数量
	private static final int KEEPALIVE_MAX_ITEMS_PER_PACKET = 128;
	private static final int MAX_MAIN_THREAD_TASKS_PER_TICK = 32;
	private static final long MAIN_THREAD_TASK_BUDGET_NANOS = 2_000_000L;
	private static final int MAIN_THREAD_BACKLOG_WARN_THRESHOLD = 40;
	private static final long MAIN_THREAD_BACKLOG_WARN_INTERVAL_MS = 5_000L;
	private static final long DIGEST_SLOW_TASK_WARN_NANOS = 50_000_000L;
	private static final long RELATION_RETRY_MS = 30_000L;

	// 全局配置网关（由 loader 层注入）
	private static ConfigGateway configGateway;

	private final RuntimeGateway runtimeGateway;
	private final TransportProcess transport;
	
	// 远程玩家信息缓存 - 存储其他客户端玩家的位置和维度信息
	private final Map<UUID, RemotePlayerInfo> remotePlayers;
	private final Map<UUID, LastSeenPlayerInfo> lastSeenPlayers;
	
	// 远程玩家数据缓存 - 存储玩家的完整属性数据
	private final Map<UUID, Map<String, Object>> remotePlayerDataCache = new HashMap<>();

	// Immutable external view, published only after a complete inbound update.
	private volatile List<RemotePlayerSnapshot> publishedRemotePlayerSnapshots = List.of();
	private volatile List<LastSeenPlayerSnapshot> publishedLastSeenPlayerSnapshots = List.of();
	/** Immutable state used by the common client hot paths; it is replaced only on semantic change. */
	private volatile Map<UUID, RemotePlayerInfo> publishedRemotePlayerState = Map.of();
	private volatile Map<UUID, LastSeenPlayerInfo> publishedLastSeenPlayerState = Map.of();
	private volatile long remotePlayerStateGeneration;
	private volatile long lastSeenPlayerStateGeneration;
	private final Map<String, Map<String, Object>> lastSeenPlayerDataCache = new HashMap<>();
	private final Map<UUID, Map<String, Object>> tabHistoryCache = new HashMap<>();
	private final Map<String, TabHistorySyncAccumulator> tabHistorySyncs = new HashMap<>();
	private final Map<String, Set<UUID>> tabHistoryLookupRequests = new HashMap<>();
	private final Set<UUID> completedTabHistoryLookups = new HashSet<>();
	private final Set<String> dirtyTabHistoryLookupRequests = new HashSet<>();
	private volatile List<TabPlayerSnapshot> publishedTabHistoryPlayers = List.of();
	private volatile long tabHistoryStateGeneration;
	private long tabHistoryRevision;
	private byte[] tabHistoryDigest = new byte[0];
	private long tabHistoryRequestSequence;
	private int tabHistoryMaxLookupSelectors = 128;
	private boolean tabHistorySupported;
	private final Map<String, Map<String, Object>> externalRelationCache = new HashMap<>();
	private final Map<String, Set<UUID>> externalRelationRequests = new HashMap<>();
	private final Set<UUID> pendingExternalRelationIds = new HashSet<>();
	private final Set<UUID> unavailableExternalRelationIds = new HashSet<>();
	private final Map<UUID, Long> unavailableExternalRelationAt = new HashMap<>();
	private volatile List<Map.Entry<UUID, Map<String, Object>>> publishedExternalRelations = List.of();
	private volatile long externalRelationStateGeneration;
	private long externalRelationRequestSequence;
	private int externalRelationMaxSelectors = 256;
	private int externalRelationMaxChunkEntries = 256;
	private boolean externalRelationSupported;
	private boolean suppressTabReports;
	
	// 远程实体数据缓存 - 存储世界中实体的位置和属性
	private final Map<String, Map<String, Object>> remoteEntityDataCache = new HashMap<>();
	
	// 远程路标原始数据缓存 - 存储路标的完整数据结构
	private final Map<String, Map<String, Object>> remoteWaypointDataCache = new HashMap<>();

	// 远程战局区块缓存 - 存储服务端裁决后的战局区块数据
	private final Map<BattleChunkRefData, Map<String, Object>> remoteBattleChunkDataCache = new HashMap<>();
	
	// 玩家标记状态缓存 - 存储玩家的队伍归属和颜色标记
	private final Map<String, PlayerMarkState> remotePlayerMarks = new HashMap<>();
	
	// 上次发送的玩家快照 - 用于计算增量更新
	private final Map<String, Map<String, Object>> lastSentPlayersSnapshot = new HashMap<>();
	
	// 上次发送的实体快照 - 用于计算增量更新
	private final Map<String, Map<String, Object>> lastSentEntitiesSnapshot = new HashMap<>();

	// 上次发送的 TAB 玩家快照 - 用于计算增量更新
	private final Map<String, Map<String, Object>> lastSentTabPlayersSnapshot = new HashMap<>();

	// 对象级保活时间戳：记录某个对象最近一次被显式保活/上报的时刻
	private final Map<String, Long> lastPlayerObjectLivenessMs = new HashMap<>();
	private final Map<String, Long> lastEntityObjectLivenessMs = new HashMap<>();

	// 传输连接实例
	private SocketProcess socket;
	
	// 重连调度器 - 负责连接失败后的自动重连
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
	private final ExecutorService digestExecutor = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "teamviewrelay-digest");
		thread.setDaemon(true);
		return thread;
	});
	private final Object digestTaskLock = new Object();
	private DigestStateSnapshot pendingDigestSnapshot;
	private boolean digestTaskRunning;
	private long digestRequestSequence;
	private long digestMergedRequestCount;
	private long inboundStateEpoch;
	
	// 连接状态标志 - 表示当前是否与服务器保持连接
	private volatile boolean isConnected = false;

	// 底层 WebSocket 打开状态 - 区分“传输已建立”和“握手已完成”
	private volatile boolean transportOpen = false;
	private volatile ConnectionStage connectionStage = ConnectionStage.DISCONNECTED;
	private volatile long connectionAttemptSequence = 0L;
	private volatile long activeConnectionAttemptId = 0L;
	private volatile boolean handshakeSent = false;
	private volatile boolean handshakeCompleted = false;
	
	// 重连意愿标志 - 控制是否应该尝试重连
	private volatile boolean shouldReconnect = false;
	private volatile int maxReconnectAttempts = UNLIMITED_RECONNECT_ATTEMPTS;
	private final AtomicInteger reconnectAttemptsRemaining = new AtomicInteger(UNLIMITED_RECONNECT_ATTEMPTS);
	private volatile long reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;
	
	// 版本不兼容导致的重连抑制标志
	private volatile boolean reconnectSuppressedForVersionMismatch = false;
	
	// JSON序列化工具 - 用于协议数据的编码解码
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	private final MessageCodec messageCodec = new ProtobufMessageCodec();
	private final Object outboundStateLock = new Object();
	private volatile long outboundEpoch = 1L;
	private final Object packetDumpLock = new Object();
	private final Object trafficStatsLock = new Object();
	private final ArrayDeque<TrafficSample> uploadTrafficSamples = new ArrayDeque<>();
	private final ArrayDeque<TrafficSample> downloadTrafficSamples = new ArrayDeque<>();
	private volatile boolean packetDumpActive = false;
	private volatile WebSocketCaptureWriter packetDumpWriter;
	private volatile String packetDumpLastSavedPath = "";
	private volatile String packetDumpCurrentPath = "";
	private volatile String currentNegotiatedExtensions = "";
	private long uploadApplicationBytesTotal = 0L;
	private long downloadApplicationBytesTotal = 0L;
	private long uploadWireBytesTotal = 0L;
	private long downloadWireBytesTotal = 0L;
	private long uploadApplicationBytesInWindow = 0L;
	private long downloadApplicationBytesInWindow = 0L;
	private long uploadWireBytesInWindow = 0L;
	private long downloadWireBytesInWindow = 0L;
	private long trafficStatsSessionStartedAtMs = 0L;
	
	// 连接状态监听器列表 - 线程安全的监听器注册表
	private final List<ConnectionStatusListener> statusListeners = new CopyOnWriteArrayList<>();
	
	// 路标更新监听器列表 - 线程安全的监听器注册表
	private final List<WaypointUpdateListener> waypointListeners = new CopyOnWriteArrayList<>();

	// 最近一次连接错误信息 - 用于诊断连接问题
	private volatile String lastConnectionError = "";
	
	// 服务端协议版本 - 用于版本兼容性判断
	private volatile String serverProtocolVersion = "0.0.0";
	
	// 服务端程序版本 - 用于版本对比和调试
	private volatile String serverProgramVersion = "unknown";
	
	// 摘要校验间隔(秒) - 控制数据一致性检查频率
	private volatile int digestIntervalSec = 10;

	// 服务端广播频率与协商后的上报间隔
	private volatile double serverBroadcastHz = 20.0;
	private volatile int negotiatedReportIntervalTicks = 10;
	private volatile long playerKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
	private volatile long entityKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
	private volatile long battleChunkKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
	
	// 上次重同步请求时间戳 - 防止重复请求
	private volatile long lastResyncRequestMs = 0L;
	
	// 上次发送玩家数据包的时间 - 用于强制刷新判断
	private volatile long lastPlayersPacketSentMs = 0L;
	
	// 上次发送实体数据包的时间 - 用于强制刷新判断
	private volatile long lastEntitiesPacketSentMs = 0L;
	
	// 待刷新的玩家ID集合 - 响应服务端刷新请求
	private final Set<String> pendingPlayerRefreshIds = new HashSet<>();
	
	// 待刷新的实体ID集合 - 响应服务端刷新请求
	private final Set<String> pendingEntityRefreshIds = new HashSet<>();

	// 待刷新的战局区块ID集合 - 响应服务端刷新请求
	private final Set<BattleChunkRefData> pendingBattleChunkRefreshRefs = new HashSet<>();
	/**
	 * 主线程任务队列 - 线程安全的任务传递机制
	 * 
	 * 设计原理：
	 * - WebSocket回调在线程池中异步执行
	 * - 通过队列将任务传递给Minecraft主线程
	 * - 在客户端tick循环中顺序处理这些任务
	 * 
	 * 优势：
	 * - 避免跨线程直接修改共享数据结构
	 * - 确保所有状态变更在同一线程中执行
	 * - 防止并发修改异常和数据不一致
	 */
	private final Queue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
	private final AtomicInteger pendingMainThreadTaskCount = new AtomicInteger();
	private volatile long lastMainThreadBacklogWarningAt;

	/**
	 * 玩家标记状态记录类
	 * 存储玩家的队伍归属、颜色标记和自定义标签
	 */
	private record PlayerMarkState(String team, Integer color, String label, String source) {
	}

	private static final class TabHistorySyncAccumulator {
		final boolean full;
		final long revision;
		final byte[] digest;
		final Map<UUID, Map<String, Object>> upsert = new HashMap<>();
		final Set<UUID> delete = new HashSet<>();

		TabHistorySyncAccumulator(boolean full, long revision, byte[] digest) {
			this.full = full;
			this.revision = revision;
			this.digest = digest == null ? new byte[0] : digest.clone();
		}
	}

	private record PlayerPatchChange(boolean remotePlayersChanged, boolean lastSeenPlayersChanged) {
		private static final PlayerPatchChange NONE = new PlayerPatchChange(false, false);
	}

	private record DigestStateSnapshot(
			long sequence,
			long inboundStateEpoch,
			long queuedAtNanos,
			Map<String, String> serverHashes,
			Map<UUID, Map<String, Object>> players,
			Map<String, Map<String, Object>> entities,
			Map<String, Map<String, Object>> waypoints,
			Map<BattleChunkRefData, Map<String, Object>> battleChunks,
			Map<String, Map<String, Object>> lastSeenPlayers,
			String protocolVersion
	) {
	}

	private record DigestComputation(
			DigestStateSnapshot snapshot,
			List<String> mismatchedScopes,
			String localPlayerHash,
			String localEntityHash,
			String localWaypointHash,
			BattleChunkDigestValues battleChunkDigests,
			String localLastSeenPlayersHash,
			long queueNanos,
			long computeNanos
	) {
	}

	/** Immutable normalized backend mark, including its precedence-bearing source. */
	public record PlayerMarkView(PlayerRelation relation, String source) {
		public PlayerMarkView {
			relation = relation == null ? PlayerRelation.NEUTRAL : relation;
		}

		public boolean automatic() {
			return "auto".equals(source);
		}
	}

	/**
	 * 构造函数
	 * @param remotePlayers 远程玩家信息映射的引用
	 */
	public NetworkManager(
			Map<UUID, RemotePlayerInfo> remotePlayers,
			RuntimeGateway runtimeGateway,
			TransportProcess transport
	) {
		this(remotePlayers, new HashMap<>(), runtimeGateway, transport);
	}

	public NetworkManager(
			Map<UUID, RemotePlayerInfo> remotePlayers,
			Map<UUID, LastSeenPlayerInfo> lastSeenPlayers,
			RuntimeGateway runtimeGateway,
			TransportProcess transport
	) {
		this.remotePlayers = remotePlayers;
		this.lastSeenPlayers = lastSeenPlayers;
		this.runtimeGateway = runtimeGateway;
		this.transport = transport;
		resetNegotiationState();
	}

	/**
	 * 设置全局配置网关
	 * @param configGateway 配置网关
	 */
	public static void setConfigGateway(ConfigGateway configGateway) {
		NetworkManager.configGateway = configGateway;
	}

	/**
	 * 处理主线程任务队列 - 核心线程同步机制
	 * 
	 * 执行时机：在StandaloneMultiPlayer的END_CLIENT_TICK事件中调用
	 * 功能说明：
	 * - 顺序执行所有排队的网络任务
	 * - 将异步网络回调的结果应用到主线程状态
	 * - 确保对共享数据结构的操作是线程安全的
	 * 
	 * 异常处理：捕获并记录任务执行中的错误，防止队列处理中断
	 */
	public void pumpMainThreadTasks() {
		long deadline = System.nanoTime() + MAIN_THREAD_TASK_BUDGET_NANOS;
		int processed = 0;
		Runnable task;
		while (processed < MAX_MAIN_THREAD_TASKS_PER_TICK
				&& (processed == 0 || System.nanoTime() < deadline)
				&& (task = mainThreadTasks.poll()) != null) {
			pendingMainThreadTaskCount.decrementAndGet();
			try {
				task.run();
			} catch (Exception e) {
				LOGGER.error("Error while processing queued network task: {}", e.getMessage());
			}
			processed++;
		}
		int pending = pendingMainThreadTaskCount.get();
		long now = System.currentTimeMillis();
		if (pending > MAIN_THREAD_BACKLOG_WARN_THRESHOLD
				&& now - lastMainThreadBacklogWarningAt >= MAIN_THREAD_BACKLOG_WARN_INTERVAL_MS) {
			lastMainThreadBacklogWarningAt = now;
			LOGGER.warn("TeamViewRelay main-thread network backlog: {} tasks pending", pending);
		}
	}

	/**
	 * 将任务加入主线程执行队列
	 * 
	 * 使用场景：
	 * - WebSocket事件回调中需要修改共享状态时
	 * - 网络数据解析完成后需要更新UI时
	 * - 需要确保在主线程执行的任何操作
	 * 
	 * 线程安全：使用ConcurrentLinkedQueue保证多线程环境下的安全入队
	 */
	private void enqueueMainThreadTask(Runnable task) {
		if (task != null) {
			pendingMainThreadTaskCount.incrementAndGet();
			mainThreadTasks.add(task);
		}
	}

	/**
	 * 建立WebSocket连接 - 网络通信入口点
	 * 
	 * 连接流程：
	 * 1. 检查配置有效性
	 * 2. 设置重连标志
	 * 3. 根据代理配置创建HTTP客户端
	 * 4. 构建WebSocket连接请求
	 * 5. 发起连接并注册回调监听器
	 * 
	 * 错误处理：
	 * - 连接失败时记录错误信息
	 * - 通知监听器连接状态变化
	 * - 自动调度重连机制
	 */
	public void connect() {
		startConnection(UNLIMITED_RECONNECT_ATTEMPTS, DEFAULT_RECONNECT_DELAY_MS);
	}

	public void connectWithReconnectLimit(int maxReconnectAttempts, long reconnectDelayMs) {
		startConnection(maxReconnectAttempts, reconnectDelayMs);
	}

	private void startConnection(int maxReconnectAttempts, long reconnectDelayMs) {
		if (configGateway == null || transport == null) {
			return;
		}
		shouldReconnect = true;
		reconnectSuppressedForVersionMismatch = false;
		this.maxReconnectAttempts = normalizeReconnectAttempts(maxReconnectAttempts);
		reconnectAttemptsRemaining.set(this.maxReconnectAttempts);
		this.reconnectDelayMs = normalizeReconnectDelayMs(reconnectDelayMs);
		doConnectAttempt();
	}

	private void doConnectAttempt() {
		if (configGateway == null || transport == null) {
			return;
		}

		final long attemptId = beginConnectionAttempt();
		boolean useSystemProxy = configGateway.isUseSystemProxy();
		boolean enableCompression = configGateway.isEnableCompression();
		boolean allowInsecureTls = configGateway.isAllowInsecureTls();
		String uri = configGateway.getServerURL();

		try {
			this.socket = transport.connect(uri, new TransportOptions(useSystemProxy, enableCompression, allowInsecureTls), new TransportListener() {
				@Override
				public void onOpen(String negotiatedExtensions) {
					handleTransportOpen(attemptId, negotiatedExtensions);
				}

				@Override
				public void onTextMessage(String text) {
					handleTransportTextMessage(attemptId, text);
				}

				@Override
				public void onBinaryMessage(byte[] payload) {
					handleTransportBinaryMessage(attemptId, payload);
				}

				@Override
				public void onTrafficEvent(TransportTrafficEvent event) {
					handleTransportTrafficEvent(attemptId, event);
				}

				@Override
				public void onClosed(int statusCode, String reason) {
					handleTransportClosed(attemptId, statusCode, reason);
				}

				@Override
				public void onFailure(Throwable error) {
					handleTransportFailure(attemptId, error);
				}
			});
		} catch (Exception e) {
			if (!isCurrentConnectionAttempt(attemptId)) {
				return;
			}
			this.transportOpen = false;
			this.isConnected = false;
			this.connectionStage = ConnectionStage.FAILED;
			invalidateConnectionAttempt(attemptId);
			this.lastConnectionError = formatThrowableReason(e);
			LOGGER.error("Failed to connect to TeamViewRelay server at {}: {}", configGateway.getServerURL(), e.getMessage());
			notifyConnectionStatusChanged(false);
			scheduleReconnect();
		}
	}

	/**
	 * 调度自动重连任务
	 * 
	 * 重连策略：
	 * - 延迟5秒后尝试重新连接
	 * - 只在shouldReconnect为true时执行
	 * - 使用单线程调度器避免并发重连
	 * 
	 * 异常处理：
	 * - 捕获调度器不可用的情况
	 * - 记录警告日志但不影响主流程
	 */
	private void scheduleReconnect() {
		if (!shouldReconnect) {
			return;
		}
		int remainingBeforeSchedule = claimReconnectAttempt(reconnectAttemptsRemaining);
		if (remainingBeforeSchedule == 0) {
			shouldReconnect = false;
			LOGGER.info("Reconnect retry budget exhausted; stopping automatic reconnects");
			return;
		}
		try {
			reconnectExecutor.schedule(this::doConnectAttempt, reconnectDelayMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			LOGGER.warn("Reconnect scheduler is unavailable: {}", e.getMessage());
		}
	}

	/**
	 * 断开WebSocket连接 - 主动关闭连接
	 * 
	 * 清理流程：
	 * 1. 取消重连意愿
	 * 2. 关闭WebSocket连接(状态码1000表示正常关闭)
	 * 3. 重置协议协商状态
	 * 4. 清空本地快照缓存
	 * 5. 更新连接状态标志
	 * 6. 通知监听器状态变化
	 * 
	 * 注意事项：这是一个干净的关闭过程，不会触发重连机制
	 */
	public void disconnect() {
		long attemptId = activeConnectionAttemptId;
		UUID localPlayerId = runtimeGateway != null ? runtimeGateway.getLocalPlayerId() : null;
		shouldReconnect = false;
		resetReconnectPolicy();
		invalidateConnectionAttempt(attemptId);
		synchronized (outboundStateLock) {
			outboundEpoch++;
			sendSourceStateClearUnlocked(localPlayerId, SOURCE_STATE_CLEAR_SCOPES);
			if (socket != null) {
				socket.close(1000, "Client disconnect");
				socket = null;
			}
		}
		transportOpen = false;
		resetNegotiationState();
		resetOutboundReportState();
		invalidateInboundRelayState();
		isConnected = false;
		connectionStage = ConnectionStage.DISCONNECTED;
		lastConnectionError = "";
		closePacketDumpWriterQuietly();
		notifyConnectionStatusChanged(false);
	}

	/**
	 * 注册连接状态监听器
	 * @param listener 连接状态变化监听器实例
	 */
	public void addConnectionStatusListener(ConnectionStatusListener listener) {
		if (listener != null) {
			statusListeners.add(listener);
		}
	}

	/**
	 * 移除连接状态监听器
	 * @param listener 要移除的监听器实例
	 */
	public void removeConnectionStatusListener(ConnectionStatusListener listener) {
		if (listener != null) {
			statusListeners.remove(listener);
		}
	}

	/**
	 * 注册路标更新监听器
	 * @param listener 路标数据更新监听器实例
	 */
	public void addWaypointUpdateListener(WaypointUpdateListener listener) {
		if (listener != null) {
			waypointListeners.add(listener);
		}
	}

	/**
	 * 移除路标更新监听器
	 * @param listener 要移除的监听器实例
	 */
	public void removeWaypointUpdateListener(WaypointUpdateListener listener) {
		if (listener != null) {
			waypointListeners.remove(listener);
		}
	}

	/**
	 * 发送玩家位置更新数据 - 核心上行数据传输方法
	 * 
	 * 协议选择逻辑：使用players_patch协议发送差分数据
	 * 
	 * 增量更新算法：
	 * 1. 构建当前玩家状态快照
	 * 2. 与上次发送的快照进行比较
	 * 3. 识别新增、修改、删除的玩家记录
	 * 4. 处理服务端的刷新请求
	 * 5. 只发送发生变化的数据以节省带宽
	 * 
	 * 协议格式(players_patch)：
	 * {
	 *   "type": "players_patch",
	 *   "submitPlayerId": "发送者UUID",
	 *   "upsert": {玩家ID: 变更字段},
	 *   "delete": [要删除的玩家ID列表]
	 * }
	 * 
	 * 性能优化：
	 * - 25秒强制全量刷新防止数据漂移
	 * - 快照机制避免重复发送相同数据
	 * - 批量处理提高网络效率
	 */
	public void sendPlayersUpdate(UUID submitPlayerId, Map<UUID, Map<String, Object>> players) {
		if (socket == null || !isConnected || submitPlayerId == null || players == null) {
			return;
		}
		long now = System.currentTimeMillis();

		Map<String, Map<String, Object>> currentSnapshot =
				new HashMap<>(hashMapCapacity(players.size()));
		for (Map.Entry<UUID, Map<String, Object>> entry : players.entrySet()) {
			currentSnapshot.put(entry.getKey().toString(), copyValueMap(entry.getValue()));
		}

		Map<String, Map<String, Object>> upsert =
				new HashMap<>(hashMapCapacity(players.size()));
		List<String> delete = new ArrayList<>();
		boolean forceFullRefresh = shouldForcePlayersFullRefresh();

		for (Map.Entry<String, Map<String, Object>> entry : currentSnapshot.entrySet()) {
			Map<String, Object> previous = lastSentPlayersSnapshot.get(entry.getKey());
			if (forceFullRefresh || previous == null) {
				upsert.put(entry.getKey(), entry.getValue());
				continue;
			}

			Map<String, Object> fieldDelta = computeFieldDelta(previous, entry.getValue());
			if (!fieldDelta.isEmpty()) {
				upsert.put(entry.getKey(), fieldDelta);
			}
		}

		for (String previousId : lastSentPlayersSnapshot.keySet()) {
			if (!currentSnapshot.containsKey(previousId)) {
				delete.add(previousId);
			}
		}

		applyPendingPlayerRefresh(currentSnapshot, upsert, delete);

		if (upsert.isEmpty() && delete.isEmpty()) {
			sendObjectKeepaliveIfNeeded(submitPlayerId, currentSnapshot, null, upsert.keySet(), null, now);
			return;
		}

		try {
			long sentAt = now;
			ProtocolPackets.PlayersPatchPacket packet = new ProtocolPackets.PlayersPatchPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.upsert = upsert;
			packet.delete = delete;
			sendPacket(packet);
			updateObjectLivenessAfterPatch(lastPlayerObjectLivenessMs, upsert.keySet(), delete, sentAt);
			lastSentPlayersSnapshot.clear();
			lastSentPlayersSnapshot.putAll(currentSnapshot);
			lastPlayersPacketSentMs = sentAt;
			sendObjectKeepaliveIfNeeded(submitPlayerId, currentSnapshot, null, upsert.keySet(), null, sentAt);
		} catch (Exception e) {
			LOGGER.error("Failed to send players_patch: {}", e.getMessage());
		}
	}

	/**
	 * 发送实体位置更新数据 - 实体信息同步方法
	 * 
	 * 功能说明：
	 * 与sendPlayersUpdate类似的增量更新机制，专门处理世界实体数据
	 * 包括怪物、掉落物、载具等非玩家实体的位置和状态信息
	 * 
	 * 协议格式(entities_patch)：
	 * {
	 *   "type": "entities_patch",
	 *   "submitPlayerId": "发送者UUID",
	 *   "upsert": {实体ID: 实体数据变更},
	 *   "delete": [要删除的实体ID列表]
	 * }
	 * 
	 * 优化特点：
	 * - 实体数据通常变化频率较低
	 * - 使用相同的快照和差分算法
	 * - 25秒强制刷新确保数据一致性
	 * - 支持服务端主动刷新请求
	 */
	public void sendEntitiesUpdate(UUID submitPlayerId, Map<String, Map<String, Object>> entities) {
		if (socket == null || !isConnected || submitPlayerId == null || entities == null) {
			return;
		}
		long now = System.currentTimeMillis();

		Map<String, Map<String, Object>> currentSnapshot =
				new HashMap<>(hashMapCapacity(entities.size()));
		for (Map.Entry<String, Map<String, Object>> entry : entities.entrySet()) {
			currentSnapshot.put(entry.getKey(), copyValueMap(entry.getValue()));
		}

		Map<String, Map<String, Object>> upsert =
				new HashMap<>(hashMapCapacity(entities.size()));
		List<String> delete = new ArrayList<>();
		boolean forceFullRefresh = shouldForceEntitiesFullRefresh();

		for (Map.Entry<String, Map<String, Object>> entry : currentSnapshot.entrySet()) {
			Map<String, Object> previous = lastSentEntitiesSnapshot.get(entry.getKey());
			if (forceFullRefresh || previous == null) {
				upsert.put(entry.getKey(), entry.getValue());
				continue;
			}

			Map<String, Object> fieldDelta = computeFieldDelta(previous, entry.getValue());
			if (!fieldDelta.isEmpty()) {
				upsert.put(entry.getKey(), fieldDelta);
			}
		}

		for (String previousId : lastSentEntitiesSnapshot.keySet()) {
			if (!currentSnapshot.containsKey(previousId)) {
				delete.add(previousId);
			}
		}

		applyPendingEntityRefresh(currentSnapshot, upsert, delete);

		if (upsert.isEmpty() && delete.isEmpty()) {
			sendObjectKeepaliveIfNeeded(submitPlayerId, null, currentSnapshot, null, upsert.keySet(), now);
			return;
		}

		try {
			long sentAt = now;
			ProtocolPackets.EntitiesPatchPacket packet = new ProtocolPackets.EntitiesPatchPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.upsert = upsert;
			packet.delete = delete;
			sendPacket(packet);
			updateObjectLivenessAfterPatch(lastEntityObjectLivenessMs, upsert.keySet(), delete, sentAt);
			lastSentEntitiesSnapshot.clear();
			lastSentEntitiesSnapshot.putAll(currentSnapshot);
			lastEntitiesPacketSentMs = sentAt;
			sendObjectKeepaliveIfNeeded(submitPlayerId, null, currentSnapshot, null, upsert.keySet(), sentAt);
		} catch (Exception e) {
			LOGGER.error("Failed to send entities_patch: {}", e.getMessage());
		}
	}

	/**
	 * 发送路标更新数据 - 路标信息上传方法
	 * 
	 * 功能说明：
	 * 上传本地创建或修改的路标信息到服务器
	 * 路标包括坐标点、名称、颜色、所属玩家等信息
	 * 
	 * 协议格式(waypoints_update)：
	 * {
	 *   "type": "waypoints_update",
	 *   "submitPlayerId": "发送者UUID",
	 *   "waypoints": {
	 *     "路标ID": {
	 *       "x": 坐标X,
	 *       "y": 坐标Y,
	 *       "z": 坐标Z,
	 *       "name": "路标名称",
	 *       "color": 颜色值,
	 *       ...
	 *     }
	 *   }
	 * }
	 * 
	 * 特点：
	 * - 目前采用全量更新方式(未来可优化为增量更新)
	 * - 每个路标都有唯一ID标识
	 * - 支持多种路标类型和属性
	 */
	public void sendWaypointsUpdate(UUID submitPlayerId, Map<String, Map<String, Object>> waypoints) {
		if (socket == null || !isConnected)
			return;
		if (waypoints == null || waypoints.isEmpty())
			return;
		try {
			ProtocolPackets.WaypointsUpdatePacket packet = new ProtocolPackets.WaypointsUpdatePacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.waypoints = waypoints;
			sendPacket(packet);
		} catch (Exception e) {
			LOGGER.error("Failed to send waypoints_update to TeamViewRelay server: {}", e.getMessage());
		}
	}

	private void sendSourceStateClearUnlocked(UUID submitPlayerId, List<String> scopes) {
		if (socket == null || !isConnected || submitPlayerId == null) {
			return;
		}
		try {
			ProtocolPackets.SourceStateClearPacket packet = new ProtocolPackets.SourceStateClearPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.scopes = scopes;
			sendPacketUnlocked(packet);
		} catch (Exception e) {
			LOGGER.warn("Failed to send source_state_clear before disconnect: {}", e.getMessage());
		}
	}

	/**
	 * Invalidate all captured entity frames before clearing the remote entity scope. The shared lock makes
	 * the clear and worker-side final epoch check a single total order.
	 */
	public void clearTypedEntitySource(UUID submitPlayerId) {
		synchronized (outboundStateLock) {
			outboundEpoch++;
			sendSourceStateClearUnlocked(submitPlayerId, List.of("entities"));
		}
	}

	public void clearPlayerSource(UUID submitPlayerId) {
		synchronized (outboundStateLock) {
			sendSourceStateClearUnlocked(submitPlayerId, List.of("players"));
			lastSentPlayersSnapshot.clear();
			lastPlayerObjectLivenessMs.clear();
			pendingPlayerRefreshIds.clear();
			lastPlayersPacketSentMs = 0L;
		}
	}

	/**
	 * 发送Tab玩家列表更新 - 玩家列表信息同步
	 * 
	 * 功能说明：
	 * 同步当前可见玩家的列表信息，包括UUID、名称、显示名等
	 * 主要用于玩家标记和队伍识别功能
	 * 
	 * 优化策略：
	 * - 首次发送完整基线，后续仅发送增量 patch
	 * - 玩家级别 diff，避免整表重传
	 * - 不再执行 TAB 的定时保底全量刷新
	 * 
	 * 协议格式(tab_players_patch)：
	 * {
	 *   "type": "tab_players_patch",
	 *   "submitPlayerId": "发送者UUID",
	 *   "upsert": {"玩家Key": 变更字段},
	 *   "delete": ["要删除的玩家Key"]
	 * }
	 */
	public void sendTabPlayersUpdate(UUID submitPlayerId, List<Map<String, Object>> tabPlayers) {
		if (socket == null || !isConnected || submitPlayerId == null || tabPlayers == null) {
			return;
		}
		if (suppressTabReports) return;

		try {
			Map<String, Map<String, Object>> currentSnapshot = buildTabPlayersSnapshot(tabPlayers);
			Map<String, Map<String, Object>> upsert = new HashMap<>();
			List<String> delete = new ArrayList<>();

			for (Map.Entry<String, Map<String, Object>> entry : currentSnapshot.entrySet()) {
				Map<String, Object> previous = lastSentTabPlayersSnapshot.get(entry.getKey());
				if (previous == null) {
					upsert.put(entry.getKey(), entry.getValue());
					continue;
				}

				Map<String, Object> fieldDelta = computeFieldDelta(previous, entry.getValue());
				if (!fieldDelta.isEmpty()) {
					upsert.put(entry.getKey(), entry.getValue());
				}
			}

			for (String previousId : lastSentTabPlayersSnapshot.keySet()) {
				if (!currentSnapshot.containsKey(previousId)) {
					delete.add(previousId);
				}
			}

			if (upsert.isEmpty() && delete.isEmpty()) {
				return;
			}

			ProtocolPackets.TabPlayersPatchPacket packet = new ProtocolPackets.TabPlayersPatchPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.upsert = upsert;
			packet.delete = delete;
			sendPacket(packet);

			lastSentTabPlayersSnapshot.clear();
			lastSentTabPlayersSnapshot.putAll(currentSnapshot);
		} catch (Exception e) {
			LOGGER.error("Failed to send tab_players_patch: {}", e.getMessage());
		}
	}

	/**
	 * 发送路标删除请求 - 路标移除通知
	 * 
	 * 功能说明：
	 * 通知服务器删除指定的路标记录
	 * 通常在玩家删除本地路标时调用
	 * 
	 * 协议格式(waypoints_delete)：
	 * {
	 *   "type": "waypoints_delete",
	 *   "submitPlayerId": "发送者UUID",
	 *   "waypointIds": ["路标ID1", "路标ID2", ...]
	 * }
	 * 
	 * 数据验证：
	 * - 过滤空值和空白字符串
	 * - 确保至少有一个有效ID才发送
	 * - 批量删除提高效率
	 */
	public void sendWaypointsDelete(UUID submitPlayerId, List<String> waypointIds) {
		if (socket == null || !isConnected)
			return;
		if (waypointIds == null || waypointIds.isEmpty())
			return;
		try {
			List<String> ids = new ArrayList<>();
			for (String waypointId : waypointIds) {
				if (waypointId != null && !waypointId.isBlank()) {
					ids.add(waypointId);
				}
			}
			if (ids.isEmpty()) {
				return;
			}
			ProtocolPackets.WaypointsDeletePacket packet = new ProtocolPackets.WaypointsDeletePacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.waypointIds = ids;
			sendPacket(packet);
		} catch (Exception e) {
			LOGGER.error("Failed to send waypoints_delete to TeamViewRelay server: {}", e.getMessage());
		}
	}

	public void sendBattleMapObservation(UUID submitPlayerId, Map<String, Object> observation) {
		if (socket == null || !isConnected || submitPlayerId == null || observation == null || observation.isEmpty()) {
			return;
		}

		try {
			ProtocolPackets.BattleMapObservationPacket packet = new ProtocolPackets.BattleMapObservationPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.mode = normalizeNullableText(observation.get("mode"));
			packet.dimension = normalizeNullableText(observation.get("dimension"));
			packet.mapSize = toIntegerOrNull(observation.get("mapSize"));
			packet.anchorRow = toIntegerOrNull(observation.get("anchorRow"));
			packet.anchorCol = toIntegerOrNull(observation.get("anchorCol"));
			packet.snapshotObservedAt = toLongOrNull(observation.get("snapshotObservedAt"));
			packet.parsedAt = toLongOrNull(observation.get("parsedAt"));
			packet.candidates = copyValueList(observation.get("candidates"));
			packet.cells = copyValueList(observation.get("cells"));
			sendPacket(packet);
		} catch (Exception e) {
			LOGGER.error("Failed to send battle_map_observation: {}", e.getMessage());
		}
	}

	public void sendBattleChunkKeepalive(UUID submitPlayerId, Set<BattleChunkRefData> battleChunkRefs) {
		if (socket == null || !isConnected || submitPlayerId == null || battleChunkRefs == null || battleChunkRefs.isEmpty()) {
			return;
		}

		List<BattleChunkRefData> normalizedRefs = battleChunkRefs.stream()
				.filter(Objects::nonNull)
				.sorted()
				.toList();
		if (normalizedRefs.isEmpty()) {
			return;
		}
		for (int start = 0; start < normalizedRefs.size(); start += KEEPALIVE_MAX_ITEMS_PER_PACKET) {
			int end = Math.min(start + KEEPALIVE_MAX_ITEMS_PER_PACKET, normalizedRefs.size());
			ProtocolPackets.StateKeepalivePacket packet = new ProtocolPackets.StateKeepalivePacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.players = List.of();
			packet.entities = List.of();
			packet.battleChunks = new ArrayList<>(normalizedRefs.subList(start, end));
			sendPacket(packet);
		}
	}

	/**
	 * 发送实体死亡取消请求 - 路标关联实体保护
	 * 
	 * 功能说明：
	 * 当路标关联的实体即将死亡时，发送此请求阻止路标自动删除
	 * 用于保护重要的目标实体路标
	 * 
	 * 应用场景：
	 * - 玩家设置了追踪特定实体的路标
	 * - 该实体受到攻击但玩家希望保留路标
	 * - 防止因实体死亡导致的路标意外删除
	 * 
	 * 协议格式(waypoints_entity_death_cancel)：
	 * {
	 *   "type": "waypoints_entity_death_cancel",
	 *   "submitPlayerId": "发送者UUID",
	 *   "targetEntityIds": ["实体ID1", "实体ID2", ...]
	 * }
	 */
	public void sendWaypointEntityDeathCancel(UUID submitPlayerId, List<String> targetEntityIds) {
		if (socket == null || !isConnected)
			return;
		if (submitPlayerId == null || targetEntityIds == null || targetEntityIds.isEmpty())
			return;
		try {
			List<String> ids = new ArrayList<>();
			for (String entityId : targetEntityIds) {
				if (entityId != null && !entityId.isBlank()) {
					ids.add(entityId);
				}
			}
			if (ids.isEmpty()) {
				return;
			}
			ProtocolPackets.WaypointsEntityDeathCancelPacket packet = new ProtocolPackets.WaypointsEntityDeathCancelPacket();
			packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
			packet.targetEntityIds = ids;
			sendPacket(packet);
		} catch (Exception e) {
			LOGGER.error("Failed to send waypoints_entity_death_cancel to TeamViewRelay server: {}", e.getMessage());
		}
	}

	private void sendPacket(Object packet) {
		synchronized (outboundStateLock) {
			sendPacketUnlocked(packet);
		}
	}

	private void sendPacketUnlocked(Object packet) {
		if (socket == null || packet == null) {
			return;
		}
		byte[] payload = messageCodec.encode(packet);
		captureOutgoingBinaryPayload(payload);
		socket.send(payload);
	}

	public long getOutboundEpoch() {
		return outboundEpoch;
	}

	/** Direct typed protobuf path used exclusively by the entity worker. */
	public boolean sendTypedEntitiesPatchIfCurrent(
			long expectedEpoch,
			UUID submitPlayerId,
			EntityPatchView patch
	) {
		synchronized (outboundStateLock) {
			if (expectedEpoch != outboundEpoch || socket == null || !isConnected
					|| submitPlayerId == null || patch == null) {
				return false;
			}
			byte[] payload = messageCodec.encodeEntityPatch(submitPlayerId, patch);
			captureOutgoingBinaryPayload(payload);
			socket.send(payload);
			return true;
		}
	}

	/**
	 * Entity keepalive is intentionally infrequent. It may allocate UUID strings at the wire boundary,
	 * but never creates per-field maps and is outside the report hot path.
	 */
	public void sendTypedEntityKeepaliveIfNeeded(
			long expectedEpoch,
			UUID submitPlayerId,
			Collection<UUID> entityIds
	) {
		synchronized (outboundStateLock) {
			if (expectedEpoch != outboundEpoch || socket == null || !isConnected || submitPlayerId == null
					|| entityIds == null || entityIds.isEmpty()) {
				return;
			}
			List<String> batch = new ArrayList<>(KEEPALIVE_MAX_ITEMS_PER_PACKET);
			for (UUID id : entityIds) {
				if (id == null) continue;
				batch.add(id.toString());
				if (batch.size() >= KEEPALIVE_MAX_ITEMS_PER_PACKET) {
					sendTypedEntityKeepaliveBatchUnlocked(submitPlayerId, batch);
					batch = new ArrayList<>(KEEPALIVE_MAX_ITEMS_PER_PACKET);
				}
			}
			if (!batch.isEmpty()) sendTypedEntityKeepaliveBatchUnlocked(submitPlayerId, batch);
		}
	}

	public long getEntityKeepaliveIntervalMs() {
		return Math.max(1_000L, entityKeepaliveIntervalMs);
	}

	private void sendTypedEntityKeepaliveBatchUnlocked(UUID submitPlayerId, List<String> ids) {
		ProtocolPackets.StateKeepalivePacket packet = new ProtocolPackets.StateKeepalivePacket();
		packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
		packet.players = List.of();
		packet.entities = ids;
		packet.battleChunks = List.of();
		sendPacketUnlocked(packet);
	}

	private void handleTransportOpen(long attemptId, String negotiatedExtensions) {
		// 连接建立事件来自传输线程，这里只投递任务，避免直接跨线程改共享状态。
		enqueueMainThreadTask(() -> {
			if (!isCurrentConnectionAttempt(attemptId)) {
				LOGGER.debug("Ignoring stale WebSocket open callback for attempt {}", attemptId);
				return;
			}
			transportOpen = true;
			isConnected = false;
			connectionStage = ConnectionStage.CONNECTED_HANDSHAKING;
			lastConnectionError = "";
			reconnectAttemptsRemaining.set(maxReconnectAttempts);
			resetNegotiationState();
			resetTrafficStats();
			resetOutboundReportState();
			invalidateInboundRelayState();
			currentNegotiatedExtensions = negotiatedExtensions == null ? "" : negotiatedExtensions.trim();
			LOGGER.info("WebSocket connection opened to TeamViewRelay server");
			if (negotiatedExtensions != null && !negotiatedExtensions.isBlank()) {
				LOGGER.info("Negotiated WebSocket extensions: {}", negotiatedExtensions);
			}
			ensurePacketDumpWriter();
			sendHandshake(attemptId);
		});
	}

	private void handleTransportTextMessage(long attemptId, String text) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			LOGGER.debug("Ignoring stale text frame for attempt {}", attemptId);
			return;
		}
		captureIncomingTextPayload(text);
		LOGGER.warn("Ignoring text websocket frame, expected ProtoBuf binary frame");
	}

	private void handleTransportBinaryMessage(long attemptId, byte[] payload) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			LOGGER.debug("Ignoring stale binary frame for attempt {}", attemptId);
			return;
		}
		if (payload == null || payload.length == 0) {
			return;
		}
		captureIncomingBinaryPayload(payload);
		ProtocolPackets.DecodedInboundMessage decoded;
		try {
			decoded = messageCodec.decode(payload);
			if (decoded == null || decoded.type == null || decoded.type.isBlank()) {
				LOGGER.warn("Received invalid message envelope");
				return;
			}
		} catch (Exception e) {
			LOGGER.error("TeamViewRelay Network - Error decoding message, bytes={}: {}",
					payload.length, e.getMessage(), e);
			return;
		}
		enqueueMainThreadTask(() -> {
			if (!isCurrentConnectionAttempt(attemptId)) {
				LOGGER.debug("Ignoring stale queued binary frame for attempt {}", attemptId);
				return;
			}
			processDecodedMessage(attemptId, decoded);
		});
	}

	/**
	 * 处理服务端完整消息 - 核心下行消息分发器
	 * 
	 * 消息类型分类处理：
	 * 
	 * 1. 握手确认(handshake_ack)：
	 *    - 处理服务端协议版本协商
	 *    - 获取服务端能力支持情况
	 *    - 初始化连接参数
	 * 
	 * 2. 全量快照(snapshot_full)：
	 *    - 接收服务端完整的玩家/实体/路标数据
	 *    - 替换本地缓存实现同步
	 *    - 用于初始同步或强制刷新
	 * 
	 * 3. 增量补丁(patch)：
	 *    - 接收服务端发送的差分更新
	 *    - 高效更新本地缓存
	 *    - 支持upsert(更新插入)和delete(删除)操作
	 * 
	 * 4. 摘要校验(digest)：
	 *    - 接收服务端数据摘要哈希
	 *    - 对比本地数据一致性
	 *    - 发现不一致时请求重同步
	 * 
	 * 5. 刷新请求(refresh_req)：
	 *    - 服务端要求客户端刷新特定数据
	 *    - 标记待刷新的玩家/实体ID
	 *    - 在下次更新时强制发送完整数据
	 * 
	 * 6. 路标相关消息：
	 *    - waypoints_update: 接收新的路标数据
	 *    - waypoints_delete: 处理路标删除通知
	 * 
	 * 通用处理：统一的错误处理和日志记录。
	 */
	private void processDecodedMessage(long attemptId, ProtocolPackets.DecodedInboundMessage decoded) {
		try {
			Objects.requireNonNull(decoded, "decoded");
			if (!isCurrentConnectionAttempt(attemptId)) {
				LOGGER.debug("Ignoring stale inbound message for attempt {}", attemptId);
				return;
			}

			if ("handshake_ack".equals(decoded.type)) {
				ProtocolPackets.HandshakeAckInboundPacket packet =
						(ProtocolPackets.HandshakeAckInboundPacket) decoded.packet;
				handleHandshakeAck(attemptId, packet);
				return;
			}

			if ("snapshot_full".equals(decoded.type)) {
				ProtocolPackets.SnapshotFullInboundPacket packet =
						(ProtocolPackets.SnapshotFullInboundPacket) decoded.packet;
				applySnapshot(packet);
				return;
			}

			if ("patch".equals(decoded.type)) {
				ProtocolPackets.PatchInboundPacket packet =
						(ProtocolPackets.PatchInboundPacket) decoded.packet;
				applyPatch(packet);
				return;
			}

			if ("digest".equals(decoded.type)) {
				ProtocolPackets.DigestInboundPacket packet =
						(ProtocolPackets.DigestInboundPacket) decoded.packet;
				handleDigest(packet);
				return;
			}

			if ("refresh_req".equals(decoded.type)) {
				ProtocolPackets.RefreshReqInboundPacket packet =
						(ProtocolPackets.RefreshReqInboundPacket) decoded.packet;
				handleRefreshRequest(packet);
				return;
			}

			if ("report_rate_hint".equals(decoded.type)) {
				ProtocolPackets.ReportRateHintInboundPacket packet =
						(ProtocolPackets.ReportRateHintInboundPacket) decoded.packet;
				handleReportRateHint(packet);
				return;
			}

			if ("tab_history_digest".equals(decoded.type)) {
				handleTabHistoryDigest((ProtocolPackets.TabHistoryDigestInboundPacket) decoded.packet);
				return;
			}

			if ("tab_history_sync_chunk".equals(decoded.type)) {
				handleTabHistorySyncChunk((ProtocolPackets.TabHistorySyncChunkInboundPacket) decoded.packet);
				return;
			}

			if ("tab_history_lookup_chunk".equals(decoded.type)) {
				handleTabHistoryLookupChunk((ProtocolPackets.TabHistoryLookupChunkInboundPacket) decoded.packet);
				return;
			}

			if ("player_directory_lookup_chunk".equals(decoded.type)) {
				handlePlayerDirectoryLookupChunk(
						(ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket) decoded.packet);
				return;
			}

			if ("player_relation_query_chunk".equals(decoded.type)) {
				handlePlayerRelationQueryChunk(
						(ProtocolPackets.PlayerRelationQueryChunkInboundPacket) decoded.packet);
				return;
			}

		} catch (Exception e) {
			LOGGER.error(
				"TeamViewRelay Network - Error applying decoded message: {}, type={}",
				e.getMessage(),
				decoded == null ? "unknown" : decoded.type,
				e
			);
		}
	}

	/**
	 * 应用全量快照数据 - 数据同步核心方法
	 * 
	 * 快照应用场景：
	 * 1. 初始连接后的首次数据同步
	 * 2. 强制重同步请求的响应
	 * 3. 协议版本升级后的数据重建
	 * 4. 检测到数据不一致时的恢复
	 * 
	 * 处理的数据类型：
	 * 
	 * 玩家数据(players):
	 * - 解析并替换所有远程玩家信息
	 * - 更新玩家位置缓存
	 * - 同步玩家维度和名称信息
	 * 
	 * 实体数据(entities):
	 * - 替换整个实体缓存
	 * - 包含怪物、物品、载具等世界实体
	 * - 更新实体位置和状态信息
	 * 
	 * 路标数据(waypoints):
	 * - 清空现有路标缓存
	 * - 解析新的路标数据
	 * - 通知监听器路标更新
	 * 
	 * 玩家标记(playerMarks):
	 * - 更新玩家队伍归属信息
	 * - 同步颜色标记和标签设置
	 * 
	 * 设计特点：
	 * - 完整替换而非增量更新
	 * - 确保数据的完整性和一致性
	 * - 适用于需要完全同步的场景
	 */
	private void applySnapshot(ProtocolPackets.SnapshotFullInboundPacket packet) {
		if (packet == null) {
			return;
		}
		boolean remotePlayersChanged = false;
		boolean lastSeenPlayersChanged = false;

		Map<String, Object> players = objectMap(packet.players);
		if (packet.players != null) {
			Map<UUID, Map<String, Object>> previousPlayerData = Map.copyOf(remotePlayerDataCache);
			Map<UUID, RemotePlayerInfo> latestRemotePlayers = parseRemotePlayers(players, true);
			remotePlayersChanged = !remotePlayers.equals(latestRemotePlayers)
					|| !previousPlayerData.equals(remotePlayerDataCache);
			reconcileRemotePlayers(latestRemotePlayers);
		}

		Map<String, Object> entities = objectMap(packet.entities);
		if (packet.entities != null) {
			replaceEntityCache(entities);
		}

		Map<String, Object> waypoints = objectMap(packet.waypoints);
		if (packet.waypoints != null) {
			remoteWaypointDataCache.clear();
			Map<String, SharedWaypointInfo> receivedWaypoints = parseWaypointsFromObject(waypoints);
			if (!receivedWaypoints.isEmpty()) {
				notifyWaypointsReceived(receivedWaypoints);
			}
		}

		if (packet.battleChunks != null) {
			remoteBattleChunkDataCache.clear();
			mergeBattleChunkEntries(packet.battleChunks);
		}

		Map<String, Object> playerMarks = objectMap(packet.playerMarks);
		if (packet.playerMarks != null) {
			replacePlayerMarks(playerMarks);
		}

		if (packet.lastSeenPlayers != null) {
			lastSeenPlayersChanged = replaceLastSeenPlayers(objectMap(packet.lastSeenPlayers));
		}

		if (remotePlayersChanged) publishRemotePlayerSnapshots();
		if (lastSeenPlayersChanged) publishLastSeenPlayerSnapshots();
		inboundStateEpoch++;
	}

	/**
	 * 应用增量补丁数据 - 高效数据更新机制
	 * 
	 * 补丁协议优势：
	 * - 只传输变化的数据，大幅减少网络流量
	 * - 支持细粒度的增删改操作
	 * - 保持数据同步的同时提升性能
	 * 
	 * 支持的操作类型：
	 * 
	 * 删除操作(delete):
	 * - 从缓存中移除指定ID的记录
	 * - 清理相关的快照和位置数据
	 * - 释放内存资源
	 * 
	 * 更新插入操作(upsert):
	 * - 对于新记录：执行插入操作
	 * - 对于现有记录：执行更新操作
	 * - 智能合并字段变更
	 * 
	 * 处理的数据类别：
	 * 
	 * 玩家补丁(players):
	 * - 删除离线或退出的玩家
	 * - 更新在线玩家的位置和状态
	 * - 维护玩家缓存的一致性
	 * 
	 * 实体补丁(entities):
	 * - 处理实体生成、移动、销毁
	 * - 更新实体属性和位置信息
	 * - 保持世界状态同步
	 * 
	 * 路标补丁(waypoints):
	 * - 添加新的路标记录
	 * - 删除被移除的路标
	 * - 通知UI层更新显示
	 * 
	 * 玩家标记补丁(playerMarks):
	 * - 更新队伍归属关系
	 * - 修改颜色标记设置
	 * - 同步玩家分组信息
	 * 
	 * 协议格式示例：
	 * {
	 *   "players": {
	 *     "delete": ["玩家ID1", "玩家ID2"],
	 *     "upsert": {
	 *       "玩家ID3": {"x": 100, "y": 64, "z": 200}
	 *     }
	 *   }
	 * }
	 */
	private void applyPatch(ProtocolPackets.PatchInboundPacket packet) {
		if (packet == null) {
			return;
		}
		boolean remotePlayersChanged = false;
		boolean lastSeenPlayersChanged = false;

		Map<String, Object> playersPatch = objectMap(packet.players);
		if (!playersPatch.isEmpty()) {
			for (Object idValue : objectList(playersPatch.get("delete"))) {
				try {
					String playerIdRaw = String.valueOf(idValue);
					UUID playerId = UUID.fromString(playerIdRaw);
					remotePlayersChanged |= remotePlayers.remove(playerId) != null;
					remotePlayerDataCache.remove(playerId);
				} catch (Exception ignored) {
				}
			}
			Map<String, Object> upsert = objectMap(playersPatch.get("upsert"));
			if (!upsert.isEmpty()) {
				PlayerPatchChange change = applyPlayerPatchUpserts(upsert);
				remotePlayersChanged |= change.remotePlayersChanged();
				lastSeenPlayersChanged |= change.lastSeenPlayersChanged();
			}
		}

		Map<String, Object> entitiesPatch = objectMap(packet.entities);
		if (!entitiesPatch.isEmpty()) {
			for (Object idValue : objectList(entitiesPatch.get("delete"))) {
				String entityId = idValue == null ? null : String.valueOf(idValue);
				if (entityId != null && !entityId.isBlank()) {
					remoteEntityDataCache.remove(entityId);
				}
			}
			Map<String, Object> upsert = objectMap(entitiesPatch.get("upsert"));
			if (!upsert.isEmpty()) {
				mergeEntityPatchUpsert(upsert);
			}
		}

		Map<String, Object> waypointPatch = objectMap(packet.waypoints);
		if (!waypointPatch.isEmpty()) {
			List<String> deleteIds = new ArrayList<>();
			for (Object idValue : objectList(waypointPatch.get("delete"))) {
				String id = idValue == null ? null : String.valueOf(idValue);
				if (id != null && !id.isBlank()) {
					remoteWaypointDataCache.remove(id);
					deleteIds.add(id);
				}
			}
			if (!deleteIds.isEmpty()) {
				notifyWaypointsDeleted(deleteIds);
			}
			Map<String, Object> upsert = objectMap(waypointPatch.get("upsert"));
			if (!upsert.isEmpty()) {
				Map<String, SharedWaypointInfo> upserts = parseWaypointsFromObject(upsert);
				if (!upserts.isEmpty()) {
					notifyWaypointsReceived(upserts);
				}
			}
		}

		if (packet.battleChunks != null) {
			applyBattleChunkPatch(packet.battleChunks);
		}

		Map<String, Object> playerMarks = objectMap(packet.playerMarks);
		if (!playerMarks.isEmpty()) {
			if (playerMarks.containsKey("upsert") || playerMarks.containsKey("delete")) {
				applyPlayerMarksPatch(playerMarks);
			} else {
				replacePlayerMarks(playerMarks);
			}
		}

		Map<String, Object> lastSeenPatch = objectMap(packet.lastSeenPlayers);
		if (!lastSeenPatch.isEmpty()) {
			lastSeenPlayersChanged |= applyLastSeenPlayerPatch(lastSeenPatch);
		}

		if (remotePlayersChanged) publishRemotePlayerSnapshots();
		if (lastSeenPlayersChanged) publishLastSeenPlayerSnapshots();
		inboundStateEpoch++;
	}

	private void replacePlayerMarks(Map<String, Object> marks) {
		remotePlayerMarks.clear();
		mergePlayerMarkUpserts(marks);
	}

	private void applyPlayerMarksPatch(Map<String, Object> patch) {
		for (Object idValue : objectList(patch.get("delete"))) {
			String normalized = normalizePlayerMarkId(idValue == null ? null : String.valueOf(idValue));
			if (normalized != null) {
				remotePlayerMarks.remove(normalized);
			}
		}
		Map<String, Object> upsert = objectMap(patch.get("upsert"));
		if (!upsert.isEmpty()) {
			mergePlayerMarkUpserts(upsert);
		}
	}

	private void mergePlayerMarkUpserts(Map<String, Object> upserts) {
		for (Map.Entry<String, Object> entry : upserts.entrySet()) {
			try {
				Map<String, Object> mark = extractDataMap(objectMap(entry.getValue()));
				if (mark.isEmpty()) {
					continue;
				}
				String normalizedId = normalizePlayerMarkId(entry.getKey());
				if (normalizedId == null) {
					continue;
				}
				String team = normalizeMarkTeam(normalizeNullableText(mark.get("team")));
				Integer color = parseColorValue(mark.get("color"));
				String label = normalizeNullableText(mark.get("label"));
				if (label != null && label.isBlank()) {
					label = null;
				}
				String source = normalizeNullableText(mark.get("source"));
				if (source != null) source = source.trim().toLowerCase(java.util.Locale.ROOT);
				remotePlayerMarks.put(normalizedId, new PlayerMarkState(team, color, label, source));
			} catch (Exception e) {
				LOGGER.warn("Failed to parse player mark {}: {}", entry.getKey(), e.getMessage());
			}
		}
	}

	private String normalizePlayerMarkId(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value.trim()).toString().toLowerCase();
		} catch (Exception e) {
			return null;
		}
	}

	private String normalizeMarkTeam(String value) {
		if (value == null) {
			return "neutral";
		}
		String text = value.trim().toLowerCase();
		if ("friendly".equals(text) || "friend".equals(text) || "ally".equals(text) || "blue".equals(text)) {
			return "friendly";
		}
		if ("enemy".equals(text) || "hostile".equals(text) || "red".equals(text)) {
			return "enemy";
		}
		return "neutral";
	}

	private Integer parseColorValue(Object value) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof Number number) {
				return number.intValue();
			}
			String text = String.valueOf(value);
			if (text.isBlank()) {
				return null;
			}
			String normalized = text.trim();
			if (normalized.startsWith("#")) {
				String hex = normalized.substring(1);
				if (hex.length() == 6) {
					return (0xFF << 24) | Integer.parseInt(hex, 16);
				}
				if (hex.length() == 8) {
					return (int) Long.parseLong(hex, 16);
				}
			}
			if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
				return (int) Long.parseLong(normalized.substring(2), 16);
			}
			return (int) Long.parseLong(normalized, 16);
		} catch (Exception ignored) {
		}
		return null;
	}

	/**
	 * 处理数据摘要校验消息 - 数据一致性保障机制
	 * 
	 * 工作原理：
	 * 1. 服务端定期发送各类数据的SHA-1摘要哈希
	 * 2. 客户端计算本地对应数据的摘要哈希
	 * 3. 对比双方哈希值是否一致
	 * 4. 发现不一致时请求强制重同步
	 * 
	 * 校验的数据类型：
	 * - players: 玩家位置数据摘要
	 * - entities: 实体位置数据摘要
	 * - waypoints: 路标数据摘要
	 * 
	 * 防抖机制：
	 * - 3秒冷却时间防止频繁重同步请求
	 * - 避免网络拥塞和服务端压力
	 * 
	 * 重同步触发条件：
	 * - 任一类型数据哈希不匹配
	 * - 超过冷却时间限制
	 * - 通过resync_req消息请求全量数据
	 * 
	 * 优势：
	 * - 及早发现数据不同步问题
	 * - 自动恢复数据一致性
	 * - 减少手动干预需求
	 */
	private void handleDigest(ProtocolPackets.DigestInboundPacket packet) {
		if (packet == null || packet.hashes == null) {
			return;
		}

		DigestStateSnapshot snapshot = new DigestStateSnapshot(
				++digestRequestSequence,
				inboundStateEpoch,
				System.nanoTime(),
				new HashMap<>(packet.hashes),
				new HashMap<>(remotePlayerDataCache),
				new HashMap<>(remoteEntityDataCache),
				new HashMap<>(remoteWaypointDataCache),
				new HashMap<>(remoteBattleChunkDataCache),
				new HashMap<>(lastSeenPlayerDataCache),
				serverProtocolVersion
		);
		submitDigestSnapshot(snapshot);
	}

	private void submitDigestSnapshot(DigestStateSnapshot snapshot) {
		synchronized (digestTaskLock) {
			if (digestTaskRunning) {
				pendingDigestSnapshot = snapshot;
				digestMergedRequestCount++;
				return;
			}
			digestTaskRunning = true;
		}
		try {
			digestExecutor.execute(() -> drainDigestSnapshots(snapshot));
		} catch (RejectedExecutionException e) {
			synchronized (digestTaskLock) {
				digestTaskRunning = false;
				pendingDigestSnapshot = null;
			}
			LOGGER.warn("Digest executor is unavailable: {}", e.getMessage());
		}
	}

	private void drainDigestSnapshots(DigestStateSnapshot first) {
		DigestStateSnapshot current = first;
		while (current != null) {
			DigestComputation computation = computeDigest(current);
			enqueueMainThreadTask(() -> applyDigestComputation(computation));
			synchronized (digestTaskLock) {
				current = pendingDigestSnapshot;
				pendingDigestSnapshot = null;
				if (current == null) {
					digestTaskRunning = false;
				}
			}
		}
	}

	private DigestComputation computeDigest(DigestStateSnapshot snapshot) {
		long startedAt = System.nanoTime();
		String localPlayerHash = computePlayersDigest(snapshot.players());
		String localEntityHash = stateDigest(snapshot.entities());
		String localWaypointHash = stateDigest(snapshot.waypoints());
		BattleChunkDigestValues battleChunkDigests = computeBattleChunkDigests(
				snapshot.battleChunks(), snapshot.protocolVersion());
		String localLastSeenPlayersHash = stateDigest(snapshot.lastSeenPlayers());
		Map<String, String> server = snapshot.serverHashes();
		List<String> mismatchedScopes = new ArrayList<>();
		if (!Objects.equals(server.get("players"), localPlayerHash)) {
			mismatchedScopes.add("players");
		}
		if (!Objects.equals(server.get("entities"), localEntityHash)) {
			mismatchedScopes.add("entities");
		}
		if (!Objects.equals(server.get("waypoints"), localWaypointHash)) {
			mismatchedScopes.add("waypoints");
		}
		String serverBattleChunkHash = server.get("battleChunks");
		if (serverBattleChunkHash != null
				&& !Objects.equals(serverBattleChunkHash, battleChunkDigests.official())
				&& !Objects.equals(serverBattleChunkHash, battleChunkDigests.compatibility())) {
			mismatchedScopes.add("battleChunks");
		}
		String serverLastSeenPlayersHash = server.get("lastSeenPlayers");
		if (serverLastSeenPlayersHash != null
				&& !Objects.equals(serverLastSeenPlayersHash, localLastSeenPlayersHash)) {
			mismatchedScopes.add("lastSeenPlayers");
		}
		long finishedAt = System.nanoTime();
		return new DigestComputation(
				snapshot,
				List.copyOf(mismatchedScopes),
				localPlayerHash,
				localEntityHash,
				localWaypointHash,
				battleChunkDigests,
				localLastSeenPlayersHash,
				startedAt - snapshot.queuedAtNanos(),
				finishedAt - startedAt
		);
	}

	private void applyDigestComputation(DigestComputation computation) {
		DigestStateSnapshot snapshot = computation.snapshot();
		if (snapshot.sequence() != digestRequestSequence
				|| snapshot.inboundStateEpoch() != inboundStateEpoch) {
			LOGGER.debug("Discarded stale digest result sequence={} currentSequence={} stateEpoch={} currentStateEpoch={}",
					snapshot.sequence(), digestRequestSequence,
					snapshot.inboundStateEpoch(), inboundStateEpoch);
			return;
		}
		long merged;
		synchronized (digestTaskLock) {
			merged = digestMergedRequestCount;
		}
		LOGGER.debug("Digest task completed sequence={} queueMs={} computeMs={} coalescedTotal={}",
				snapshot.sequence(), computation.queueNanos() / 1_000_000.0,
				computation.computeNanos() / 1_000_000.0, merged);
		if (computation.computeNanos() >= DIGEST_SLOW_TASK_WARN_NANOS) {
			LOGGER.warn("Slow TeamViewRelay digest computation: {} ms (queue={} ms, coalescedTotal={})",
					computation.computeNanos() / 1_000_000.0,
					computation.queueNanos() / 1_000_000.0, merged);
		}
		if (computation.mismatchedScopes().isEmpty()) {
			return;
		}

		Map<String, String> server = snapshot.serverHashes();
		BattleChunkDigestValues battleChunkDigests = computation.battleChunkDigests();
		LOGGER.warn(
				"Digest mismatch detected scopes={} server={{players={}, entities={}, waypoints={}, battleChunks={}, lastSeenPlayers={}}} local={{players={}, entities={}, waypoints={}, battleChunks={}, battleChunksCompatibility={}, lastSeenPlayers={}}} battleChunkDigestContract={}",
				computation.mismatchedScopes(),
				server.get("players"),
				server.get("entities"),
				server.get("waypoints"),
				server.get("battleChunks"),
				server.get("lastSeenPlayers"),
				computation.localPlayerHash(),
				computation.localEntityHash(),
				computation.localWaypointHash(),
				battleChunkDigests.official(),
				battleChunkDigests.compatibility(),
				computation.localLastSeenPlayersHash(),
				battleChunkDigests.contract()
		);

		long now = System.currentTimeMillis();
		if (now - lastResyncRequestMs < RESYNC_COOLDOWN_MS) {
			return;
		}
		lastResyncRequestMs = now;
		sendResyncRequest("digest_mismatch");
	}

	private void sendResyncRequest(String reason) {
		if (socket == null || !isConnected) {
			return;
		}
		try {
			ProtocolPackets.ResyncReqPacket req = new ProtocolPackets.ResyncReqPacket();
			req.reason = reason;
			UUID localPlayerId = runtimeGateway.getLocalPlayerId();
			if (localPlayerId != null) {
				req.submitPlayerId = UuidBinaryCodec.toBytes(localPlayerId);
			}
			sendPacket(req);
		} catch (Exception e) {
			LOGGER.warn("Failed to send resync request: {}", e.getMessage());
		}
	}

	/**
	 * WebSocket连接关闭回调 - 连接终止处理
	 * 
	 * 状态码含义：
	 * - 1000: 正常关闭(客户端主动断开)
	 * - 其他: 异常关闭(网络问题、服务端关闭等)
	 * 
	 * 处理流程：
	 * 1. 通过任务队列切换到主线程
	 * 2. 更新连接状态为断开
	 * 3. 记录关闭原因(异常关闭时)
	 * 4. 重置协议协商状态
	 * 5. 清空本地快照缓存
	 * 6. 通知所有监听器连接已断开
	 * 7. 记录断开日志
	 * 8. 如需重连则调度重连任务
	 * 
	 * 线程安全：与onOpen保持一致的处理模式
	 */
	private void handleTransportClosed(long attemptId, int statusCode, String reason) {
		// 关闭事件也切回主线程，统一处理状态重置与重连调度。
		enqueueMainThreadTask(() -> {
			if (!isCurrentConnectionAttempt(attemptId)) {
				LOGGER.debug("Ignoring stale WebSocket close callback for attempt {}", attemptId);
				return;
			}
			isConnected = false;
			transportOpen = false;
			connectionStage = ConnectionStage.DISCONNECTED;
			invalidateConnectionAttempt(attemptId);
			socket = null;
			if (statusCode == 1008) {
				shouldReconnect = false;
				reconnectSuppressedForVersionMismatch = true;
				connectionStage = ConnectionStage.FAILED;
			}
			if (statusCode != 1000) {
				lastConnectionError = "WebSocket closed (" + statusCode + "): "
						+ (reason == null || reason.isBlank() ? "unknown reason" : reason);
				if (statusCode != 1008) {
					connectionStage = ConnectionStage.FAILED;
				}
			} else {
				lastConnectionError = "";
			}
			closePacketDumpWriterQuietly();
			currentNegotiatedExtensions = "";
			resetNegotiationState();
			resetOutboundReportState();
			invalidateInboundRelayState();
			notifyConnectionStatusChanged(false);
			if (statusCode == 1000) {
				LOGGER.info("Disconnected from TeamViewRelay server via normal close. Status: {}, Reason: {}", statusCode, reason);
			} else {
				LOGGER.warn("Disconnected from TeamViewRelay server via peer/application close. Status: {}, Reason: {}", statusCode, reason);
			}
			if (shouldReconnect && !reconnectSuppressedForVersionMismatch) {
				scheduleReconnect();
			}
		});
	}

	/**
	 * WebSocket连接失败回调 - 网络异常处理
	 * 
	 * 错误类型包括：
	 * - 网络连接超时
	 * - DNS解析失败
	 * - SSL/TLS握手失败
	 * - 代理配置错误
	 * - 服务端拒绝连接
	 * 
	 * 处理策略：
	 * 1. 通过任务队列确保主线程处理
	 * 2. 记录详细的错误信息
	 * 3. 设置连接状态为断开
	 * 4. 格式化错误原因便于诊断
	 * 5. 重置所有网络状态
	 * 6. 通知监听器连接失败
	 * 7. 如需重连则启动重连机制
	 * 
	 * 错误信息格式化：
	 * - 提取异常链中的关键信息
	 * - 合并多个异常的原因描述
	 * - 提供清晰的错误诊断信息
	 */
	private void handleTransportFailure(long attemptId, Throwable error) {
		// 失败事件在网络线程触发，这里只入队，保证状态清理和通知时序一致。
		enqueueMainThreadTask(() -> {
			if (!isCurrentConnectionAttempt(attemptId)) {
				LOGGER.debug("Ignoring stale WebSocket failure callback for attempt {}", attemptId);
				return;
			}
			String failureReason = formatThrowableReason(error);
			String failureCategory = classifyTransportFailure(error);
			LOGGER.error("TeamViewRelay transport failure [{}]: {}", failureCategory, failureReason);
			isConnected = false;
			transportOpen = false;
			connectionStage = ConnectionStage.FAILED;
			invalidateConnectionAttempt(attemptId);
			socket = null;
			lastConnectionError = failureReason;
			closePacketDumpWriterQuietly();
			currentNegotiatedExtensions = "";
			resetNegotiationState();
			resetOutboundReportState();
			invalidateInboundRelayState();
			notifyConnectionStatusChanged(false);
			if (shouldReconnect) {
				scheduleReconnect();
			}
		});
	}

	public static String getServerURL() {
		return configGateway != null ? configGateway.getServerURL() : "ws://localhost:8080/mc-client";
	}

	public static void setServerURL(String serverURL) {
		if (configGateway != null) {
			configGateway.setServerURL(serverURL);
		}
	}

		public static String getRoomCode() {
			return configGateway != null ? configGateway.getRoomCode() : "default";
		}

		public static void setRoomCode(String roomCode) {
			if (configGateway != null) {
				configGateway.setRoomCode(roomCode);
			}
		}

	public static boolean isUseSystemProxy() {
		return configGateway == null || configGateway.isUseSystemProxy();
	}

	public static void setUseSystemProxy(boolean useSystemProxy) {
		if (configGateway != null) {
			configGateway.setUseSystemProxy(useSystemProxy);
		}
	}

	public static boolean isAllowInsecureTls() {
		return configGateway != null && configGateway.isAllowInsecureTls();
	}

	public static void setAllowInsecureTls(boolean allowInsecureTls) {
		if (configGateway != null) {
			configGateway.setAllowInsecureTls(allowInsecureTls);
		}
	}

	public boolean isConnected() {
		return isConnected;
	}

	public ConnectionStage getConnectionStage() {
		return connectionStage;
	}

	public String getLastConnectionError() {
		return lastConnectionError;
	}

	public TrafficStatsSnapshot getTrafficStatsSnapshot() {
		synchronized (trafficStatsLock) {
			long now = System.currentTimeMillis();
			evictExpiredTrafficSamples(uploadTrafficSamples, now, true);
			evictExpiredTrafficSamples(downloadTrafficSamples, now, false);
			return new TrafficStatsSnapshot(
					uploadApplicationBytesTotal,
					downloadApplicationBytesTotal,
					uploadWireBytesTotal,
					downloadWireBytesTotal,
					isConnected ? computeWindowAverageBytesPerSecond(uploadApplicationBytesInWindow, now) : 0L,
					isConnected ? computeWindowAverageBytesPerSecond(downloadApplicationBytesInWindow, now) : 0L,
					isConnected ? computeWindowAverageBytesPerSecond(uploadWireBytesInWindow, now) : 0L,
					isConnected ? computeWindowAverageBytesPerSecond(downloadWireBytesInWindow, now) : 0L,
					isConnected);
		}
	}

	public void startPacketDumpCapture() {
		packetDumpActive = true;
		ensurePacketDumpWriter();
	}

	public void stopPacketDumpCapture() {
		packetDumpActive = false;
		closePacketDumpWriterQuietly();
	}

	public boolean isPacketDumpCaptureActive() {
		return packetDumpActive;
	}

	public String getPacketDumpCurrentPath() {
		return packetDumpCurrentPath;
	}

	public String getPacketDumpLastSavedPath() {
		return packetDumpLastSavedPath;
	}

	private void captureOutgoingBinaryPayload(byte[] payload) {
		if (payload == null || payload.length == 0) {
			return;
		}
		WebSocketCaptureWriter writer = getPacketDumpWriterIfEnabled();
		if (writer == null) {
			return;
		}
		try {
			writer.writeClientBinaryMessage(payload);
		} catch (Exception e) {
			LOGGER.warn("Failed to dump outgoing websocket payload: {}", e.getMessage());
			closePacketDumpWriterQuietly();
		}
	}

	private void captureIncomingBinaryPayload(byte[] payload) {
		if (payload == null || payload.length == 0) {
			return;
		}
		WebSocketCaptureWriter writer = getPacketDumpWriterIfEnabled();
		if (writer == null) {
			return;
		}
		try {
			writer.writeServerBinaryMessage(payload);
		} catch (Exception e) {
			LOGGER.warn("Failed to dump incoming websocket payload: {}", e.getMessage());
			closePacketDumpWriterQuietly();
		}
	}

	private void captureIncomingTextPayload(String text) {
		WebSocketCaptureWriter writer = getPacketDumpWriterIfEnabled();
		if (writer == null) {
			return;
		}
		try {
			writer.writeServerTextMessage(text);
		} catch (Exception e) {
			LOGGER.warn("Failed to dump incoming websocket text payload: {}", e.getMessage());
			closePacketDumpWriterQuietly();
		}
	}

	private WebSocketCaptureWriter getPacketDumpWriterIfEnabled() {
		if (!packetDumpActive) {
			closePacketDumpWriterQuietly();
			return null;
		}
		ensurePacketDumpWriter();
		return packetDumpWriter;
	}

	private void ensurePacketDumpWriter() {
		if (runtimeGateway == null || configGateway == null || !packetDumpActive || !isConnected) {
			return;
		}
		synchronized (packetDumpLock) {
			if (packetDumpWriter != null) {
				return;
			}
			try {
				packetDumpWriter = WebSocketCaptureWriter.open(
						runtimeGateway.getLogsDirectory(),
						configGateway.getServerURL(),
						configGateway.getRoomCode(),
						currentNegotiatedExtensions);
				packetDumpCurrentPath = String.valueOf(packetDumpWriter.getOutputPath());
				LOGGER.info("WebSocket packet dump started: {}", packetDumpWriter.getOutputPath());
			} catch (Exception e) {
				LOGGER.warn("Failed to open WebSocket packet dump writer: {}", e.getMessage());
				packetDumpWriter = null;
			}
		}
	}

	private void closePacketDumpWriterQuietly() {
		synchronized (packetDumpLock) {
			if (packetDumpWriter == null) {
				return;
			}
			try {
				packetDumpWriter.close();
				packetDumpLastSavedPath = String.valueOf(packetDumpWriter.getOutputPath());
				LOGGER.info("WebSocket packet dump saved: {}", packetDumpWriter.getOutputPath());
			} catch (Exception e) {
				LOGGER.warn("Failed to close WebSocket packet dump writer: {}", e.getMessage());
			} finally {
				packetDumpCurrentPath = "";
				packetDumpWriter = null;
			}
		}
	}

	private void handleTransportTrafficEvent(long attemptId, TransportTrafficEvent event) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			return;
		}
		if (event == null) {
			return;
		}
		recordTrafficEvent(event);
	}

	private void recordTrafficEvent(TransportTrafficEvent event) {
		synchronized (trafficStatsLock) {
			long now = System.currentTimeMillis();
			ArrayDeque<TrafficSample> targetSamples = event.direction() == TransportTrafficEvent.Direction.OUTBOUND
					? uploadTrafficSamples
					: downloadTrafficSamples;
			targetSamples.addLast(new TrafficSample(
					now,
					Math.max(0L, event.applicationPayloadBytes()),
					Math.max(0L, event.wireBytes())
			));
			if (event.direction() == TransportTrafficEvent.Direction.OUTBOUND) {
				uploadApplicationBytesTotal += Math.max(0L, event.applicationPayloadBytes());
				uploadWireBytesTotal += Math.max(0L, event.wireBytes());
				uploadApplicationBytesInWindow += Math.max(0L, event.applicationPayloadBytes());
				uploadWireBytesInWindow += Math.max(0L, event.wireBytes());
				evictExpiredTrafficSamples(uploadTrafficSamples, now, true);
			} else {
				downloadApplicationBytesTotal += Math.max(0L, event.applicationPayloadBytes());
				downloadWireBytesTotal += Math.max(0L, event.wireBytes());
				downloadApplicationBytesInWindow += Math.max(0L, event.applicationPayloadBytes());
				downloadWireBytesInWindow += Math.max(0L, event.wireBytes());
				evictExpiredTrafficSamples(downloadTrafficSamples, now, false);
			}
		}
	}

	private void evictExpiredTrafficSamples(ArrayDeque<TrafficSample> samples, long now, boolean upload) {
		long cutoff = now - TRAFFIC_RATE_WINDOW_MS;
		while (!samples.isEmpty() && samples.peekFirst().timestampMs <= cutoff) {
			TrafficSample expired = samples.removeFirst();
			if (upload) {
				uploadApplicationBytesInWindow -= expired.applicationBytes;
				uploadWireBytesInWindow -= expired.wireBytes;
			} else {
				downloadApplicationBytesInWindow -= expired.applicationBytes;
				downloadWireBytesInWindow -= expired.wireBytes;
			}
		}
		if (upload) {
			uploadApplicationBytesInWindow = Math.max(0L, uploadApplicationBytesInWindow);
			uploadWireBytesInWindow = Math.max(0L, uploadWireBytesInWindow);
		} else {
			downloadApplicationBytesInWindow = Math.max(0L, downloadApplicationBytesInWindow);
			downloadWireBytesInWindow = Math.max(0L, downloadWireBytesInWindow);
		}
	}

	private long computeWindowAverageBytesPerSecond(long bytesInWindow, long now) {
		if (bytesInWindow <= 0L) {
			return 0L;
		}
		long sessionAgeMs = trafficStatsSessionStartedAtMs > 0L ? now - trafficStatsSessionStartedAtMs : TRAFFIC_RATE_WINDOW_MS;
		long divisorMs = Math.max(1L, Math.min(TRAFFIC_RATE_WINDOW_MS, sessionAgeMs));
		return Math.round((double) bytesInWindow * 1_000.0D / (double) divisorMs);
	}

	private void resetTrafficStats() {
		synchronized (trafficStatsLock) {
			uploadApplicationBytesTotal = 0L;
			downloadApplicationBytesTotal = 0L;
			uploadWireBytesTotal = 0L;
			downloadWireBytesTotal = 0L;
			uploadApplicationBytesInWindow = 0L;
			downloadApplicationBytesInWindow = 0L;
			uploadWireBytesInWindow = 0L;
			downloadWireBytesInWindow = 0L;
			trafficStatsSessionStartedAtMs = System.currentTimeMillis();
			uploadTrafficSamples.clear();
			downloadTrafficSamples.clear();
		}
	}

	private synchronized long beginConnectionAttempt() {
		connectionAttemptSequence++;
		activeConnectionAttemptId = connectionAttemptSequence;
		synchronized (outboundStateLock) {
			outboundEpoch++;
		}
		handshakeSent = false;
		handshakeCompleted = false;
		connectionStage = ConnectionStage.CONNECTING;
		transportOpen = false;
		isConnected = false;
		lastConnectionError = "";
		return activeConnectionAttemptId;
	}

	private boolean isCurrentConnectionAttempt(long attemptId) {
		return attemptId > 0L && activeConnectionAttemptId == attemptId;
	}

	private synchronized void invalidateConnectionAttempt(long attemptId) {
		if (attemptId <= 0L || activeConnectionAttemptId != attemptId) {
			return;
		}
		activeConnectionAttemptId = 0L;
		handshakeSent = false;
		handshakeCompleted = false;
	}

	private int normalizeReconnectAttempts(int attempts) {
		return attempts < 0 ? UNLIMITED_RECONNECT_ATTEMPTS : attempts;
	}

	private long normalizeReconnectDelayMs(long delayMs) {
		return Math.max(1_000L, delayMs);
	}

	private void resetReconnectPolicy() {
		maxReconnectAttempts = UNLIMITED_RECONNECT_ATTEMPTS;
		reconnectAttemptsRemaining.set(UNLIMITED_RECONNECT_ATTEMPTS);
		reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;
	}

	static int claimReconnectAttempt(AtomicInteger remainingAttempts) {
		return remainingAttempts.getAndUpdate(value -> value > 0 ? value - 1 : value);
	}

	public Position3D getRemoteEntityPosition(String entityId, String expectedDimension) {
		if (entityId == null || entityId.isBlank()) {
			return null;
		}

		Map<String, Object> data = remoteEntityDataCache.get(entityId);
		if (data == null) {
			return null;
		}

		if (expectedDimension != null && !expectedDimension.isBlank()) {
			Object dimension = data.get("dimension");
			if (dimension == null || !expectedDimension.equals(String.valueOf(dimension))) {
				return null;
			}
		}

		Double x = getAsDouble(data.get("x"));
		Double y = getAsDouble(data.get("y"));
		Double z = getAsDouble(data.get("z"));
		if (x == null || y == null || z == null) {
			return null;
		}

		return new Position3D(x, y, z);
	}

	public Position3D getRemotePlayerPosition(String playerId, String playerName, String expectedDimension) {
		UUID expectedUuid = null;
		if (playerId != null && !playerId.isBlank()) {
			try {
				expectedUuid = UUID.fromString(playerId);
			} catch (IllegalArgumentException ignored) {
			}
		}

		if (expectedUuid != null) {
			RemotePlayerInfo info = remotePlayers.get(expectedUuid);
			if (isRemotePlayerMatch(info, playerName, expectedDimension)) {
				return info.position();
			}
		}

		for (RemotePlayerInfo info : remotePlayers.values()) {
			if (isRemotePlayerMatch(info, playerName, expectedDimension)) {
				return info.position();
			}
		}

		return null;
	}

	public String getPlayerMarkTeam(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		PlayerMarkState mark = remotePlayerMarks.get(playerId.toString().toLowerCase());
		return mark == null ? null : mark.team();
	}

	public PlayerMarkView getPlayerMark(UUID playerId) {
		if (playerId == null) return null;
		PlayerMarkState mark = remotePlayerMarks.get(playerId.toString().toLowerCase());
		if (mark == null) return null;
		return new PlayerMarkView(relationFromTeam(mark.team()), mark.source());
	}

	/** Returns the latest immutable, loader-neutral remote-player snapshot. */
	public List<RemotePlayerSnapshot> getRemotePlayerSnapshots() {
		return publishedRemotePlayerSnapshots;
	}

	/** Immutable online-player state for common rendering and integration scheduling. */
	public Map<UUID, RemotePlayerInfo> getRemotePlayerState() {
		return publishedRemotePlayerState;
	}

	public long getRemotePlayerStateGeneration() {
		return remotePlayerStateGeneration;
	}

	/** Returns the latest immutable authoritative offline-player snapshot. */
	public List<LastSeenPlayerSnapshot> getLastSeenPlayerSnapshots() {
		return publishedLastSeenPlayerSnapshots;
	}

	/** Immutable offline-player state for common rendering and integration scheduling. */
	public Map<UUID, LastSeenPlayerInfo> getLastSeenPlayerState() {
		return publishedLastSeenPlayerState;
	}

	public long getLastSeenPlayerStateGeneration() {
		return lastSeenPlayerStateGeneration;
	}

	private void publishRemotePlayerSnapshots() {
		Map<UUID, RemotePlayerInfo> state = Map.copyOf(remotePlayers);
		if (!state.equals(publishedRemotePlayerState)) {
			publishedRemotePlayerState = state;
			remotePlayerStateGeneration++;
		}
		List<RemotePlayerSnapshot> snapshots = new ArrayList<>();
		for (Map.Entry<UUID, RemotePlayerInfo> entry : state.entrySet()) {
			UUID playerId = entry.getKey();
			RemotePlayerInfo info = entry.getValue();
			Map<String, Object> data = remotePlayerDataCache.get(playerId);
			RemotePlayerSnapshot snapshot = buildRemotePlayerSnapshot(playerId, info, data);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		snapshots.sort(Comparator.comparing(snapshot -> snapshot.uuid().toString()));
		List<RemotePlayerSnapshot> nextSnapshots = List.copyOf(snapshots);
		if (!nextSnapshots.equals(publishedRemotePlayerSnapshots)) {
			publishedRemotePlayerSnapshots = nextSnapshots;
		}
	}

	private void publishLastSeenPlayerSnapshots() {
		Map<UUID, LastSeenPlayerInfo> state = Map.copyOf(lastSeenPlayers);
		if (state.equals(publishedLastSeenPlayerState)) return;
		publishedLastSeenPlayerState = state;
		lastSeenPlayerStateGeneration++;
		List<LastSeenPlayerSnapshot> snapshots = new ArrayList<>();
		for (LastSeenPlayerInfo player : state.values()) {
			if (player == null || publishedRemotePlayerState.containsKey(player.uuid())) continue;
			Position3D position = player.position();
			snapshots.add(new LastSeenPlayerSnapshot(
					player.uuid(), player.name(), player.dimension(),
					position.x(), position.y(), position.z(), player.lastSeenAtUtcMs(),
					player.positionObservedAtUtcMs(), player.offlineDetectedAtUtcMs()));
		}
		snapshots.sort(Comparator.comparing(snapshot -> snapshot.uuid().toString()));
		publishedLastSeenPlayerSnapshots = List.copyOf(snapshots);
		requestMissingTabHistory(state.keySet());
	}

	private RemotePlayerSnapshot buildRemotePlayerSnapshot(UUID playerId, RemotePlayerInfo info,
			Map<String, Object> data) {
		if (playerId == null || info == null || info.position() == null || data == null) {
			return null;
		}

		String playerName = info.name();
		String dimension = info.dimension();
		if (playerName == null || playerName.isBlank() || dimension == null || dimension.isBlank()) {
			return null;
		}

		Object reportedUuid = data.get("playerUUID");
		if (reportedUuid != null) {
			try {
				if (!playerId.equals(UUID.fromString(String.valueOf(reportedUuid)))) {
					return null;
				}
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}

		Double x = finite(info.position().x());
		Double y = finite(info.position().y());
		Double z = finite(info.position().z());
		Double velocityX = finite(getAsDouble(data.get("vx")));
		Double velocityY = finite(getAsDouble(data.get("vy")));
		Double velocityZ = finite(getAsDouble(data.get("vz")));
		Double health = finite(getAsDouble(data.get("health")));
		Double maxHealth = finite(getAsDouble(data.get("maxHealth")));
		Double armor = finite(getAsDouble(data.get("armor")));
		Double width = finite(getAsDouble(data.get("width")));
		Double height = finite(getAsDouble(data.get("height")));
		Boolean riding = getAsBoolean(data.get("isRiding"));
		if (x == null || y == null || z == null || velocityX == null || velocityY == null
				|| velocityZ == null || health == null || maxHealth == null || armor == null
				|| width == null || height == null || riding == null || health < 0
				|| maxHealth <= 0 || armor < 0 || width <= 0 || height <= 0) {
			return null;
		}

		PlayerMarkState mark = remotePlayerMarks.get(playerId.toString().toLowerCase());
		PlayerRelation relation = mark == null ? PlayerRelation.NEUTRAL : relationFromTeam(mark.team());
		RemotePlayerPositionSource positionSource = remotePlayerPositionSource(data);
		return new RemotePlayerSnapshot(playerId, playerName, dimension, x, y, z,
			velocityX, velocityY, velocityZ, health.floatValue(), maxHealth.floatValue(),
			armor.floatValue(), riding, width.floatValue(), height.floatValue(), relation, positionSource);
	}

	private RemotePlayerPositionSource remotePlayerPositionSource(Map<String, Object> data) {
		String sourceId = normalizeNullableText(data.get("positionSourceId"));
		String displayName = normalizeNullableText(data.get("positionSourceDisplayName"));
		Double resolution = finite(getAsDouble(data.get("positionResolution")));
		if (resolution != null && resolution <= 0.0) resolution = null;
		String rawKind = normalizeNullableText(data.get("positionSourceKind"));
		RemotePlayerPositionSourceKind kind = switch (rawKind == null ? "" : rawKind) {
			case "PLAYER_POSITION_SOURCE_KIND_SELF_REPORT" -> RemotePlayerPositionSourceKind.SELF_REPORT;
			case "PLAYER_POSITION_SOURCE_KIND_PLAYER_REPORT" -> RemotePlayerPositionSourceKind.PLAYER_REPORT;
			case "PLAYER_POSITION_SOURCE_KIND_EXTERNAL_SOURCE" -> RemotePlayerPositionSourceKind.EXTERNAL_SOURCE;
			default -> RemotePlayerPositionSourceKind.UNKNOWN;
		};
		return new RemotePlayerPositionSource(kind, sourceId, displayName, resolution);
	}

	private static PlayerRelation relationFromTeam(String team) {
		return switch (team == null ? "" : team) {
			case "friendly" -> PlayerRelation.FRIENDLY;
			case "enemy" -> PlayerRelation.ENEMY;
			default -> PlayerRelation.NEUTRAL;
		};
	}

	private Double finite(double value) {
		return Double.isFinite(value) ? value : null;
	}

	private Double finite(Double value) {
		return value != null && Double.isFinite(value) ? value : null;
	}

	private Boolean getAsBoolean(Object value) {
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (value instanceof String text) {
			if ("true".equalsIgnoreCase(text)) return true;
			if ("false".equalsIgnoreCase(text)) return false;
		}
		return null;
	}

	private boolean isRemotePlayerMatch(RemotePlayerInfo info, String expectedPlayerName, String expectedDimension) {
		if (info == null || info.position() == null || info.dimension() == null) {
			return false;
		}

		if (expectedDimension != null && !expectedDimension.isBlank()) {
			String actualDimension = info.dimension();
			if (!expectedDimension.equals(actualDimension)) {
				return false;
			}
		}

		if (expectedPlayerName == null || expectedPlayerName.isBlank()) {
			return true;
		}

		String actualName = info.name();
		return actualName != null && actualName.equalsIgnoreCase(expectedPlayerName);
	}

	private String classifyTransportFailure(Throwable throwable) {
		if (throwable == null) {
			return "unknown";
		}

		Throwable current = throwable;
		int depth = 0;
		while (current != null && depth < 6) {
			String message = current.getMessage();
			if (message != null && message.contains("websocket_read_timeout_after_upgrade")) {
				return "read-timeout";
			}
			current = current.getCause();
			depth++;
		}
		return "transport-error";
	}

	private String formatThrowableReason(Throwable throwable) {
		if (throwable == null) {
			return "Unknown error";
		}

		StringBuilder details = new StringBuilder();
		Throwable current = throwable;
		int depth = 0;
		while (current != null && depth < 6) {
			String message = current.getMessage();
			String type = current.getClass().getSimpleName();
			if (message != null && !message.isBlank()) {
				if (details.length() > 0) {
					details.append(" | caused by: ");
				}
				details.append(type).append(": ").append(message.trim());
			}
			current = current.getCause();
			depth++;
		}

		if (details.length() > 0) {
			return details.toString();
		}

		String fallback = throwable.toString();
		if (fallback != null && !fallback.isBlank()) {
			return fallback;
		}
		return throwable.getClass().getSimpleName();
	}

	private String getCurrentDimension() {
		return runtimeGateway.getCurrentDimensionId();
	}

	/**
	 * 发送握手消息 - 协议协商初始化
	 * 
	 * 握手协议格式：
	 * {
	 *   "type": "handshake",
	 *   "networkProtocolVersion": "客户端协议版本",
	 *   "localProgramVersion": "客户端程序版本",
	 *   "roomCode": "房间代码",
	 *   "submitPlayerId": "玩家UUID"
	 * }
	 * 
	 * 协商目的：
	 * 1. 向服务端声明客户端能力
	 * 2. 协商双方支持的协议版本
	 * 3. 确认是否支持增量更新功能
	 * 4. 建立房间归属关系
	 * 5. 交换客户端标识信息
	 * 
	 * 服务端响应：handshake_ack消息确认协商结果
	 */
	private void sendHandshake(long attemptId) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			LOGGER.debug("Ignoring handshake send for stale attempt {}", attemptId);
			return;
		}
		if (socket == null || !transportOpen || connectionStage != ConnectionStage.CONNECTED_HANDSHAKING) {
			return;
		}
		if (handshakeSent) {
			LOGGER.warn("Ignoring duplicate handshake send for attempt {}", attemptId);
			return;
		}

		try {
			handshakeSent = true;
			ProtocolPackets.HandshakePacket handshake = new ProtocolPackets.HandshakePacket();
			handshake.networkProtocolVersion = runtimeGateway.getClientProtocolVersion();
			handshake.minimumCompatibleNetworkProtocolVersion = runtimeGateway.getClientMinCompatibleProtocolVersion();
			handshake.localProgramVersion = runtimeGateway.getClientProgramVersion();
			handshake.roomCode = getRoomCode();
			handshake.preferredReportIntervalTicks = configGateway != null ? configGateway.getUpdateIntervalTicks() : 10;
			handshake.minReportIntervalTicks = 1;
			handshake.maxReportIntervalTicks = 1000;
			UUID localPlayerId = runtimeGateway.getLocalPlayerId();
			if (localPlayerId != null) {
				handshake.submitPlayerId = UuidBinaryCodec.toBytes(localPlayerId);
			}

			sendPacket(handshake);
			LOGGER.info("Sent handshake message for attempt {}", attemptId);
		} catch (Exception e) {
			LOGGER.error("Failed to send handshake message: {}", e.getMessage());
		}
	}

	/**
	 * 处理握手确认消息 - 协议协商完成
	 * 
	 * 从服务端响应中提取的关键信息：
	 * 
	 * 1. 协议版本兼容性：
	 *    - serverProtocolVersion: 服务端协议版本
	 *    - 用于后续功能兼容性判断
	 * 
	 * 2. 数据一致性配置：
	 *    - digestIntervalSec: 摘要校验间隔时间
	 *    - 控制数据同步频率
	 * 
	 * 3. 初始状态同步：
	 *    - 下发服务端广播频率与建议上报间隔
	 *    - 作为后续运行时拥塞协商基准
	 * 
	 * 4. 程序版本信息：
	 *    - serverProgramVersion: 服务端程序版本
	 *    - 用于调试和版本对比
	 */
	private void handleHandshakeAck(long attemptId, ProtocolPackets.HandshakeAckInboundPacket packet) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			LOGGER.debug("Ignoring stale handshake ack for attempt {}", attemptId);
			return;
		}
		if (packet == null) {
			return;
		}
		if (handshakeCompleted) {
			LOGGER.warn("Ignoring duplicate handshake ack for attempt {}", attemptId);
			return;
		}

		serverProtocolVersion = readProtocolVersionFromHandshakeAck(packet);
		serverProgramVersion = readProgramVersionFromHandshakeAck(packet);

		if (!Boolean.TRUE.equals(packet.ready)) {
			String rejectReason = extractHandshakeRejectReason(packet);
			rejectForVersionIncompatibility(attemptId, "服务端拒绝握手: " + rejectReason);
			return;
		}

		if (!protocolAtLeast(serverProtocolVersion, runtimeGateway.getClientMinCompatibleProtocolVersion())) {
			rejectForVersionIncompatibility(
					attemptId,
					"版本不兼容: 服务端协议 " + serverProtocolVersion
							+ " 低于客户端最低要求 " + runtimeGateway.getClientMinCompatibleProtocolVersion());
			return;
		}

		handshakeCompleted = true;
		isConnected = true;
		connectionStage = ConnectionStage.CONNECTED;
		notifyConnectionStatusChanged(true);

		digestIntervalSec = packet.digestIntervalSec != null ? packet.digestIntervalSec : 10;
		serverBroadcastHz = packet.broadcastHz != null ? packet.broadcastHz : 20.0;
		if (packet.reportIntervalTicks != null && packet.reportIntervalTicks > 0) {
			negotiatedReportIntervalTicks = packet.reportIntervalTicks;
		}
		initializeExternalRelations(packet);

		int advertisedPlayerTimeoutSec = packet.playerTimeoutSec != null && packet.playerTimeoutSec > 0
				? packet.playerTimeoutSec
				: 0;
		int advertisedEntityTimeoutSec = packet.entityTimeoutSec != null && packet.entityTimeoutSec > 0
				? packet.entityTimeoutSec
				: 0;
		if (advertisedPlayerTimeoutSec > 0) {
			playerKeepaliveIntervalMs = calculateKeepaliveIntervalMs(advertisedPlayerTimeoutSec);
		}
		if (advertisedEntityTimeoutSec > 0) {
			entityKeepaliveIntervalMs = calculateKeepaliveIntervalMs(advertisedEntityTimeoutSec);
		}
		int advertisedBattleChunkTimeoutSec = packet.battleChunkTimeoutSec != null && packet.battleChunkTimeoutSec > 0
				? packet.battleChunkTimeoutSec
				: 0;
		if (advertisedBattleChunkTimeoutSec > 0) {
			battleChunkKeepaliveIntervalMs = calculateKeepaliveIntervalMs(advertisedBattleChunkTimeoutSec);
		}

		initializeTabHistory(packet);

		LOGGER.info(
				"Handshake completed: protocol={}, serverProgramVersion={}, digestInterval={}s, playerKeepalive={}ms(playerTimeout={}s), entityKeepalive={}ms(entityTimeout={}s), battleChunkKeepalive={}ms(battleChunkTimeout={}s)",
				serverProtocolVersion,
				serverProgramVersion,
				digestIntervalSec,
				playerKeepaliveIntervalMs,
				advertisedPlayerTimeoutSec,
				entityKeepaliveIntervalMs,
				advertisedEntityTimeoutSec,
				battleChunkKeepaliveIntervalMs,
				advertisedBattleChunkTimeoutSec
		);
	}

	private void initializeExternalRelations(ProtocolPackets.HandshakeAckInboundPacket packet) {
		Map<String, Object> capabilities = objectMap(packet.relationshipQuery);
		externalRelationSupported = protocolAtLeast(serverProtocolVersion, "0.8.0")
				&& Boolean.TRUE.equals(capabilities.get("supported"))
				&& configGateway != null
				&& !"off".equals(configGateway.getExternalRelationSyncMode());
		externalRelationMaxSelectors = Math.max(1, Math.min(
				toIntegerOrNull(capabilities.get("maxSelectors")) == null
						? 256 : toIntegerOrNull(capabilities.get("maxSelectors")), 512));
		externalRelationMaxChunkEntries = Math.max(1, Math.min(
				toIntegerOrNull(capabilities.get("maxChunkEntries")) == null
						? 256 : toIntegerOrNull(capabilities.get("maxChunkEntries")), 512));
		suppressTabReports = reportPolicySuppressesTab(packet.reportPolicy);
		LOGGER.info(
				"External relations initialized: supported={}, maxSelectors={}, maxChunkEntries={}, suppressTab={}",
				externalRelationSupported,
				externalRelationMaxSelectors,
				externalRelationMaxChunkEntries,
				suppressTabReports
		);
	}

	private static boolean reportPolicySuppressesTab(Map<String, Object> policy) {
		if (policy == null) return false;
		Object recommendations = policy.get("recommendations");
		if (!(recommendations instanceof Collection<?> values)) return false;
		for (Object raw : values) {
			Map<String, Object> recommendation = objectMap(raw);
			if (!"REPORT_SCOPE_TAB".equals(recommendation.get("scope"))) continue;
			return "REPORT_RECOMMENDATION_MODE_SUPPRESS".equals(recommendation.get("mode"));
		}
		return false;
	}

	public boolean isSuppressTabReports() {
		return suppressTabReports;
	}

	public UUID getLocalPlayerId() {
		return runtimeGateway.getLocalPlayerId();
	}

	private void initializeTabHistory(ProtocolPackets.HandshakeAckInboundPacket packet) {
		Map<String, Object> capabilities = objectMap(packet.tabHistory);
		tabHistorySupported = Boolean.TRUE.equals(capabilities.get("supported"));
		if (!tabHistorySupported) return;
		tabHistoryMaxLookupSelectors = Math.max(1, Math.min(
				toIntegerOrNull(capabilities.get("maxLookupSelectors")) == null
						? 128 : toIntegerOrNull(capabilities.get("maxLookupSelectors")), 512));
		String mode = configGateway == null ? "on_demand" : configGateway.getTabHistorySyncMode();
		if ("off".equals(mode)) return;
		if ("full".equals(mode)) {
			ProtocolPackets.TabHistorySubscribePacket subscribe = new ProtocolPackets.TabHistorySubscribePacket();
			subscribe.enabled = true;
			if (tabHistoryRevision > 0) {
				subscribe.knownRevision = tabHistoryRevision;
				subscribe.knownDigestSha256 = tabHistoryDigest.clone();
			}
			sendPacket(subscribe);
			requestTabHistorySync(tabHistoryRevision > 0);
		} else {
			requestMissingTabHistory(lastSeenPlayers.keySet());
		}
	}

	private void handleTabHistoryDigest(ProtocolPackets.TabHistoryDigestInboundPacket packet) {
		if (packet == null || configGateway == null || !"full".equals(configGateway.getTabHistorySyncMode())) return;
		Map<String, Object> head = objectMap(packet.head);
		long revision = longValue(head.get("revision"), 0L);
		byte[] digest = bytesValue(head.get("digestSha256"));
		if (revision != tabHistoryRevision || !java.util.Arrays.equals(digest, tabHistoryDigest)) {
			requestTabHistorySync(tabHistoryRevision > 0);
		}
	}

	private void requestTabHistorySync(boolean delta) {
		ProtocolPackets.TabHistorySyncRequestPacket request = new ProtocolPackets.TabHistorySyncRequestPacket();
		request.requestId = "mod-sync-" + (++tabHistoryRequestSequence);
		request.preferredMode = delta ? "TAB_HISTORY_SYNC_MODE_DELTA" : "TAB_HISTORY_SYNC_MODE_FULL";
		request.allowFullFallback = true;
		request.maxChunkEntries = 256;
		if (delta) {
			request.baseRevision = tabHistoryRevision;
			request.baseDigestSha256 = tabHistoryDigest.clone();
		}
		sendPacket(request);
	}

	public void requestTabHistoryByUuids(Collection<UUID> playerIds) {
		requestMissingTabHistory(playerIds);
	}

	private void requestMissingTabHistory(Collection<UUID> playerIds) {
		if (!isConnected || !tabHistorySupported || playerIds == null || configGateway == null
				|| "off".equals(configGateway.getTabHistorySyncMode())) return;
		Set<UUID> alreadyPending = new HashSet<>();
		for (Set<UUID> pending : tabHistoryLookupRequests.values()) alreadyPending.addAll(pending);
		List<UUID> missing = playerIds.stream()
				.filter(Objects::nonNull)
				.filter(id -> !tabHistoryCache.containsKey(id) && !alreadyPending.contains(id)
						&& !completedTabHistoryLookups.contains(id))
				.sorted(Comparator.comparing(UUID::toString))
				.toList();
		for (int offset = 0; offset < missing.size(); offset += tabHistoryMaxLookupSelectors) {
			List<UUID> batch = missing.subList(offset, Math.min(missing.size(), offset + tabHistoryMaxLookupSelectors));
			String requestId = "mod-lookup-" + (++tabHistoryRequestSequence);
			ProtocolPackets.TabHistoryLookupRequestPacket request = new ProtocolPackets.TabHistoryLookupRequestPacket();
			request.requestId = requestId;
			request.uuids = batch.stream().map(UUID::toString).toList();
			request.names = List.of();
			request.maxChunkEntries = 256;
			tabHistoryLookupRequests.put(requestId, Set.copyOf(batch));
			sendPacket(request);
		}
	}

	private void handleTabHistoryLookupChunk(ProtocolPackets.TabHistoryLookupChunkInboundPacket packet) {
		if (packet == null || packet.errorCode != null) {
			if (packet != null) {
				Set<UUID> completed = tabHistoryLookupRequests.remove(packet.requestId);
				if (completed != null) completedTabHistoryLookups.addAll(completed);
				dirtyTabHistoryLookupRequests.remove(packet.requestId);
			}
			return;
		}
		boolean changed = false;
		for (Map<String, Object> result : packet.results == null ? List.<Map<String, Object>>of() : packet.results) {
			Object entries = result.get("entries");
			if (!(entries instanceof Collection<?> collection)) continue;
			for (Object entry : collection) changed |= applyTabHistoryEntry(entry, tabHistoryCache);
		}
		if (changed && packet.requestId != null) dirtyTabHistoryLookupRequests.add(packet.requestId);
		if (Boolean.TRUE.equals(packet.finalChunk)) {
			Set<UUID> completed = tabHistoryLookupRequests.remove(packet.requestId);
			if (completed != null) completedTabHistoryLookups.addAll(completed);
			if (dirtyTabHistoryLookupRequests.remove(packet.requestId)) publishTabHistoryPlayers();
		}
	}

	private void handleTabHistorySyncChunk(ProtocolPackets.TabHistorySyncChunkInboundPacket packet) {
		if (packet == null || packet.requestId == null || packet.errorCode != null) return;
		Map<String, Object> head = objectMap(packet.head);
		long revision = longValue(head.get("revision"), 0L);
		byte[] digest = bytesValue(head.get("digestSha256"));
		boolean full = "TAB_HISTORY_SYNC_MODE_FULL".equals(packet.mode);
		TabHistorySyncAccumulator accumulator = tabHistorySyncs.computeIfAbsent(
				packet.requestId, ignored -> new TabHistorySyncAccumulator(full, revision, digest));
		if (accumulator.full != full || accumulator.revision != revision
				|| !java.util.Arrays.equals(accumulator.digest, digest)) {
			tabHistorySyncs.remove(packet.requestId);
			requestTabHistorySync(false);
			return;
		}
		for (Map<String, Object> entry : packet.upsert == null ? List.<Map<String, Object>>of() : packet.upsert) {
			applyTabHistoryEntry(entry, accumulator.upsert);
		}
		for (String rawId : packet.deleteUuids == null ? List.<String>of() : packet.deleteUuids) {
			try { accumulator.delete.add(UUID.fromString(rawId)); } catch (IllegalArgumentException ignored) { }
		}
		if (!Boolean.TRUE.equals(packet.finalChunk)) return;

		Map<UUID, Map<String, Object>> candidate = full ? new HashMap<>() : new HashMap<>(tabHistoryCache);
		candidate.putAll(accumulator.upsert);
		accumulator.delete.forEach(candidate::remove);
		tabHistorySyncs.remove(packet.requestId);
		if (!java.util.Arrays.equals(computeTabHistoryDigest(candidate), digest)) {
			LOGGER.warn("Rejected Tab history sync with mismatched SHA-256 digest request={}", packet.requestId);
			requestTabHistorySync(false);
			return;
		}
		tabHistoryCache.clear();
		tabHistoryCache.putAll(candidate);
		tabHistoryRevision = revision;
		tabHistoryDigest = digest.clone();
		publishTabHistoryPlayers();
	}

	private boolean applyTabHistoryEntry(Object raw, Map<UUID, Map<String, Object>> target) {
		Map<String, Object> entry = objectMap(raw);
		Map<String, Object> player = objectMap(entry.get("player"));
		String uuid = normalizeUuidText(player.get("uuid"));
		if (uuid == null) return false;
		try {
			Map<String, Object> stored = new HashMap<>(entry);
			stored.put("player", new HashMap<>(player));
			return !Objects.equals(target.put(UUID.fromString(uuid), stored), stored);
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private byte[] computeTabHistoryDigest(Map<UUID, Map<String, Object>> entries) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			entries.keySet().stream().sorted(Comparator.comparing(UUID::toString)).forEach(playerId -> {
				digest.update(UuidBinaryCodec.toBytes(playerId));
				digest.update(bytesValue(entries.get(playerId).get("etagSha256")));
			});
			return digest.digest();
		} catch (Exception error) {
			return new byte[0];
		}
	}

	private static byte[] bytesValue(Object value) {
		return value instanceof byte[] bytes ? bytes.clone() : new byte[0];
	}

	public List<TabPlayerSnapshot> getTabHistoryPlayers() {
		return publishedTabHistoryPlayers;
	}

	public long getTabHistoryStateGeneration() {
		return tabHistoryStateGeneration;
	}

	private void publishTabHistoryPlayers() {
		List<TabPlayerSnapshot> result = new ArrayList<>();
		for (Map.Entry<UUID, Map<String, Object>> cached : tabHistoryCache.entrySet()) {
			Map<String, Object> player = objectMap(cached.getValue().get("player"));
			result.add(new TabPlayerSnapshot(
					cached.getKey().toString(), normalizeNullableText(player.get("name")),
					normalizeNullableText(player.get("scoreboardPrefix")),
					normalizeNullableText(player.get("displayName")),
					normalizeNullableText(player.get("scoreboardTeamId")),
					normalizeNullableText(player.get("scoreboardSuffix")),
					toIntegerOrNull(player.get("scoreboardColorRgb")),
					normalizeNullableText(player.get("displayName")), null, null, null));
		}
		result.sort(Comparator.comparing(TabPlayerSnapshot::playerId));
		List<TabPlayerSnapshot> next = List.copyOf(result);
		if (next.equals(publishedTabHistoryPlayers)) return;
		publishedTabHistoryPlayers = next;
		tabHistoryStateGeneration++;
	}

	private long calculateKeepaliveIntervalMs(int timeoutSec) {
		long timeoutMs = Math.max(1L, (long) timeoutSec) * 1_000L;
		long candidate = Math.round(timeoutMs * 0.6);
		long upperBound = Math.max(1_000L, timeoutMs - 1_000L);
		return Math.max(1_000L, Math.min(candidate, upperBound));
	}

	public void requestExternalRelationsByUuids(Collection<UUID> playerIds) {
		requestExternalRelations(playerIds, true);
	}

	private void requestExternalRelations(Collection<UUID> playerIds, boolean includeRelations) {
		if (!isConnected || !externalRelationSupported || playerIds == null || playerIds.isEmpty()) return;
		long now = System.currentTimeMillis();
		Set<UUID> alreadyPending = new HashSet<>(pendingExternalRelationIds);
		List<UUID> missing = playerIds.stream()
				.filter(Objects::nonNull)
				.filter(id -> !externalRelationCache.containsKey(id.toString()))
				.filter(id -> !alreadyPending.contains(id))
				.filter(id -> !unavailableExternalRelationIds.contains(id)
						|| now - unavailableExternalRelationAt.getOrDefault(id, 0L) >= RELATION_RETRY_MS)
				.sorted(Comparator.comparing(UUID::toString))
				.distinct()
				.toList();
		if (missing.isEmpty()) return;
		UUID localPlayerId = runtimeGateway.getLocalPlayerId();
		for (int offset = 0; offset < missing.size(); offset += externalRelationMaxSelectors) {
			List<UUID> batch = missing.subList(
					offset, Math.min(missing.size(), offset + externalRelationMaxSelectors));
			pendingExternalRelationIds.addAll(batch);
			if (includeRelations && localPlayerId != null) {
				sendRelationQuery(localPlayerId, batch);
			} else {
				sendDirectoryLookup(batch);
			}
		}
	}

	private void sendRelationQuery(UUID subject, List<UUID> targetIds) {
		ProtocolPackets.PlayerRelationQueryRequestPacket request =
				new ProtocolPackets.PlayerRelationQueryRequestPacket();
		request.requestId = "mod-relation-" + (++externalRelationRequestSequence);
		request.subjectPlayerId = subject.toString();
		request.targetPlayerIds = targetIds.stream().map(UUID::toString).toList();
		request.maxChunkEntries = externalRelationMaxChunkEntries;
		externalRelationRequests.put(request.requestId, Set.copyOf(targetIds));
		sendPacket(request);
	}

	private void sendDirectoryLookup(List<UUID> playerIds) {
		ProtocolPackets.PlayerDirectoryLookupRequestPacket request =
				new ProtocolPackets.PlayerDirectoryLookupRequestPacket();
		request.requestId = "mod-directory-" + (++externalRelationRequestSequence);
		request.playerIds = playerIds.stream().map(UUID::toString).toList();
		request.maxChunkEntries = externalRelationMaxChunkEntries;
		externalRelationRequests.put(request.requestId, Set.copyOf(playerIds));
		sendPacket(request);
	}

	private void handlePlayerDirectoryLookupChunk(
			ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket packet) {
		if (packet == null || packet.errorCode != null) {
			markExternalRelationUnavailable(packet == null ? null : packet.requestId);
			return;
		}
		for (Map<String, Object> result : packet.results == null
				? List.<Map<String, Object>>of() : packet.results) {
			if (result.get("entries") instanceof Collection<?> entries) {
				for (Object raw : entries) applyExternalDirectoryEntry(raw);
			}
		}
		finishExternalRelationRequest(packet.requestId, Boolean.TRUE.equals(packet.finalChunk));
	}

	private void handlePlayerRelationQueryChunk(
			ProtocolPackets.PlayerRelationQueryChunkInboundPacket packet) {
		if (packet == null || packet.errorCode != null) {
			markExternalRelationUnavailable(packet == null ? null : packet.requestId);
			return;
		}
		boolean changed = false;
		for (Map<String, Object> raw : packet.results == null
				? List.<Map<String, Object>>of() : packet.results) {
			Map<String, Object> target = objectMap(raw.get("target"));
			Map<String, Object> player = objectMap(target.get("player"));
			UUID playerId = parseExternalRelationUuid(player.get("uuid"), player.get("playerId"));
			if (playerId == null) continue;
			Map<String, Object> stored = new HashMap<>(raw);
			stored.put("stale", target.get("stale"));
			stored.put("target", new HashMap<>(target));
			changed |= !Objects.equals(externalRelationCache.put(playerId.toString(), stored), stored);
			unavailableExternalRelationIds.remove(playerId);
			unavailableExternalRelationAt.remove(playerId);
		}
		if (changed) publishExternalRelations();
		finishExternalRelationRequest(packet.requestId, Boolean.TRUE.equals(packet.finalChunk));
	}

	private void applyExternalDirectoryEntry(Object raw) {
		Map<String, Object> entry = objectMap(raw);
		Map<String, Object> player = objectMap(entry.get("player"));
		UUID playerId = parseExternalRelationUuid(player.get("uuid"), player.get("playerId"));
		if (playerId == null) return;
		Map<String, Object> stored = new HashMap<>();
		stored.put("target", new HashMap<>(entry));
		stored.put("relation", "PLAYER_RELATION_KIND_UNKNOWN");
		stored.put("stale", entry.get("stale"));
		if (!Objects.equals(externalRelationCache.put(playerId.toString(), stored), stored)) {
			publishExternalRelations();
		}
	}

	private UUID parseExternalRelationUuid(Object uuidValue, Object playerIdValue) {
		String uuid = normalizeUuidText(uuidValue);
		if (uuid == null) uuid = normalizeNullableText(playerIdValue);
		if (uuid == null) return null;
		try {
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private void markExternalRelationUnavailable(String requestId) {
		Set<UUID> requested = requestId == null ? null : externalRelationRequests.remove(requestId);
		if (requested == null) return;
		long now = System.currentTimeMillis();
		requested.forEach(playerId -> {
			unavailableExternalRelationIds.add(playerId);
			unavailableExternalRelationAt.put(playerId, now);
		});
		pendingExternalRelationIds.removeAll(requested);
	}

	private void finishExternalRelationRequest(String requestId, boolean complete) {
		if (requestId == null) return;
		if (!complete) return;
		Set<UUID> requested = externalRelationRequests.remove(requestId);
		if (requested == null) return;
		pendingExternalRelationIds.removeAll(requested);
		unavailableExternalRelationIds.removeAll(requested);
		for (UUID playerId : requested) {
			unavailableExternalRelationAt.remove(playerId);
		}
	}

	public List<Map.Entry<UUID, Map<String, Object>>> getExternalRelations() {
		return publishedExternalRelations;
	}

	public long getExternalRelationStateGeneration() {
		return externalRelationStateGeneration;
	}

	private void publishExternalRelations() {
		List<Map.Entry<UUID, Map<String, Object>>> next = externalRelationCache.entrySet().stream()
				.map(entry -> Map.entry(UUID.fromString(entry.getKey()), Map.copyOf(entry.getValue())))
				.collect(Collectors.toCollection(ArrayList::new));
		next.sort(Comparator.comparing(Map.Entry::getKey));
		publishedExternalRelations = List.copyOf(next);
		externalRelationStateGeneration++;
	}

	private void handleReportRateHint(ProtocolPackets.ReportRateHintInboundPacket packet) {
		if (packet == null) {
			return;
		}
		if (packet.broadcastHz != null && packet.broadcastHz > 0) {
			serverBroadcastHz = packet.broadcastHz;
		}
		if (packet.reportIntervalTicks != null && packet.reportIntervalTicks > 0) {
			negotiatedReportIntervalTicks = packet.reportIntervalTicks;
			LOGGER.info(
					"Applied report rate hint: interval={} ticks, broadcastHz={}, reason={}",
					negotiatedReportIntervalTicks,
					serverBroadcastHz,
					packet.reason == null ? "runtime" : packet.reason
			);
		}
	}

	private String extractHandshakeRejectReason(ProtocolPackets.HandshakeAckInboundPacket packet) {
		if (packet == null) {
			return "unknown";
		}

		if (packet.rejectReason != null && !packet.rejectReason.isBlank()) {
			return packet.rejectReason.trim();
		}

		if (packet.error != null && !packet.error.isBlank()) {
			return packet.error.trim();
		}

		return "unknown";
	}

	private void rejectForVersionIncompatibility(long attemptId, String reason) {
		if (!isCurrentConnectionAttempt(attemptId)) {
			LOGGER.debug("Ignoring stale version rejection for attempt {}", attemptId);
			return;
		}
		String finalReason = reason == null || reason.isBlank()
				? "版本不兼容，连接已拒绝"
				: reason.trim();

		lastConnectionError = finalReason;
		transportOpen = false;
		isConnected = false;
		connectionStage = ConnectionStage.FAILED;
		shouldReconnect = false;
		reconnectSuppressedForVersionMismatch = true;
		invalidateConnectionAttempt(attemptId);
		resetOutboundReportState();
		invalidateInboundRelayState();
		notifyConnectionStatusChanged(false);

		if (socket != null) {
			try {
				socket.close(1008, truncateWebSocketCloseReason(finalReason));
			} catch (Exception ignored) {
			}
		}

		LOGGER.warn("Connection rejected due to protocol incompatibility: {}", finalReason);
	}

	private String truncateWebSocketCloseReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return "version_incompatible";
		}
		String normalized = reason.trim();
		if (normalized.length() <= 120) {
			return normalized;
		}
		return normalized.substring(0, 120);
	}

	private boolean protocolAtLeast(String current, String minimum) {
		return ProtocolVersionUtil.atLeast(current, minimum);
	}

	private String readProtocolVersionFromHandshakeAck(ProtocolPackets.HandshakeAckInboundPacket packet) {
		String value = packet.networkProtocolVersion;
		if (value != null && !value.isBlank()) {
			return value;
		}
		return runtimeGateway.getServerProtocolFallbackVersion();
	}

	private String readProgramVersionFromHandshakeAck(ProtocolPackets.HandshakeAckInboundPacket packet) {
		String value = packet.localProgramVersion;
		if (value != null && !value.isBlank()) {
			return value;
		}
		value = packet.programVersion;
		if (value != null && !value.isBlank()) {
			return value;
		}
		return runtimeGateway.getProgramVersionUnknown();
	}

	private void notifyConnectionStatusChanged(boolean connected) {
		for (ConnectionStatusListener listener : statusListeners) {
			try {
				listener.onConnectionStatusChanged(connected);
			} catch (Exception e) {
				LOGGER.error("Error notifying connection status listener: {}", e.getMessage());
			}
		}
	}

	private void notifyWaypointsReceived(Map<String, SharedWaypointInfo> waypoints) {
		for (WaypointUpdateListener listener : waypointListeners) {
			try {
				listener.onWaypointsReceived(waypoints);
			} catch (Exception e) {
				LOGGER.error("Error notifying waypoint listener: {}", e.getMessage());
			}
		}
	}

	private void notifyWaypointsDeleted(List<String> waypointIds) {
		for (WaypointUpdateListener listener : waypointListeners) {
			try {
				listener.onWaypointsDeleted(waypointIds);
			} catch (Exception e) {
				LOGGER.error("Error notifying waypoint delete listener: {}", e.getMessage());
			}
		}
	}

	private Map<UUID, RemotePlayerInfo> parseRemotePlayers(
			Map<String, Object> players, boolean replaceCache) {
		Map<UUID, RemotePlayerInfo> newRemotePlayers = new HashMap<>();
		String fallbackDimension = getCurrentDimension();

		if (replaceCache) {
			remotePlayerDataCache.clear();
		}

		for (Map.Entry<String, Object> entry : players.entrySet()) {
			try {
				String playerIdStr = entry.getKey();
				Map<String, Object> actualData = extractDataMap(objectMap(entry.getValue()));
				if (actualData.isEmpty()) {
					continue;
				}
				UUID playerId = UUID.fromString(playerIdStr);
				Map<String, Object> mergedData = new HashMap<>();
				if (!replaceCache && remotePlayerDataCache.containsKey(playerId)) {
					mergedData.putAll(remotePlayerDataCache.get(playerId));
				}
				mergedData.putAll(actualData);

				RemotePlayerInfo info = buildRemotePlayerInfo(playerId, mergedData, fallbackDimension, playerIdStr);
				if (info == null) {
					continue;
				}

				remotePlayerDataCache.put(playerId, mergedData);
				newRemotePlayers.put(playerId, info);
			} catch (Exception e) {
				LOGGER.error("TeamViewRelay Network - Error parsing player data: {}", e.getMessage());
			}
		}

		return newRemotePlayers;
	}

	private PlayerPatchChange applyPlayerPatchUpserts(Map<String, Object> upserts) {
		String fallbackDimension = getCurrentDimension();
		boolean remoteChanged = false;
		boolean lastSeenChanged = false;

		for (Map.Entry<String, Object> entry : upserts.entrySet()) {
			try {
				Map<String, Object> data = extractDataMap(objectMap(entry.getValue()));
				if (data.isEmpty()) {
					continue;
				}

				UUID playerId = UUID.fromString(entry.getKey());
				Map<String, Object> mergedData = new HashMap<>();
				Map<String, Object> existing = remotePlayerDataCache.get(playerId);
				if (existing != null) {
					mergedData.putAll(existing);
				}

				applyDeltaFields(mergedData, data);

				RemotePlayerInfo info = buildRemotePlayerInfo(playerId, mergedData, fallbackDimension, entry.getKey());
				if (info == null) {
					continue;
				}

				boolean rawDataChanged = !Objects.equals(existing, mergedData);
				remotePlayerDataCache.put(playerId, mergedData);
				RemotePlayerInfo previous = remotePlayers.put(playerId, info);
				remoteChanged |= rawDataChanged || !Objects.equals(previous, info);
				lastSeenChanged |= lastSeenPlayers.remove(playerId) != null;
				lastSeenPlayerDataCache.remove(playerId.toString());
			} catch (Exception e) {
				LOGGER.error("TeamViewRelay Network - Error applying player patch: {}", e.getMessage());
			}
		}
		return remoteChanged || lastSeenChanged
				? new PlayerPatchChange(remoteChanged, lastSeenChanged) : PlayerPatchChange.NONE;
	}

	private void applyDeltaFields(Map<String, Object> target, Map<String, Object> delta) {
		if (target == null || delta == null) {
			return;
		}
		for (Map.Entry<String, Object> entry : delta.entrySet()) {
			if (entry.getValue() == null) {
				target.remove(entry.getKey());
			} else {
				target.put(entry.getKey(), entry.getValue());
			}
		}
	}

	private boolean replaceLastSeenPlayers(Map<String, Object> players) {
		Map<UUID, LastSeenPlayerInfo> before = Map.copyOf(lastSeenPlayers);
		lastSeenPlayers.clear();
		lastSeenPlayerDataCache.clear();
		for (Map.Entry<String, Object> entry : players.entrySet()) {
			upsertLastSeenPlayer(entry.getKey(), entry.getValue());
		}
		return !before.equals(lastSeenPlayers);
	}

	private boolean applyLastSeenPlayerPatch(Map<String, Object> patch) {
		boolean changed = false;
		for (Object rawId : objectList(patch.get("delete"))) {
			try {
				UUID playerId = UUID.fromString(String.valueOf(rawId));
				changed |= lastSeenPlayers.remove(playerId) != null;
				lastSeenPlayerDataCache.remove(playerId.toString());
			} catch (IllegalArgumentException ignored) {
			}
		}
		for (Map.Entry<String, Object> entry : objectMap(patch.get("upsert")).entrySet()) {
			changed |= upsertLastSeenPlayer(entry.getKey(), entry.getValue());
		}
		return changed;
	}

	private boolean upsertLastSeenPlayer(String rawId, Object rawValue) {
		try {
			UUID playerId = UUID.fromString(rawId);
			if (remotePlayers.containsKey(playerId)) return false;
			Map<String, Object> data = extractDataMap(objectMap(rawValue));
			LastSeenPlayerInfo player = parseLastSeenPlayer(playerId, data);
			if (player == null) return false;
			LastSeenPlayerInfo previous = lastSeenPlayers.put(playerId, player);
			lastSeenPlayerDataCache.put(playerId.toString(), new HashMap<>(data));
			return !Objects.equals(previous, player);
		} catch (IllegalArgumentException error) {
			LOGGER.debug("Ignoring invalid last-seen player {}: {}", rawId, error.getMessage());
			return false;
		}
	}

	private LastSeenPlayerInfo parseLastSeenPlayer(UUID playerId, Map<String, Object> data) {
		if (data == null || data.isEmpty()) return null;
		String reportedId = normalizeNullableText(data.get("playerUUID"));
		String name = normalizeNullableText(data.get("playerName"));
		String dimension = normalizeNullableText(data.get("dimension"));
		Double x = finite(getAsDouble(data.get("x")));
		Double y = finite(getAsDouble(data.get("y")));
		Double z = finite(getAsDouble(data.get("z")));
		long lastSeenAt = longValue(data.get("lastSeenAtUtcMs"), -1L);
		long positionObservedAt = longValue(data.get("positionObservedAtUtcMs"), -1L);
		long offlineDetectedAt = longValue(data.get("offlineDetectedAtUtcMs"), -1L);
		if (reportedId == null || !playerId.equals(UUID.fromString(reportedId))
				|| name == null || dimension == null || x == null || y == null || z == null
				|| positionObservedAt < 0 || lastSeenAt < positionObservedAt
				|| offlineDetectedAt < lastSeenAt) {
			return null;
		}
		return new LastSeenPlayerInfo(playerId, new Position3D(x, y, z), dimension, name,
				lastSeenAt, positionObservedAt, offlineDetectedAt);
	}

	private RemotePlayerInfo buildRemotePlayerInfo(UUID playerId, Map<String, Object> mergedData,
			String fallbackDimension, String fallbackName) {
		Double x = getAsDouble(mergedData.get("x"));
		Double y = getAsDouble(mergedData.get("y"));
		Double z = getAsDouble(mergedData.get("z"));
		if (x == null || y == null || z == null) {
			return null;
		}

		String dimensionId = mergedData.get("dimension") == null ? null : String.valueOf(mergedData.get("dimension"));
		String dimension = dimensionId == null || dimensionId.isBlank() ? fallbackDimension : dimensionId;
		String playerName = mergedData.get("playerName") == null ? fallbackName : String.valueOf(mergedData.get("playerName"));

		Position3D position = new Position3D(x, y, z);
		return new RemotePlayerInfo(playerId, position, dimension, playerName);
	}

	private Double getAsDouble(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> computeFieldDelta(Map<String, Object> previous, Map<String, Object> current) {
		Map<String, Object> delta = new HashMap<>();
		for (Map.Entry<String, Object> entry : current.entrySet()) {
			if (!Objects.equals(previous.get(entry.getKey()), entry.getValue())) {
				delta.put(entry.getKey(), entry.getValue());
			}
		}
		return delta;
	}

	private void updateObjectLivenessAfterPatch(
			Map<String, Long> livenessMap,
			Set<String> upsertIds,
			List<String> deleteIds,
			long nowMs
	) {
		if (upsertIds != null) {
			for (String id : upsertIds) {
				if (id != null && !id.isBlank()) {
					livenessMap.put(id, nowMs);
				}
			}
		}
		if (deleteIds != null) {
			for (String id : deleteIds) {
				if (id != null && !id.isBlank()) {
					livenessMap.remove(id);
				}
			}
		}
	}

	private void sendObjectKeepaliveIfNeeded(
			UUID submitPlayerId,
			Map<String, Map<String, Object>> playerSnapshot,
			Map<String, Map<String, Object>> entitySnapshot,
			Set<String> playerUpsertIds,
			Set<String> entityUpsertIds,
			long nowMs
	) {
		if (socket == null || !isConnected || submitPlayerId == null) {
			return;
		}

		List<String> keepalivePlayers = collectKeepaliveIds(
				playerSnapshot,
				playerUpsertIds,
				lastPlayerObjectLivenessMs,
				playerKeepaliveIntervalMs,
				nowMs
		);
		List<String> keepaliveEntities = collectKeepaliveIds(
				entitySnapshot,
				entityUpsertIds,
				lastEntityObjectLivenessMs,
				entityKeepaliveIntervalMs,
				nowMs
		);

		if (keepalivePlayers.isEmpty() && keepaliveEntities.isEmpty()) {
			return;
		}

		ProtocolPackets.StateKeepalivePacket packet = new ProtocolPackets.StateKeepalivePacket();
		packet.submitPlayerId = UuidBinaryCodec.toBytes(submitPlayerId);
		packet.players = keepalivePlayers;
		packet.entities = keepaliveEntities;
		packet.battleChunks = List.of();
		sendPacket(packet);
	}

	private List<String> collectKeepaliveIds(
			Map<String, Map<String, Object>> snapshot,
			Set<String> justUpsertedIds,
			Map<String, Long> livenessMap,
			long keepaliveIntervalMs,
			long nowMs
	) {
		if (snapshot == null || snapshot.isEmpty()) {
			return List.of();
		}

		Set<String> activeIds = new HashSet<>(snapshot.keySet());
		livenessMap.keySet().retainAll(activeIds);

		List<String> keepaliveIds = new ArrayList<>();
		for (String objectId : activeIds) {
			if (objectId == null || objectId.isBlank()) {
				continue;
			}
			if (justUpsertedIds != null && justUpsertedIds.contains(objectId)) {
				continue;
			}
			Long lastSeenMs = livenessMap.get(objectId);
			if (lastSeenMs == null) {
				livenessMap.put(objectId, nowMs);
				continue;
			}
			if (nowMs - lastSeenMs < keepaliveIntervalMs) {
				continue;
			}
			keepaliveIds.add(objectId);
			livenessMap.put(objectId, nowMs);
			if (keepaliveIds.size() >= KEEPALIVE_MAX_ITEMS_PER_PACKET) {
				break;
			}
		}

		return keepaliveIds;
	}

	private boolean shouldForcePlayersFullRefresh() {
		long now = System.currentTimeMillis();
		return now - lastPlayersPacketSentMs >= FORCE_FULL_REFRESH_MS;
	}

	private boolean shouldForceEntitiesFullRefresh() {
		long now = System.currentTimeMillis();
		return now - lastEntitiesPacketSentMs >= FORCE_FULL_REFRESH_MS;
	}

	private void handleRefreshRequest(ProtocolPackets.RefreshReqInboundPacket packet) {
		List<String> players = packet != null && packet.players != null ? packet.players : List.of();
		List<String> entities = packet != null && packet.entities != null ? packet.entities : List.of();
		List<BattleChunkRefData> battleChunks = packet != null && packet.battleChunks != null
				? packet.battleChunks : List.of();

		pendingPlayerRefreshIds.addAll(players);
		pendingEntityRefreshIds.addAll(entities);
		pendingBattleChunkRefreshRefs.addAll(battleChunks);

		if (!players.isEmpty() || !entities.isEmpty() || !battleChunks.isEmpty()) {
			LOGGER.debug(
					"Received refresh_req: players={}, entities={}, battleChunks={}",
					players.size(),
					entities.size(),
					battleChunks.size()
			);
		}
	}

	private void applyPendingPlayerRefresh(
			Map<String, Map<String, Object>> currentSnapshot,
			Map<String, Map<String, Object>> upsert,
			List<String> delete
	) {
		if (pendingPlayerRefreshIds.isEmpty()) {
			return;
		}

		Set<String> deleteSet = new HashSet<>(delete);
		for (String playerId : new ArrayList<>(pendingPlayerRefreshIds)) {
			Map<String, Object> fullData = currentSnapshot.get(playerId);
			if (fullData != null) {
				upsert.put(playerId, fullData);
			} else {
				deleteSet.add(playerId);
			}
			pendingPlayerRefreshIds.remove(playerId);
		}

		delete.clear();
		delete.addAll(deleteSet);
	}

	private void applyPendingEntityRefresh(
			Map<String, Map<String, Object>> currentSnapshot,
			Map<String, Map<String, Object>> upsert,
			List<String> delete
	) {
		if (pendingEntityRefreshIds.isEmpty()) {
			return;
		}

		Set<String> deleteSet = new HashSet<>(delete);
		for (String entityId : new ArrayList<>(pendingEntityRefreshIds)) {
			Map<String, Object> fullData = currentSnapshot.get(entityId);
			if (fullData != null) {
				upsert.put(entityId, fullData);
			} else {
				deleteSet.add(entityId);
			}
			pendingEntityRefreshIds.remove(entityId);
		}

		delete.clear();
		delete.addAll(deleteSet);
	}

	private Map<String, SharedWaypointInfo> parseWaypointsFromObject(Map<String, Object> waypoints) {
		Map<String, SharedWaypointInfo> result = new HashMap<>();

		for (Map.Entry<String, Object> entry : waypoints.entrySet()) {
			try {
				String waypointId = entry.getKey();
				Map<String, Object> delta = extractDataMap(objectMap(entry.getValue()));
				if (delta.isEmpty()) {
					continue;
				}
				Map<String, Object> data = new HashMap<>();
				Map<String, Object> existing = remoteWaypointDataCache.get(waypointId);
				if (existing != null) {
					data.putAll(existing);
				}
				applyDeltaFields(data, delta);
				remoteWaypointDataCache.put(waypointId, new HashMap<>(data));

				if (!data.containsKey("x") || !data.containsKey("y") || !data.containsKey("z")) {
					continue;
				}

				UUID ownerId = parseOptionalUuid(data, "ownerId");

				String name = textOrDefault(data.get("name"), "Waypoint");
				String symbol = textOrDefault(data.get("symbol"), "W");
				String ownerName = textOrDefault(data.get("ownerName"), "Unknown");
				String dimension = normalizeNullableText(data.get("dimension"));
				int color = intValue(data.get("color"), 0x55FF55);
				long createdAt = longValue(data.get("createdAt"), System.currentTimeMillis());
				String targetType = normalizeNullableText(data.get("targetType"));
				String targetEntityId = normalizeNullableText(data.get("targetEntityId"));
				String targetEntityType = normalizeNullableText(data.get("targetEntityType"));
				String targetEntityName = normalizeNullableText(data.get("targetEntityName"));
				String waypointKind = normalizeNullableText(data.get("waypointKind"));
				String tacticalType = normalizeNullableText(data.get("tacticalType"));
				String sourceType = normalizeNullableText(data.get("sourceType"));

				SharedWaypointInfo waypoint = new SharedWaypointInfo(
						waypointId,
						ownerId,
						ownerName,
						name,
						symbol,
						intValue(data.get("x"), 0),
						intValue(data.get("y"), 0),
						intValue(data.get("z"), 0),
						dimension,
						color,
						createdAt,
						targetType,
						targetEntityId,
						targetEntityType,
						targetEntityName,
						waypointKind,
						tacticalType,
						sourceType);
				result.put(waypointId, waypoint);
			} catch (Exception e) {
				LOGGER.error("Failed to parse shared waypoint {}: {}", entry.getKey(), e.getMessage());
			}
		}

		return result;
	}

	private UUID parseOptionalUuid(Map<String, Object> data, String fieldName) {
		if (data == null || fieldName == null || fieldName.isBlank()) {
			return null;
		}
		Object value = data.get(fieldName);
		if (value == null) {
			return null;
		}
		try {
			String raw = String.valueOf(value);
			if (raw == null || raw.isBlank()) {
				return null;
			}
			return UUID.fromString(raw.trim());
		} catch (Exception ignored) {
			return null;
		}
	}

	private void reconcileRemotePlayers(Map<UUID, RemotePlayerInfo> latestRemotePlayers) {
		remotePlayers.entrySet().removeIf(entry -> !latestRemotePlayers.containsKey(entry.getKey()));
		for (Map.Entry<UUID, RemotePlayerInfo> entry : latestRemotePlayers.entrySet()) {
			UUID playerId = entry.getKey();
			RemotePlayerInfo latest = entry.getValue();
			RemotePlayerInfo existing = remotePlayers.get(playerId);
			if (!Objects.equals(existing, latest)) {
				remotePlayers.put(playerId, latest);
			}
		}
	}

	private String computePlayersDigest() {
		return computePlayersDigest(remotePlayerDataCache);
	}

	private String computePlayersDigest(Map<UUID, Map<String, Object>> playerData) {
		Map<String, Map<String, Object>> state = new HashMap<>();
		for (Map.Entry<UUID, Map<String, Object>> entry : playerData.entrySet()) {
			state.put(entry.getKey().toString(), entry.getValue());
		}
		return stateDigest(state);
	}

	private String computeEntitiesDigest() {
		return stateDigest(remoteEntityDataCache);
	}

	private String computeWaypointDigest() {
		return stateDigest(remoteWaypointDataCache);
	}

	private BattleChunkDigestValues computeBattleChunkDigests() {
		return computeBattleChunkDigests(remoteBattleChunkDataCache, serverProtocolVersion);
	}

	private BattleChunkDigestValues computeBattleChunkDigests(
			Map<BattleChunkRefData, Map<String, Object>> battleChunks,
			String protocolVersion) {
		if (protocolAtLeast(protocolVersion, "0.7.1")) {
			List<BattleChunkRefData> refs = new ArrayList<>(battleChunks.keySet());
			Collections.sort(refs);
			List<Object> entries = new ArrayList<>();
			for (BattleChunkRefData ref : refs) {
				Map<String, Object> structured = new HashMap<>();
				structured.put("ref", battleChunkRefMap(ref));
				structured.put("data", normalizeBattleChunkCoreData(battleChunks.get(ref)));
				entries.add(structured);
			}
			return new BattleChunkDigestValues(
					digestCanonicalValues(entries), null, "structured_v2_ref_data");
		}

		Map<String, Map<String, Object>> official = new HashMap<>();
		Map<String, Map<String, Object>> buggyRust = new HashMap<>();
		for (Map.Entry<BattleChunkRefData, Map<String, Object>> entry : battleChunks.entrySet()) {
			BattleChunkRefData ref = entry.getKey();
			Map<String, Object> data = normalizeBattleChunkCoreData(entry.getValue());
			buggyRust.put(ref.identityKey(), data);
			Map<String, Object> flattened = new HashMap<>(data);
			flattened.putAll(battleChunkRefMap(ref));
			official.put(ref.identityKey(), flattened);
		}
		return new BattleChunkDigestValues(
				stateDigest(official), stateDigest(buggyRust), "legacy_keyed_with_buggy_rust_compat");
	}

	private Map<String, Object> battleChunkRefMap(BattleChunkRefData ref) {
		Map<String, Object> mapped = new HashMap<>();
		mapped.put("dimension", ref.dimension());
		mapped.put("chunkX", ref.chunkX());
		mapped.put("chunkZ", ref.chunkZ());
		return mapped;
	}

	private record BattleChunkDigestValues(String official, String compatibility, String contract) {
	}

	private String computeLastSeenPlayersDigest() {
		return stateDigest(lastSeenPlayerDataCache);
	}

	private Map<String, Object> normalizeBattleChunkCoreData(Map<String, Object> source) {
		Map<String, Object> normalized = new HashMap<>();
		if (source == null || source.isEmpty()) {
			return normalized;
		}
		copyBattleChunkFieldIfPresent(source, normalized, "symbol");
		copyBattleChunkFieldIfPresent(source, normalized, "markerType");
		copyBattleChunkFieldIfPresent(source, normalized, "colorRaw");
		copyBattleChunkFieldIfPresent(source, normalized, "colorNote");
		copyBattleChunkFieldIfPresent(source, normalized, "roomCode");
		copyBattleChunkFieldIfPresent(source, normalized, "colorSemanticKey");
		copyBattleChunkFieldIfPresent(source, normalized, "mode");
		Object colorMode = source.get("colorMode");
		normalized.put("colorMode", colorMode == null ? "raw_observed" : colorMode);
		return normalized;
	}

	private void copyBattleChunkFieldIfPresent(Map<String, Object> source, Map<String, Object> target, String fieldName) {
		if (source.containsKey(fieldName)) {
			target.put(fieldName, source.get(fieldName));
		}
	}

	private void replaceEntityCache(Map<String, Object> entities) {
		remoteEntityDataCache.clear();
		mergeEntityPatchUpsert(entities);
	}

	private void mergeEntityPatchUpsert(Map<String, Object> upserts) {
		for (Map.Entry<String, Object> entry : upserts.entrySet()) {
			try {
				Map<String, Object> data = extractDataMap(objectMap(entry.getValue()));
				if (data.isEmpty()) {
					continue;
				}
				String entityId = entry.getKey();
				Map<String, Object> merged = new HashMap<>();
				Map<String, Object> existing = remoteEntityDataCache.get(entityId);
				if (existing != null) {
					merged.putAll(existing);
				}
				applyDeltaFields(merged, data);
				remoteEntityDataCache.put(entityId, merged);
			} catch (Exception e) {
				LOGGER.error("TeamViewRelay Network - Error applying entity patch: {}", e.getMessage());
			}
		}
	}

	private void mergeBattleChunkEntries(List<ProtocolPackets.BattleChunkEntryData> entries) {
		if (entries == null) return;
		for (ProtocolPackets.BattleChunkEntryData entry : entries) {
			try {
				if (entry == null || entry.ref() == null || entry.data() == null) {
					continue;
				}
				remoteBattleChunkDataCache.put(
						entry.ref(),
						normalizeBattleChunkCoreData(entry.data())
				);
			} catch (Exception e) {
				LOGGER.error("TeamViewRelay Network - Error applying battle chunk snapshot: {}", e.getMessage());
			}
		}
	}

	private void applyBattleChunkPatch(ProtocolPackets.BattleChunkPatchData battleChunkPatch) {
		if (battleChunkPatch == null) {
			return;
		}
		if (battleChunkPatch.delete() != null) {
			for (BattleChunkRefData ref : battleChunkPatch.delete()) {
				if (ref != null) remoteBattleChunkDataCache.remove(ref);
			}
		}
		if (battleChunkPatch.upsert() == null) return;
		for (ProtocolPackets.BattleChunkUpsertData upsert : battleChunkPatch.upsert()) {
			if (upsert == null || upsert.ref() == null) continue;
			Map<String, Object> merged = new HashMap<>();
			Map<String, Object> existing = remoteBattleChunkDataCache.get(upsert.ref());
			if (existing != null) merged.putAll(normalizeBattleChunkCoreData(existing));
			if (upsert.clearFields() != null) {
				for (String field : upsert.clearFields()) {
					if (field != null) merged.remove(field);
				}
			}
			applyDeltaFields(merged, normalizeBattleChunkCoreData(upsert.data()));
			remoteBattleChunkDataCache.put(upsert.ref(), merged);
		}
	}

	private String stateDigest(Map<String, Map<String, Object>> state) {
		try {
			List<String> ids = new ArrayList<>(state.keySet());
			Collections.sort(ids);

			List<String> lines = new ArrayList<>();
			for (String id : ids) {
				Map<String, Object> data = state.get(id);
				lines.add(gson.toJson(id) + ":" + canonicalValue(data == null ? Map.of() : data));
			}

			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			for (String line : lines) {
				digest.update(line.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			byte[] bytes = digest.digest();
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8 && i < bytes.length; i++) {
				hex.append(String.format("%02x", bytes[i]));
			}
			return hex.toString();
		} catch (Exception e) {
			return "hash_error";
		}
	}

	private String digestCanonicalValues(List<Object> values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			for (Object value : values) {
				digest.update(canonicalValue(value).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			byte[] bytes = digest.digest();
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8 && i < bytes.length; i++) {
				hex.append(String.format("%02x", bytes[i]));
			}
			return hex.toString();
		} catch (Exception e) {
			return "hash_error";
		}
	}

	private String canonicalValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Boolean boolValue) {
			return boolValue.toString();
		}
		if (value instanceof Number numberValue) {
			return canonicalNumber(numberValue);
		}
		if (value instanceof String stringValue) {
			return gson.toJson(stringValue);
		}
		if (value instanceof Map<?, ?> mapValue) {
			List<String> keys = new ArrayList<>();
			for (Object key : mapValue.keySet()) {
				keys.add(String.valueOf(key));
			}
			Collections.sort(keys);

			StringBuilder builder = new StringBuilder("{");
			for (int i = 0; i < keys.size(); i++) {
				String key = keys.get(i);
				if (i > 0) {
					builder.append(',');
				}
				builder.append(gson.toJson(key)).append(':').append(canonicalValue(mapValue.get(key)));
			}
			builder.append('}');
			return builder.toString();
		}
		if (value instanceof List<?> listValue) {
			StringBuilder builder = new StringBuilder("[");
			for (int i = 0; i < listValue.size(); i++) {
				if (i > 0) {
					builder.append(',');
				}
				builder.append(canonicalValue(listValue.get(i)));
			}
			builder.append(']');
			return builder.toString();
		}

		return gson.toJson(value);
	}

	private String canonicalNumber(Number numberValue) {
		if (numberValue instanceof Byte || numberValue instanceof Short
				|| numberValue instanceof Integer || numberValue instanceof Long) {
			return String.valueOf(numberValue.longValue());
		}

		double value = numberValue.doubleValue();
		if (!Double.isFinite(value)) {
			return "null";
		}

		BigDecimal decimal = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
		String text = decimal.toPlainString();
		if ("-0".equals(text)) {
			return "0";
		}
		return text;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> objectMap(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> objectList(Object value) {
		return value instanceof List<?> ? (List<Object>) value : List.of();
	}

	private static Map<String, Object> extractDataMap(Map<String, Object> node) {
		if (node.containsKey("data") && node.get("data") instanceof Map<?, ?>) {
			return objectMap(node.get("data"));
		}
		return node;
	}

	private static String textOrDefault(Object value, String fallback) {
		String text = normalizeNullableText(value);
		return text == null ? fallback : text;
	}

	private static int intValue(Object value, int fallback) {
		if (value instanceof Number number) return number.intValue();
		try {
			return value == null ? fallback : Integer.parseInt(String.valueOf(value));
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private static long longValue(Object value, long fallback) {
		if (value instanceof Number number) return number.longValue();
		try {
			return value == null ? fallback : Long.parseLong(String.valueOf(value));
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private Map<String, Object> copyValueMap(Map<String, Object> source) {
		if (source == null) {
			return new HashMap<>();
		}
		return new HashMap<>(source);
	}

	private static int hashMapCapacity(int expectedSize) {
		if (expectedSize < 3) {
			return expectedSize + 1;
		}
		if (expectedSize < 1 << 30) {
			return (int) ((float) expectedSize / 0.75F + 1.0F);
		}
		return Integer.MAX_VALUE;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> copyValueList(Object source) {
		if (!(source instanceof List<?> listValue)) {
			return List.of();
		}
		List<Map<String, Object>> copy = new ArrayList<>();
		for (Object item : listValue) {
			if (item instanceof Map<?, ?> rawMap) {
				Map<String, Object> normalized = new HashMap<>();
				for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
					normalized.put(String.valueOf(entry.getKey()), entry.getValue());
				}
				copy.add(normalized);
			}
		}
		return copy;
	}

	private Integer toIntegerOrNull(Object value) {
		if (value instanceof Number numberValue) {
			return numberValue.intValue();
		}
		try {
			String text = normalizeNullableText(value);
			return text == null ? null : Integer.parseInt(text);
		} catch (Exception ignored) {
			return null;
		}
	}

	private Long toLongOrNull(Object value) {
		if (value instanceof Number numberValue) {
			return numberValue.longValue();
		}
		try {
			String text = normalizeNullableText(value);
			return text == null ? null : Long.parseLong(text);
		} catch (Exception ignored) {
			return null;
		}
	}

	private Map<String, Map<String, Object>> buildTabPlayersSnapshot(List<Map<String, Object>> tabPlayers) {
		Map<String, Map<String, Object>> snapshot =
				new HashMap<>(hashMapCapacity(tabPlayers.size()));
		for (Map<String, Object> raw : tabPlayers) {
			if (raw == null || raw.isEmpty()) {
				continue;
			}

			String playerId = normalizeUuidText(raw.get("uuid"));
			if (playerId == null) playerId = normalizeUuidText(raw.get("playerUUID"));
			String name = normalizeNullableText(raw.get("name"));
			String displayName = normalizeNullableText(raw.get("displayName"));
			if (displayName == null) displayName = normalizeNullableText(raw.get("prefixColored"));
			String prefixedName = normalizeNullableText(raw.get("prefixedName"));
			if (prefixedName == null) prefixedName = normalizeNullableText(raw.get("prefixText"));

			String entryKey = buildTabPlayerEntryKey(playerId, name, displayName, prefixedName);
			if (entryKey == null) {
				continue;
			}

			Map<String, Object> snapshotEntry = new HashMap<>(8);
			if (playerId != null) {
				snapshotEntry.put("uuid", playerId);
			}
			snapshotEntry.put("name", name);
			snapshotEntry.put("displayName", displayName);
			snapshotEntry.put("prefixedName", prefixedName);
			snapshotEntry.put("scoreboardTeamId", raw.get("scoreboardTeamId"));
			snapshotEntry.put("scoreboardPrefix", raw.get("scoreboardPrefix"));
			snapshotEntry.put("scoreboardSuffix", raw.get("scoreboardSuffix"));
			snapshotEntry.put("scoreboardColorRgb", raw.get("scoreboardColorRgb"));
			snapshotEntry.put("formattedDisplayName", raw.get("formattedDisplayName"));
			snapshotEntry.put("formattedScoreboardPrefix", raw.get("formattedScoreboardPrefix"));
			snapshotEntry.put("formattedScoreboardSuffix", raw.get("formattedScoreboardSuffix"));
			snapshot.put(entryKey, snapshotEntry);
		}
		return snapshot;
	}

	private String buildTabPlayerEntryKey(String playerId, String name, String displayName, String prefixedName) {
		if (playerId != null && !playerId.isBlank()) {
			return playerId;
		}
		if (name != null && !name.isBlank()) {
			return "name:" + name.toLowerCase();
		}
		if (displayName != null && !displayName.isBlank()) {
			return "display:" + displayName;
		}
		if (prefixedName != null && !prefixedName.isBlank()) {
			return "prefix:" + prefixedName;
		}
		return null;
	}

	private String normalizeUuidText(Object value) {
		String canonical = UuidBinaryCodec.toCanonicalString(value);
		if (canonical != null && !canonical.isBlank()) {
			return canonical;
		}
		return normalizeNullableText(value);
	}

	private static String normalizeNullableText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private void resetNegotiationState() {
		serverProtocolVersion = runtimeGateway.getServerProtocolFallbackVersion();
		serverProgramVersion = runtimeGateway.getProgramVersionUnknown();
		digestIntervalSec = 10;
		serverBroadcastHz = 20.0;
		negotiatedReportIntervalTicks = configGateway != null ? configGateway.getUpdateIntervalTicks() : 10;
		playerKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
		entityKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
		battleChunkKeepaliveIntervalMs = DEFAULT_OBJECT_KEEPALIVE_INTERVAL_MS;
		lastResyncRequestMs = 0L;
		lastPlayersPacketSentMs = 0L;
		lastEntitiesPacketSentMs = 0L;
		tabHistorySyncs.clear();
		tabHistoryLookupRequests.clear();
		completedTabHistoryLookups.clear();
		dirtyTabHistoryLookupRequests.clear();
		tabHistorySupported = false;
		externalRelationRequests.clear();
		pendingExternalRelationIds.clear();
		unavailableExternalRelationIds.clear();
		unavailableExternalRelationAt.clear();
		externalRelationSupported = false;
		suppressTabReports = false;
	}

	public int getNegotiatedReportIntervalTicks() {
		return Math.max(1, negotiatedReportIntervalTicks);
	}

	public double getServerBroadcastHz() {
		return serverBroadcastHz;
	}

	public long getBattleChunkKeepaliveIntervalMs() {
		return battleChunkKeepaliveIntervalMs;
	}

	public Set<BattleChunkRefData> drainPendingBattleChunkRefreshRefs() {
		if (pendingBattleChunkRefreshRefs.isEmpty()) {
			return Set.of();
		}
		Set<BattleChunkRefData> drained = new HashSet<>(pendingBattleChunkRefreshRefs);
		pendingBattleChunkRefreshRefs.clear();
		return drained;
	}

	public boolean hasPendingEntityRefreshIds() {
		return !pendingEntityRefreshIds.isEmpty();
	}

	public Set<String> drainPendingEntityRefreshIds() {
		if (pendingEntityRefreshIds.isEmpty()) {
			return Set.of();
		}
		Set<String> drained = new HashSet<>(pendingEntityRefreshIds);
		pendingEntityRefreshIds.clear();
		return drained;
	}

	private void resetOutboundReportState() {
		lastSentPlayersSnapshot.clear();
		lastSentEntitiesSnapshot.clear();
		lastSentTabPlayersSnapshot.clear();
		lastPlayerObjectLivenessMs.clear();
		lastEntityObjectLivenessMs.clear();
		pendingPlayerRefreshIds.clear();
		pendingEntityRefreshIds.clear();
		pendingBattleChunkRefreshRefs.clear();
	}

	private void invalidateInboundRelayState() {
		List<String> removedWaypointIds = remoteWaypointDataCache.isEmpty()
				? List.of()
				: List.copyOf(remoteWaypointDataCache.keySet());
		remotePlayers.clear();
		remotePlayerDataCache.clear();
		remoteEntityDataCache.clear();
		remoteWaypointDataCache.clear();
		remoteBattleChunkDataCache.clear();
		remotePlayerMarks.clear();
		lastSeenPlayers.clear();
		lastSeenPlayerDataCache.clear();
		tabHistoryCache.clear();
		tabHistoryRevision = 0L;
		tabHistoryDigest = new byte[0];
		tabHistorySyncs.clear();
		tabHistoryLookupRequests.clear();
		completedTabHistoryLookups.clear();
		dirtyTabHistoryLookupRequests.clear();
		externalRelationCache.clear();
		externalRelationRequests.clear();
		pendingExternalRelationIds.clear();
		unavailableExternalRelationIds.clear();
		unavailableExternalRelationAt.clear();
		publishedExternalRelations = List.of();
		externalRelationStateGeneration++;
		if (!publishedRemotePlayerState.isEmpty()) {
			publishedRemotePlayerState = Map.of();
			remotePlayerStateGeneration++;
		}
		if (!publishedLastSeenPlayerState.isEmpty()) {
			publishedLastSeenPlayerState = Map.of();
			lastSeenPlayerStateGeneration++;
		}
		if (!publishedTabHistoryPlayers.isEmpty()) {
			publishedTabHistoryPlayers = List.of();
			tabHistoryStateGeneration++;
		}
		publishedRemotePlayerSnapshots = List.of();
		publishedLastSeenPlayerSnapshots = List.of();
		if (!removedWaypointIds.isEmpty()) {
			notifyWaypointsDeleted(removedWaypointIds);
		}
		inboundStateEpoch++;
	}
}
