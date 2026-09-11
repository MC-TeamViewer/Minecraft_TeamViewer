# Changelog

## v0.10.0-alpha.6-proto0.9.0 - 2026-09-11

- 诊断日志:QUIC 下行泵安装时打印读到的 ALPN 与是否安装 zstd 解码器;首字节到达
  打印一条;连接建立打印本地地址(定位重叠连接/幽灵连接)。用于定位"业务握手中"
  卡死(服务端 writer_ended、mod 收不到 ack)的确切环节。


## v0.10.0-alpha.5-proto0.9.0 - 2026-09-10

- QUIC 门接入下行 movement datagram 与 zstd 字典套(协议 `0.9.0-alpha.6`,后端需 `1.2.0-alpha.10` 及以上):
  - ALPN 偏好序改为 `[teamviewrelay/v1+zstd-dict, teamviewrelay/v1+zstd, teamviewrelay/v1]`;
    协商 +zstd-dict 时服务端经门控下行流(服务端第 2 条单向流)下发
    `DatagramDictOffer`,装字典后经客户端第 1 条单向流(全连接仅此一条,后续
    ready 帧依次写入同一流)回 `DatagramDictReady`。门控流分帧/protobuf 解析
    失败按协议违规显式断连;字典内容异常只拒装不回执(服务端维持独立压缩,自愈)。
  - 连接级 datagram 由排空改为消费:按「激活字典 → 前任字典(current+previous
    双字典轮换宽限)→ 无字典独立单帧」顺序解码,全部失败按丢包静默丢弃。解出
    的裸 `WireEnvelope` 恒为 `{WebMap, Patch}`(消费端按载荷类型识别、与连接
    角色无关),`Patch.players` 绝对值 upsert 复用现有 patch 应用路径合并本地
    玩家表。仅在握手回执 `downlink_channels_accepted` 含 MOVEMENT(alpha.6
    字段 20)后生效;可靠流低频保底不变。
  - 握手声明补全:Player 握手现在真正写入 `unreliable_channels`(alpha.5 字段
    13,此前 `declaresUplinkMovementDatagram` 仅置位未编码,上行声明实际从未
    上线),并新增 `accepts_channels`(字段 14)下行消费声明。
  - 新增 `MovementDatagramDecoder`(zstd-jni,字典训练/压缩侧仅测试使用)。
- Mod 版本升级为 `v0.10.0-alpha.5`;网络协议仍为 `0.9.0`(字段纯增量)。

## v0.10.0-alpha.2-proto0.8.0 - 2026-09-08

- 两扇门接入压缩套下行 zstd（`enableCompression` 生效，协议需后端 `1.2.0-alpha.6` 及以上）：
  - WS 门：握手请求新增 `Sec-WebSocket-Protocol: teamviewrelay.zstd.v1, teamviewrelay.plain.v1`
    （与 permessage-deflate 同头竞争，回退链 zstd → deflate → 明文不变；旧后端忽略该头照常走
    deflate）。服务端回执 `teamviewrelay.zstd.v1` 即协商成立：每条下行 binary 消息 = 连续 zstd
    流的一个压缩块，经持久解压器解码后交付；上行恒 plain。zstd 与 permessage-deflate 互斥，
    服务端同回执两者按协议违规断连；回执列表外子协议同样显式报错（RFC 6455）。
  - QUIC 门：ALPN 偏好序改为 `[teamviewrelay/v1+zstd, teamviewrelay/v1]`；协商为 +zstd 时下行
    帧载荷 = 连续 zstd 流的压缩块，解压后经回调交付。不提供 `+zstd-dict`：本门收不到 datagram
    （netty-quic 未开 datagram 选项），字典模式无用武之地。
  - 语义与后端/网页脚本同款（TeamViewRelay-Protocol README"压缩套"）：压缩套为单向，仅下行
    （服务端 → 客户端）压缩；解压失败属协议违规，显式断连不静默吞帧；单次交付上限 8 MiB、
    解压窗口上限 2^23，超限拒绝。
- 新增共享解压器 `ZstdStreamDecoder`（zstd-jni `1.5.7-16` 连续模式）：置于主包以便 WS 门与 QUIC
  适配核共享同一类身份；非阻塞排干语义适配 WS 读线程与 QUIC netty IO 线程。zstd-jni 以未重定位
  嵌套 jar 随四份构建分发（vanilla 未捆绑 zstd，无冲突；Minecraft 1.20.5+ 区块压缩用的是
  lz4-java）。
