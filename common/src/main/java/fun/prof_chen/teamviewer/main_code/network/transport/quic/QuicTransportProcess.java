package fun.prof_chen.teamviewer.main_code.network.transport.quic;

import fun.prof_chen.teamviewer.main_code.network.abstraction.SocketProcess;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportListener;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 裸 QUIC 门的 parent 侧外壳。quiche native 以 RegisterNatives 按
 * {@code io/netty/handler/codec/quic/*} 原名绑定,netty 4.2 类不可重定位;
 * 而未重定位的 netty 4.2 又会与 Minecraft 自带 netty 4.1 同包名冲突——
 * 因此整套 bundle(编译类 jar + 平台 native jar)以资源形式携带,运行时
 * 校验后解包到缓存目录,加载进 child-first ClassLoader,并在其中反射进入
 * {@link QuicTransportCore}。
 *
 * <p>native 分发:三大主力平台(windows-x86_64 / linux-x86_64 / osx-aarch_64)
 * 随 mod 内嵌;冷门两个(linux-aarch_64 / osx-x86_64)首连时从 Maven Central
 * 下载,以编译期内置的 SHA-256 校验。校验失败或无官方包的平台,QUIC 不可用,
 * 上层保持 WS 可用(显式配置切换,不自动回退)。</p>
 */
public final class QuicTransportProcess implements TransportProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuicTransportProcess.class);
    /** netty 4.2 主线版本;classes 与 native 必须严格同版(quiche/BoringSSL ABI 锁定)。 */
    public static final String NETTY_VERSION = "4.2.17.Final";
    private static final String BUNDLE_RESOURCE_ROOT = "/META-INF/teamviewer/quic/";
    private static final String MAVEN_URL =
            "https://repo1.maven.org/maven2/io/netty/netty-codec-native-quic/"
                    + NETTY_VERSION + "/netty-codec-native-quic-" + NETTY_VERSION + "-";
    /** 门适配核(仅这两个类 + 内部类,构建期打成 teamviewer-quic-core.jar):
     *  必须在 child 内定义,故以资源 jar 进 child URL,而非主类路径。 */
    private static final List<String> BUNDLE_CLASS_JARS = List.of(
            "teamviewer-quic-core.jar",
            "netty-codec-classes-quic-" + NETTY_VERSION + ".jar",
            "netty-common-" + NETTY_VERSION + ".jar",
            "netty-buffer-" + NETTY_VERSION + ".jar",
            "netty-transport-" + NETTY_VERSION + ".jar",
            "netty-resolver-" + NETTY_VERSION + ".jar",
            "netty-codec-base-" + NETTY_VERSION + ".jar",
            "netty-handler-" + NETTY_VERSION + ".jar");
    /** 冷门平台运行时下载;digest 锁定本版本 jar,升级 NETTY_VERSION 时同步更新。
     *  未列出的平台(native 随 mod 内嵌)从资源解包,校验即内嵌产物本身。 */
    private static final Map<String, String> DOWNLOADABLE_NATIVES = Map.of(
            "linux-aarch_64",
            "350ba4c87cb8d2066d895f511605e1479da2c25e2bf5c2cf5dcb4cd34fcaae75",
            "osx-x86_64",
            "d8c48589adfafd798d1c4b3996d9b19dc7bd9f1785524b821388e2db73a52dd2");

    /** 每个进程只加载一次:native 已绑定到 loader,无法卸载。 */
    private static final AtomicReference<Object> sharedCore = new AtomicReference<>();
    private static final AtomicReference<IOException> loadFailure = new AtomicReference<>();

    private final Path cacheDirectory;

    /**
     * @param cacheDirectory 传输层缓存目录(通常为配置目录下的子目录),用于解包
     *                       netty bundle 与缓存下载的 native;不可写时 QUIC 不可用。
     */
    public QuicTransportProcess(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    @Override
    public SocketProcess connect(String uri, TransportOptions options, TransportListener listener) {
        Objects.requireNonNull(listener, "listener");
        try {
            Object core = ensureCore();
            Method connect = core.getClass().getMethod(
                    "connect", String.class, TransportOptions.class, TransportListener.class);
            // 包私有类跨 loader 反射进入:setAccessible 越过类级可见性检查。
            connect.setAccessible(true);
            return (SocketProcess) connect.invoke(core, uri, options, listener);
        } catch (Exception error) {
            // 与 OkHttp 门同约定:connect 不抛受检异常,同步失败走 onFailure,
            // 由 NetworkManager 统一记录并调度重连。
            listener.onFailure(new IOException(
                    "QUIC transport failed to start: " + rootMessage(error), error));
            return DEAD_SOCKET;
        }
    }

    /** 启动失败后的占位会话:静默 no-op,不再向监听器重复报错。 */
    private static final SocketProcess DEAD_SOCKET = new SocketProcess() {
        @Override
        public void send(byte[] payload) {
        }

        @Override
        public void close(int statusCode, String reason) {
        }
    };

    /** 当前平台是否有官方 native 包(内嵌或可下载)。 */
    public static boolean isSupportedPlatform() {
        return platformClassifier() != null;
    }

    /**
     * 当前平台 classifier;无官方包(musl/armv7/BSD 等兜底)返回 null。
     * classifier 命名与 netty-codec-native-quic 发布物一致。
     */
    public static String platformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x86_64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("linux")) {
            if (x86_64) return "linux-x86_64";
            if (arm64) return "linux-aarch_64";
            return null;
        }
        if (os.contains("win") && x86_64) {
            return "windows-x86_64";
        }
        if ((os.contains("mac") || os.contains("darwin"))) {
            if (arm64) return "osx-aarch_64";
            if (x86_64) return "osx-x86_64";
        }
        return null;
    }

    private Object ensureCore() throws IOException {
        Object core = sharedCore.get();
        if (core != null) {
            return core;
        }
        IOException failure = loadFailure.get();
        if (failure != null) {
            throw failure;
        }
        synchronized (QuicTransportProcess.class) {
            core = sharedCore.get();
            if (core != null) {
                return core;
            }
            if (loadFailure.get() != null) {
                throw loadFailure.get();
            }
            try {
                core = buildCore();
                sharedCore.set(core);
                return core;
            } catch (IOException error) {
                loadFailure.set(error);
                throw error;
            }
        }
    }

    private Object buildCore() throws IOException {
        String classifier = platformClassifier();
        if (classifier == null) {
            throw new IOException("QUIC transport is not available on this platform ("
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ")");
        }
        if (cacheDirectory == null) {
            throw new IOException("QUIC transport cache directory is not configured");
        }
        Path bundleDirectory = cacheDirectory.resolve("netty-" + NETTY_VERSION);
        Files.createDirectories(bundleDirectory);
        cleanupStaleVersions(cacheDirectory, bundleDirectory.getFileName().toString());

        List<Path> jars = new ArrayList<>(BUNDLE_CLASS_JARS.size() + 1);
        for (String jarName : BUNDLE_CLASS_JARS) {
            jars.add(materialize(jarName, bundleDirectory, null));
        }
        String nativeJarName = "netty-codec-native-quic-" + NETTY_VERSION + "-" + classifier + ".jar";
        jars.add(materialize(nativeJarName, bundleDirectory, DOWNLOADABLE_NATIVES.get(classifier)));

        URL[] urls = jars.stream()
                .map(QuicTransportProcess::toUrl)
                .toArray(URL[]::new);
        ClassLoader parent = QuicTransportProcess.class.getClassLoader();
        String quicPackage = QuicTransportProcess.class.getPackageName();
        String coreClassName = quicPackage + ".QuicTransportCore";
        // child-first 只对本包类与 io.netty.** 生效:隔离 MC 自带 netty 4.1,
        // 同时让门适配核及其 codec 在 child 内定义(共享 io.netty 类型身份);
        // 本壳与接口(SocketProcess 等)经 parent fallback 保持同一类身份。
        URLClassLoader loader = new URLClassLoader(urls, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        if (name.startsWith("io.netty.") || name.startsWith(quicPackage + ".")) {
                            try {
                                loaded = findClass(name);
                            } catch (ClassNotFoundException delegated) {
                                loaded = parent.loadClass(name);
                            }
                        } else {
                            loaded = parent.loadClass(name);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
        try {
            java.lang.reflect.Constructor<?> constructor = Class.forName(coreClassName, true, loader)
                    .getDeclaredConstructor();
            // 包私有类跨 loader 反射进入:child 定义副本仅由本壳可达。
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable error) {
            try {
                loader.close();
            } catch (IOException ignored) {
            }
            throw new IOException("QUIC core failed to initialize: " + rootMessage(error), error);
        }
    }

    /** 解包内嵌 jar 或下载缺失 native。内嵌 jar 无版本指纹可用:每次进程内
     *  首次加载都从资源原样重写(mod 升级后旧缓存内容必须被替换,否则
     *  child 会拿着上一版的适配核字节码跑)——单次进程只发生一次,开销可忽略。
     *  下载 native 以 SHA-256 锁定,已校验通过的缓存直接复用。 */
    private static Path materialize(String jarName, Path bundleDirectory, String expectedSha256)
            throws IOException {
        Path target = bundleDirectory.resolve(jarName);
        String resourcePath = BUNDLE_RESOURCE_ROOT + jarName;
        if (expectedSha256 == null) {
            try (InputStream input = QuicTransportProcess.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IOException("QUIC bundle resource is missing: " + resourcePath);
                }
                atomicCopy(input, target);
                return target;
            }
        }
        // 可下载平台:已缓存且校验通过 → 复用;未缓存或校验失败 → 首连下载
        if (Files.isRegularFile(target) && expectedSha256.equals(sha256(target))) {
            return target;
        }
        LOGGER.info("Downloading QUIC native bundle {} (about 2.5 MiB) from Maven Central", jarName);
        HttpURLConnection connection =
                (HttpURLConnection) new URL(MAVEN_URL + classifierPart(jarName) + ".jar")
                        .openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        int status = connection.getResponseCode();
        if (status != 200) {
            throw new IOException("QUIC native download failed with HTTP " + status);
        }
        Path download = target.resolveSibling(target.getFileName() + ".download");
        try (InputStream input = connection.getInputStream()) {
            atomicCopy(input, download);
        } finally {
            connection.disconnect();
        }
        String digest = sha256(download);
        if (!expectedSha256.equals(digest)) {
            Files.deleteIfExists(download);
            throw new IOException("QUIC native download failed SHA-256 verification for " + jarName);
        }
        Files.move(download, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String classifierPart(String nativeJarName) {
        // netty-codec-native-quic-<version>-<classifier>.jar → <classifier>
        String prefix = "netty-codec-native-quic-" + NETTY_VERSION + "-";
        return nativeJarName.substring(prefix.length(), nativeJarName.length() - ".jar".length());
    }

    private static void atomicCopy(InputStream input, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            input.transferTo(output);
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    digest.update(chunk, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    private static void cleanupStaleVersions(Path cacheDirectory, String currentVersionDirectory) {
        try (Stream<Path> entries = Files.list(cacheDirectory)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("netty-"))
                    .filter(path -> !path.getFileName().toString().equals(currentVersionDirectory))
                    .forEach(path -> {
                        try (Stream<Path> walk = Files.walk(path)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(child -> child.toFile().delete());
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static java.net.URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException error) {
            throw new IllegalArgumentException("Path to URL conversion failed: " + path, error);
        }
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) {
            error = error.getCause();
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