- Mod 版本升级为 `v0.10.0-alpha.2`；网络协议仍为 `0.8.0`（压缩套经门原生载体协商，envelope
  结构无变化）。

## v0.10.0-alpha.1-proto0.8.0 - 2026-09-08

- 新增裸 QUIC 传输门（客户端侧，实验性）：服务器地址以 `quic://host:port` 填写即走 QUIC 门，其余
  （`ws://`/`wss://`）仍走 WebSocket；每次连接按 URL scheme 显式选择，失败不自动回退。门会话约定与
  TeamViewRelay-Protocol README"传输门约定"一致：客户端 1 条双向流上行 + 服务端 1 条单向流下行，
  `[varint LEB128 长度][payload]` 分帧（上限 8 MiB），ALPN `teamviewrelay/v1`，10 秒首帧握手超时。
- 实现为 child-first 类加载器隔离的 netty 4.2（`4.2.17.Final`）bundle：quiche native 以 JNI
  RegisterNatives 按原名绑定、不可重定位，而未重定位的 netty 4.2 又与 Minecraft 自带 netty 4.1
  同包名冲突——因此编译类 jar、适配核 jar（`teamviewer-quic-core.jar`）与平台 native jar 以资源形式
  随 mod 分发，运行时解包进只对 `io.netty.**` 与适配核包 child-first 的独立 ClassLoader，业务接口
  （SocketProcess 等）与主类路径保持同一类身份。
- netty native 分发：三大主力平台（windows-x86_64 / linux-x86_64 / osx-aarch_64）内嵌随 mod；
  冷门两个（linux-aarch_64 / osx-x86_64）首连时从 Maven Central 下载并以编译期内置 SHA-256 校验；
  校验失败或无官方包的平台 QUIC 不可用（保持 WS 可用，显式切换）。内嵌 bundle 每次进程首次加载
  都从 mod 资源原样重写缓存目录，mod 升级后不会复用旧字节码。
- TLS 信任复用 `allowInsecureTls` 语义：开启时信任所有证书（自签/试用场景），关闭时走系统信任库；
  系统代理对 QUIC（UDP）无语义，忽略；`enableCompression` 在 zstd 压缩落地前忽略。
- 连接状态 `WS_CONNECTED_HANDSHAKING` 更名为 `CONNECTED_HANDSHAKING`，对应界面文案键同步为
  `connection.status.connected_handshaking`（"Connected, handshaking" / "已连接，业务握手中"）。
- Mod 版本升级为 `v0.10.0-alpha.1`；网络协议仍为 `0.8.0`（QUIC 门需要后端 `1.2.0-alpha.5` 及以上）。

## v0.9.0-proto0.8.0 - 2026-09-07

- 网络协议升级为 `0.8.0`：外部数据源可在握手中声明数据集格式、覆盖范围和 stale 阈值，并通过独立的
  玩家目录查询与关系查询消息按需发布完整玩家目录、组织节点和组织关系边；关系图不进入实时
  `SnapshotFull`。`teamviewer.v1` 包名与既有消息保持不变，0.7.x 的服务端和客户端会忽略新增字段，
  继续使用实时 Tab、Tab 历史和本地关系标记。
- 客户端新增外部玩家关系数据集的按需同步：每 15 秒或关系数据变化时，收集本机、远程在线、离线历史
  和 Tab 玩家的 UUID，按服务端能力上限分批分块查询缺失关系；本机不可用或查询失败时回退为目录查询。
  查询出错或无数据的条目每 30 秒重试一次，结果缓存为版本化不可变快照，并保留服务端的 `stale`
  过期标记。
- 敌我识别仲裁在“本地关系分类”之后、“后端自动标记”之前插入“外部数据集”优先级层：后端手工标记
  与本地插件关系仍然优先，外部关系覆盖后端自动标记；HUD、世界渲染和可攻击性判定共用同一仲裁结果。
- 支持服务端 `PlayerReportPolicy` 报告策略建议：只有健康且完整的外部在线目录才会建议抑制 Tab 上报，
  客户端接受 `SUPPRESS` 建议后停止发送 `tab_players_patch`；玩家位置与实体上报不受影响。
- 新增 `externalRelationSyncMode` 配置项（默认 `on_demand`，可设为 `off` 完全关闭外部关系查询）。
- 同步 Protobuf Java 生成器与运行库到 `4.36.1`。
- Mod 版本升级为 `v0.9.0`，网络协议升级为 `0.8.0`；最低兼容协议仍为 `0.6.1`。

## v0.8.7-proto0.7.1 - 2026-08-28

- Minecraft 26.1.2 + JourneyMap 6.0.5 不再为小地图视口外的 Relay 在线/离线玩家执行离屏标签布局，
  避免近万临时玩家路径点令 `GuiRenderState.hasIntersection` 占满渲染线程；视口内标记与全屏地图不受影响。
- JourneyMap 玩家标记、世界信标、共享报点和用户路径点保持独立；关闭在线玩家地图标记会立即清理其受管对象。
- 握手上报的程序版本统一包含 Mod 与 Minecraft 版本，例如
  `team-view-relay-mod-v0.8.7-proto0.7.1-mc26.1.2`；同时修正 Fabric Mod 元数据版本查询 ID。
- JourneyMap 内置插件版本升级为 `1.4.3`，Mod 版本升级为 `v0.8.7`；网络协议仍为 `0.7.1`，
  最低兼容协议仍为 `0.6.1`。

## v0.8.6-proto0.7.1 - 2026-08-27

- 将服务端摘要的规范化、排序与散列移出 Minecraft 主线程；摘要请求串行执行并合并积压请求，
  避免大型战区缓存造成约 10 秒一次的客户端卡顿。
- 本地玩家死亡、退出世界或暂时不可上报时会清除本连接的玩家来源状态，避免旧位置继续参与来源仲裁。
- Mod 版本升级为 `v0.8.6`；网络协议仍为 `0.7.1`，最低兼容协议仍为 `0.6.1`。

## v0.8.5-proto0.7.1 - 2026-08-23

- Battle chunk 的 snapshot、patch、refresh、keepalive 与客户端缓存统一使用结构化
  `dimension/chunkX/chunkZ` 引用，不再把重复坐标拼进公开 map key 或 data。
- 协议 0.7.1 摘要改为排序后的 `{ref,data}` 条目合同；连接旧 0.7.0 服务端时仍使用正式 legacy 合同，
  并兼容 Rust 0.7.0 曾经漏掉坐标字段的缺陷摘要，避免每 10 秒触发全量重同步。
- 同步 Protobuf Java 生成器与运行库到 `4.36.0`，避免重新生成 binding 后因运行库旧于生成代码而拒绝加载。
- Mod 版本升级为 `v0.8.5`，网络协议升级为 `0.7.1`；最低兼容协议仍为 `0.6.1`。

## v0.8.4-proto0.7.0 - 2026-08-22

- JourneyMap 在线玩家、离线玩家、玩家报点、网页/管理端报点和其他共享路标分别使用五个独立路径点组；
  新组采用稳定 GUID，并按 GUID、角色元数据、旧名称和旧标签依次复用已有组，避免每次启动继续创建同名空组。
- 已存在的重复路径点组不会被自动删除；用户可以在 JourneyMap 中自行清理，插件会确定性地保留并复用一个匹配组。
- Minecraft 26.1.2 的 JourneyMap 插件支持对每个 Relay 路径点组分别控制世界路径点、旋转光束、静止光束
  和最大显示距离，不修改 JourneyMap 的全局客户端设置；不支持该接口的版本会提示并回退到原有实现。
- 修复 Fabric All-in-One 根元数据遗漏 JourneyMap Mixin 配置，导致组级信标选项全部禁用的问题；构建现在会校验
  Mixin 配置插件和目标类均存在于对应内嵌 Adapter。
- 修复普通 `./gradlew build` 误触发 Fabric/NeoForge All-in-One 发布组装，并因尚未收集全矩阵 Jar 而失败的问题；
  AIO 完整组装与校验仍由显式项目任务和正式 `task build` 发布流程执行。
- 修复 JourneyMap 在有效游戏会话中返回空 `worldId` 或空路径点组列表时，三个联动能力被错误标记为
  “外部入口未就绪”的问题；路径点组存储重建后仍会恢复相同稳定 GUID。
- 修复打开集成插件页面时，JourneyMap 能力探测每帧抓取并反射展开完整世界、玩家和实体快照而卡死渲染线程的问题。
  Lua 宿主新增轻量 `environment.play_session_ready()` 生命周期接口，能力探测不再读取完整世界快照。
- JourneyMap 内置插件版本升级为 `1.4.2`，Mod 版本升级为 `v0.8.4`；网络协议仍为 `0.7.0`，
  协议子模块、后端和网页脚本无需升级。

## v0.8.2-proto0.7.0 - 2026-08-22

- 大量离线玩家记录改为按维度的空间索引；世界渲染只枚举渲染距离附近的候选项，并缓存离线玩家标签。
  新建配置的渲染距离默认值调整为 512，已有配置文件中的用户值保持不变。
- 在线玩家、离线最后位置和 Tab 历史分别发布不可变版本化快照。无关实体/路标更新、重复离线记录和
  未变化的 Tab 不再触发全量筛选、关系分类或原生地图同步。
- Tab 历史查询会记住本连接中已完成的空结果，避免对同一批离线 UUID 重复请求；历史实际变化后才刷新关系。
- JourneyMap、Xaero 等原生地图投影拆分在线与离线更新通道：在线位置高频变化不会再重建离线玩家标记。
- Lua 热路径为常用玩家、坐标和关系类型采用显式转换，避免逐字段反射；修复极端世界坐标下空间索引遍历的整数溢出。
- Mod 版本升级为 `v0.8.2`；网络协议保持 `0.7.0`，协议子模块、后端和网页脚本无需升级。

## v0.8.1-proto0.7.0 - 2026-08-22

- “Tab 标签敌我识别”插件可将本城、友城、敌对/交战城镇、友方成员和原始采集时间复制为
  版本化 JSON 关系档案，供 Web 在本机导入；档案不会写入 TeamViewRelay 房间。
- Lua 插件 API 新增受大小、深度和条目数限制的 `tv.copy_json_to_clipboard(table)`，各 Fabric 与
  NeoForge Adapter 通过对应 Minecraft 原生剪贴板实现该能力。
- JourneyMap API v2 会创建持久化的 `TeamViewRelay Online Players`（`[TV] Online`）和
  `TeamViewRelay Offline Players`（`[TV] Offline`）路径点组；不支持路径点组的 API 会回退到默认路径点，
  不影响地图联动。
- JourneyMap 26.1.2 及同类完整 API 将在线/离线 Relay 玩家分别拆分为地图标记与世界信标四个即时开关。
  修复此前关闭在线标记和信标后，离线 `[TV Last]` 路标仍留在地图上的问题。
- JourneyMap 旧 API 会回退为在线与离线路标两个有效开关，避免显示无法兑现的地图/世界细分选项。
- Xaero 地图联动新增三个即时开关：在线玩家世界地图、离线玩家世界地图、离线玩家小地图最后位置；
  关闭时仅清理由 TeamViewRelay 管理的 tracker 或 `[TV Last]` 路标，不影响用户路径点和共享 Relay 路标。
- Mod 版本升级为 `v0.8.1`；网络协议仍为 `0.7.0`，后端和协议仓库无需升级。

## v0.7.0-proto0.6.5 - 2026-08-20

- 公共 API 增加远程玩家坐标来源、来源类型、显示名称和位置分辨率。
- 网络协议升级为 `0.6.5`，最低兼容版本仍为 `0.6.1`。
- 完整快照和增量更新均解码并保留来源元数据，供 AimAssist 等客户端判断数据可信度。
- `TeamViewRelayApi.API_VERSION` 升级为 3，同时保留旧的 `RemotePlayerSnapshot` 构造器兼容二进制调用。

本文件记录 TeamViewRelay Mod 的用户可见变更。版本号格式为
`v<Mod 版本>-proto<协议版本>`；协议版本未变化时，后端和网页脚本不需要跟随 Mod 一起发布。

## v0.6.2-proto0.6.4 - 2026-08-20

### 离线玩家显示修复

- 网络协议继续使用 UTC 毫秒时间，游戏内世界渲染、JourneyMap 和 Xaero 标记改为按客户端本地时区显示。
- 修复 Xaero 离线玩家标记在断开连接、切换世界或 waypoint 会话后无法删除的问题。
- 修复重新上线后仍显示自己的离线历史标记的问题，并保留用户自有 waypoint。

## v0.6.1-proto0.6.4 - 2026-08-19

### 离线玩家最后位置

- 接收权威 Squaremap 外部数据源维护的离线玩家最后位置和 Unix UTC 毫秒时间；该功能默认关闭。
- 世界渲染可分别显示橙色方框和追踪线，并在 512 方块内以批量矢量线段显示姓名与 UTC 时间，
  避免远距离历史玩家带来持续的文字渲染开销。
- JourneyMap、Xaero Minimap 和 Xaero World Map 使用独立标记显示离线历史；玩家重新上线、断线、
  切换世界或关闭功能时会清理历史标记，不与实时玩家对象混用。
- Common SDK API 升级至 v2，新增 `TeamViewRelayApi.lastSeenPlayers()` 不可变只读快照。

### 协议与兼容性

- 网络协议升级为 `0.6.4`，最低兼容版本仍为 `0.6.1`。
- 协议低于 `0.6.4` 的客户端不会收到离线历史 scope；实时玩家、实体、路标和战局数据继续兼容。
- 历史玩家使用独立仓库、投影能力与渲染规划，不改变现有在线玩家的过期和残留清理语义。

## v0.6.0-proto0.6.2 - 2026-08-18

### 敌我识别与扩展 API

- Tab 标签敌我识别支持从 `/town` 和 `/t` 的聊天输出自动导入城镇、盟友、敌对与居民关系；
  插件页显示当前自动识别结果、采集时间和清空操作。
- 新增手动/自动关系采用模式，默认“仅自动”，手动标签配置与自动采集结果彼此独立保存。
- Common 对外提供稳定的远程玩家、关系来源和可攻击性只读 API，其他 Mod 无需依赖网络或 UI 实现。

### 性能与架构

- Fabric 1.18.2-1.21.10 与 NeoForge 1.20.2-1.21.10 将方框、追踪线、圆、光柱和平面按渲染状态批量提交，
  draw call 数量不再随远程玩家数量线性增长。
- Minecraft 1.21.11 和 26.x 保留原生 Gizmo 管线，并使用自定义 `BatchGizmo` 按遮挡模式合并命令。
- 配置页与插件管理页拆分为独立会话；GameClient、RuntimeGateway、事件注册、配置/插件页面和渲染器采用
  core/shim 分层，删除大量版本复制代码并强化 Adapter SDK 边界。
- Mod 版本升级为 `v0.6.0`；网络协议仍为 `0.6.2`，最低兼容版本仍为 `0.6.1`。

## v0.5.0-proto0.6.2 - 2026-08-04

比较范围：[`v0.4.14-proto0.6.2`（`f599b1c`）](https://github.com/MC-TeamViewer/Minecraft_TeamViewer/commit/f599b1c6aedd7e8150c44485d6ba3d1d1c826461)
至 `v0.5.0-proto0.6.2`。

### Minecraft、Loader 与发布产物

- Fabric 从只验证 Minecraft 1.21.8 扩展为覆盖 `1.18`–`26.2` 之间的全部 31 个正式版本。
  17 个独立 Jar 按真实 API 兼容范围划分，同时提供一个覆盖全部 Fabric 目标的 All-in-One。
- 新增 NeoForge 支持，19 个独立 Jar 覆盖 Minecraft `1.20.2`–`26.2` 的全部 21 个正式版本；
  补齐仅有 beta NeoForge 的 `1.20.3`、`1.20.5`、`1.21.2`、`1.21.6`、`1.21.7` 和 `1.21.9`。
  `1.20.1` 留给后续独立 Forge 版本，不引入旧 Forge 命名空间或双 FML 入口。
- 新增实验性的 NeoForge All-in-One。一个 Java 17 外壳按运行时版本选择唯一的重定位
  `GAMELIBRARY` adapter、Factory 和 Mixin；19 个独立 Jar 继续作为回退选择。
- NeoForge beta 目标分离构建 pin、玩家运行范围和发布渠道；玩家可更新同一 Minecraft 版本线的
  Loader，定时 CI 会从官方 Maven 元数据解析并构建该线最新版本。26.2 基线更新到 `26.2.0.48-beta`。
- AIO 外壳同时提供 FML 1 的 `META-INF/mods.toml` 与现代 FML 的
  `META-INF/neoforge.mods.toml`，并分别使用对应 dependency schema。独立包则按构建前端只保留
  一份元数据，避免 NeoForge 1.20.6 的 FML 3 误读 FML 4 描述文件而拒绝加载。
- 根据 Minecraft 目标分别使用 Java 17、21 或 25；公共运行时保持 Java 17 ABI，版本 Adapter
  可使用目标游戏要求的 Java 版本。
- Fabric 与 NeoForge 发布物都区分完整 standalone 与内部 slim adapter；NeoForge slim adapter
  经过精确 class 重定位后只用于 AIO JarJar 组装。
  构建会检查 Mod ID、Mixin、ServiceLoader、嵌套依赖、字节码版本、目标元数据和 Adapter 哈希。
- Fabric 的公开 Mod ID 仍为 `team-view-relay`；NeoForge 因 FML 命名规则使用
  `team_view_relay`，配置文件名和协议身份不变。

### 集成插件与地图联动

- 新增 Lua 集成插件运行时和稳定能力注册表。插件可提供远程玩家投影、共享路标或战局地图数据源，
  并明确报告可用、未安装、不支持、未实现、入口未就绪或加载失败等状态。
- 内置 NodeMC、SimMC、Xaero 和 JourneyMap 插件，并提供可执行的中英双语 Lua Adapter 示例。
  JourneyMap 同时适配旧版 API v1、API v2 及 26.2 的接口变化；Xaero 包含旧版本兼容入口。
- 新增完整的集成插件管理界面：查看能力和诊断信息、修改插件设置、启停、重新扫描、复制内置示例为
  自定义插件，以及卸载、恢复或永久删除自定义插件。
- 战局地图由固定模式改为可选择的数据源。NodeMC 计分板与 SimMC 原生区域数据共用历史对齐、坐标投影、
  差异检测、缓存保留和 keepalive 上传流程。
- JourneyMap/Xaero 的远程玩家和共享路标同步统一由能力接口管理；插件停用、功能关闭、断线或切换世界时
  会清理其创建的原生标记。

### 实体上传、界面与渲染

- 新增实体上传专用设置页，可选择自适应或固定采集周期。自适应模式会在实体数量较大时自动降低
  采集频率，固定模式仍受服务器协商的最小上报周期约束。
- 新增实体类型与自定义名称的精确白名单/黑名单，可在游戏内分页新增和删除规则；规则在采集阶段应用，
  不需要先构造完整实体对象。
- 配置 UI 改为公共页面模型与各版本原生控件宿主，统一网络、显示、颜色、路标、实体上传、抓包和插件页面；
  无效颜色字段会保留旧值，同时允许其余合法字段继续保存。
- HUD 与世界渲染改为公共 Planner 生成不可变帧、Loader Adapter 执行原生绘制，保证 Fabric 与 NeoForge
  共享相同的显示决策。
- 快速报点使用目标版本的原生射线检测，正确处理交互距离、最大标记距离和方块遮挡。

### 性能、稳定性与协议处理

- 重构高频实体上报：客户端线程单次遍历已加载实体并写入可复用的结构化数组，后台单线程完成状态比较、
  patch 生成和 Protobuf 编码，减少对象分配和游戏线程占用。
- Tab 玩家列表改为独立低频快照和增量比较，不再在每次世界状态采集中重复读取；远程玩家、路标和战局地图
  协调器共享一次轻量世界快照。
- 只有确实需要实体定位时才枚举世界实体；上报、HUD、渲染和地图集成的时钟与清理生命周期集中管理。
- 重连预算改为原子扣减，避免并发重连丢失次数或降到零以下；WebSocket 扩展参数的非法数字和越界值现在
  会转换成带原始值与原因的协议错误。
- 精简 WebSocket 帧回调和无意义队列分支，保留统一的传输流量统计；修正配置逐字段保存、空值处理及
  legacy Gson record 反序列化。

### 架构、构建与质量保障

- 引入 Loader 中立的 Common Adapter SDK、`ClientAdapterBundle`、`ServiceLoader` 启动层和 Adapter TCK。
  网络、配置、同步、HUD、渲染和战局地图业务只保留一份，各版本仅转换原生 Minecraft/Loader API。
- Fabric 与 NeoForge 均采用 `shared -> compat -> version` 三级源码解析。现代 ModDev 和 legacy UserDev
  共同消费唯一的 `neoforge-adapter` 源码树，相同 API 实现可跨构建边界复用，具体版本目录只保留必要覆盖。
- 新增 `source-plan`，可追踪每个目标的有效文件来自 shared、哪个 compat 层或 version override；
  compat 层发生同路径冲突、空层、冗余覆盖或目标遗漏时直接构建失败。
- 所有 NeoForge 构建统一使用根 Gradle 9.5.1 wrapper。Minecraft 1.20.x 的 UserDev 与 1.21+
  的 ModDevGradle 保持独立工程边界，但不再维护第二套 Gradle wrapper。
- `task build` 现在会按 CPU、cgroup 和可用内存并行构建各版本 adapter 与 Fabric runtime；
  每个目标使用独立 Gradle project cache，`JOBS=1` 可恢复串行排障。
- Minecraft 目标、精确 Fabric runtime、依赖版本、Java 要求和发布文件名集中到统一清单；Taskfile、Gradle、
  GitHub Actions、CodeQL 与 Qodana 使用同一套目标信息和本地构建准备 Action。
- 新增公共业务、Adapter TCK、插件、实体管线、配置 UI、战局地图、协议、传输、HUD、渲染以及目标清单测试；
  完整矩阵会逐个编译 31 个 Fabric 正式 runtime 和所有 NeoForge 目标。

### 主要修复

- 修复 Fabric 1.18.2 的渲染矩阵/API 差异，以及跨版本报点距离与遮挡判断。
- 修复 Fabric 1.21.1、26.1、26.1.1 和 26.2 等 JourneyMap 入口注解、Waypoint API 与构建检查问题。
- 修复 SimMC 战争地图区域读取、位置历史与投影问题，以及 Lua/外部插件能力注册、设置持久化和生命周期清理。
- 修复插件使用旧 Gson 时对 Java record 的反序列化兼容问题。
- 修复多个配置解析、空值、并发状态、无界队列语义和包目录问题，并清理未使用缓存及重复版本源码。
- 修复多版本 Fabric/NeoForge 构建、All-in-One 打包、Qodana 项目模型与完整兼容矩阵 CI 问题。
- NeoForge Mixin 的 Java compatibility level 现在由目标工具链生成；legacy UserDev 的严格 Adapter TCK
  使用与 standalone 相同的扁平公共运行时，避免开发运行漏载 bootstrap 或 JarJar 类。
- 为所有 standalone 和 All-in-One 外壳补充跨版本 `pack.mcmeta`，修复 NeoForge 1.20.2 将
  `mod:team_view_relay` 判定为无效 ResourcePack 的启动告警。
- NeoForge 的 `pack.mcmeta` 同时声明旧版 `supported_formats` 与新版 `min_format` / `max_format`，
  避免 Minecraft 1.21.11 和 26.2 在每次资源仓库刷新时重复打印元数据解析异常。
- 将 NeoForge 元数据引用的 Mod 图标放到 Jar 根目录，修复 1.20.2 在 Mod 列表中选择
  TeamViewRelay 时因旧版路径校验不接受 `assets/teamviewer/icon.ico` 而崩溃。

### 升级说明

- 网络协议仍为 `0.6.2`，最低兼容协议仍为 `0.6.1`。本次只发布 Minecraft Mod；后端、网页脚本和
  协议仓库不需要跟随升级。
- Fabric 玩家应在一个匹配版本的独立版和 Fabric All-in-One 之间二选一；NeoForge 玩家应在对应独立版
  和实验性 NeoForge All-in-One 之间二选一。两种 Loader 都不能同时安装 standalone 与 AIO，且不存在
  跨 Loader 通用 Jar。
- 旧配置仍从 `config/team-view-relay.json` 读取；原战局地图模式会回落到默认 NodeMC 数据源，之后可在
  配置页选择实际可用的插件数据源。
- 自定义插件位于 `config/team-view-relay/plugins/`；内置插件只读，只能停用或复制为自定义插件。

## v0.4.14-proto0.6.2

- 上一个公开版本。后续完整提交历史可通过 Git 标签比较查看。
