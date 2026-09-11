package fun.prof_chen.teamviewer.main_code.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationIds;
import fun.prof_chen.teamviewer.main_code.client.entity.EntityUploadFilter;
import fun.prof_chen.teamviewer.main_code.network.abstraction.ConfigGateway;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class Config implements ConfigGateway {
    public static final String TRACER_START_CROSSHAIR = "crosshair";
    public static final String TRACER_START_TOP = "top";
    public static final String WAYPOINT_UI_BEACON = "beacon";
    public static final String WAYPOINT_UI_RING = "ring";
    public static final String WAYPOINT_UI_PIN = "pin";
    public static final String ENTITY_REPORT_AUTO = "auto";
    public static final String ENTITY_REPORT_FIXED = "fixed";
    public static final String ENTITY_FILTER_ALLOW_TYPE = "allow_type";
    public static final String ENTITY_FILTER_DENY_TYPE = "deny_type";
    public static final String ENTITY_FILTER_ALLOW_NAME = "allow_name";
    public static final String ENTITY_FILTER_DENY_NAME = "deny_name";
    public static final int MAX_ENTITY_FILTER_RULES = 512;
    public static final int MAX_ENTITY_FILTER_VALUE_LENGTH = 128;
    public static final String TAB_HISTORY_OFF = "off";
    public static final String TAB_HISTORY_ON_DEMAND = "on_demand";
    public static final String TAB_HISTORY_FULL = "full";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String EXTERNAL_RELATION_ON_DEMAND = "on_demand";
    public static final String EXTERNAL_RELATION_OFF = "off";
    private static final int DEFAULT_FRIENDLY_TEAM_COLOR = 0xFF3B82F6;
    private static final int DEFAULT_NEUTRAL_TEAM_COLOR = 0xFFEAB308;
    private static final int DEFAULT_ENEMY_TEAM_COLOR = 0xFFEF4444;
    private static final Pattern NAMESPACED_ENTITY_TYPE =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private transient Path storagePath;

    private String serverURL = "ws://localhost:8080/mc-client";
    private String roomCode = "default";
    // New installs should not turn every retained offline record into a per-frame render candidate.
    // Existing saved values are intentionally preserved by Gson loading.
    private int renderDistance = 512;
    private boolean showLines = false;
    private boolean showBoxes = false;
    private boolean showLastSeenPlayers = false;
    private boolean showLastSeenBoxes = true;
    private boolean showLastSeenLines = true;
    private int boxColor = 0x80FF0000;
    private int lineColor = 0xFFFF0000;
    private int friendlyTeamColor = DEFAULT_FRIENDLY_TEAM_COLOR;
    private int neutralTeamColor = DEFAULT_NEUTRAL_TEAM_COLOR;
    private int enemyTeamColor = DEFAULT_ENEMY_TEAM_COLOR;
    private String tracerStartMode = TRACER_START_CROSSHAIR;
    private double tracerTopOffset = 0.42;
    private boolean enableCompression = true;
    private String compressionSuite = TransportOptions.SUITE_ZSTD_DICT;
    private boolean wsPlainNoDeflate = false;
    private boolean quicTlsKeyLogEnabled = false;
    private int updateInterval = 5;
    private boolean uploadEntities = true;
    private String entityReportMode = ENTITY_REPORT_AUTO;
    private int entityReportFixedIntervalTicks = 10;
    private List<String> entityAllowedTypes = new ArrayList<>();
    private List<String> entityDeniedTypes = new ArrayList<>();
    private List<String> entityAllowedNames = new ArrayList<>();
    private List<String> entityDeniedNames = new ArrayList<>();
    private transient volatile EntityUploadFilter compiledEntityUploadFilter;
    private transient long entityUploadSettingsRevision;
    private boolean uploadSharedWaypoints = false;
    private boolean showSharedWaypoints = true;
    private boolean showOwnSharedWaypointsOnMinimap = true;
    private boolean xrayMarkersAndBoxes = true;
    private boolean showNetworkTrafficHud = false;
    private boolean enableMiddleDoubleClickMark = true;
    private boolean enableMiddleClickCancelWaypoint = true;
    private boolean autoCancelWaypointOnEntityDeath = true;
    private int waypointTimeoutSeconds = 60;
    private boolean enableLongTermWaypoint = true;
    private int longTermWaypointTimeoutSeconds = 1800;
    private int maxQuickMarkCount = 3;
    private String waypointUiStyle = WAYPOINT_UI_BEACON;
    private double waypointBeaconBeamWidth = 0.32D;
    private double waypointBeaconBeamHeight = 7.6D;
    private double tampermonkeyBeamWidth = 0.34D;
    private double tampermonkeyBeamHeight = 384.0D;
    private boolean autoConnectOnMultiplayerJoin = false;
    private boolean useSystemProxy = false;
    private boolean allowInsecureTls = false;
    private boolean preferLocalDataForRender = true;
    private boolean battleMapSyncEnabled = true;
    private String battleMapSourceId = IntegrationIds.NODEMC_BATTLE_MAP;
    private int battleMapUpdateIntervalTicks = 10;
    private int battleMapKeepaliveIntervalSeconds = 30;
    private int battleMapCacheRetentionSeconds = 7200;
    private boolean battleMapDebugEnabled = false;
    private String tabHistorySyncMode = TAB_HISTORY_ON_DEMAND;
    private String externalRelationSyncMode = EXTERNAL_RELATION_ON_DEMAND;

    public static Config load(Path configPath) {
        if (!Files.exists(configPath)) {
            return createWithStoragePath(configPath);
        }

        try {
            String content = Files.readString(configPath);
            Config config = GSON.fromJson(content, Config.class);
            if (config == null) {
                return createWithStoragePath(configPath);
            }
            config.storagePath = configPath;
            config.battleMapSourceId = config.getBattleMapSourceId();
            return config;
        } catch (Exception e) {
            System.err.println("Failed to load TeamViewRelay config: " + e.getMessage());
            return createWithStoragePath(configPath);
        }
    }

    private static Config createWithStoragePath(Path configPath) {
        Config config = new Config();
        config.storagePath = configPath;
        return config;
    }

    public void save() {
        if (storagePath == null) {
            System.err.println("Failed to save TeamViewRelay config: storage path is not configured");
            return;
        }

        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = GSON.toJson(this);
            Files.writeString(storagePath, content);
        } catch (IOException e) {
            System.err.println("Failed to save TeamViewRelay config: " + e.getMessage());
        }
    }

    public void setStoragePath(Path storagePath) {
        this.storagePath = storagePath;
    }

    @Override
    public String getServerURL() {
        return serverURL;
    }

    @Override
    public void setServerURL(String serverURL) {
        this.serverURL = serverURL;
    }

    @Override
    public String getRoomCode() {
        if (roomCode == null) {
            return "default";
        }
        String normalized = roomCode.trim();
        if (normalized.isEmpty()) {
            return "default";
        }
        if (normalized.length() > 64) {
            return normalized.substring(0, 64);
        }
        return normalized;
    }

    @Override
    public void setRoomCode(String roomCode) {
        if (roomCode == null) {
            this.roomCode = "default";
            return;
        }
        String normalized = roomCode.trim();
        if (normalized.isEmpty()) {
            this.roomCode = "default";
            return;
        }
        this.roomCode = normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistance = renderDistance;
    }

    public boolean isShowLines() {
        return showLines;
    }

    public void setShowLines(boolean showLines) {
        this.showLines = showLines;
    }

    public boolean isShowBoxes() {
        return showBoxes;
    }

    public void setShowBoxes(boolean showBoxes) {
        this.showBoxes = showBoxes;
    }

    public boolean isShowLastSeenPlayers() {
        return showLastSeenPlayers;
    }

    public void setShowLastSeenPlayers(boolean showLastSeenPlayers) {
        this.showLastSeenPlayers = showLastSeenPlayers;
    }

    public boolean isShowLastSeenBoxes() {
        return showLastSeenBoxes;
    }

    public void setShowLastSeenBoxes(boolean showLastSeenBoxes) {
        this.showLastSeenBoxes = showLastSeenBoxes;
    }

    public boolean isShowLastSeenLines() {
        return showLastSeenLines;
    }

    public void setShowLastSeenLines(boolean showLastSeenLines) {
        this.showLastSeenLines = showLastSeenLines;
    }

    public int getBoxColor() {
        return boxColor;
    }

    public void setBoxColor(int boxColor) {
        this.boxColor = boxColor;
    }

    public int getLineColor() {
        return lineColor;
    }

    public void setLineColor(int lineColor) {
        this.lineColor = lineColor;
    }

    public int getFriendlyTeamColor() {
        if (friendlyTeamColor == 0) {
            return DEFAULT_FRIENDLY_TEAM_COLOR;
        }
        return friendlyTeamColor;
    }

    public void setFriendlyTeamColor(int friendlyTeamColor) {
        this.friendlyTeamColor = friendlyTeamColor;
    }

    public int getNeutralTeamColor() {
        if (neutralTeamColor == 0) {
            return DEFAULT_NEUTRAL_TEAM_COLOR;
        }
        return neutralTeamColor;
    }

    public void setNeutralTeamColor(int neutralTeamColor) {
        this.neutralTeamColor = neutralTeamColor;
    }

    public int getEnemyTeamColor() {
        if (enemyTeamColor == 0) {
            return DEFAULT_ENEMY_TEAM_COLOR;
        }
        return enemyTeamColor;
    }

    public void setEnemyTeamColor(int enemyTeamColor) {
        this.enemyTeamColor = enemyTeamColor;
    }

    public String getTracerStartMode() {
        if (TRACER_START_TOP.equalsIgnoreCase(tracerStartMode)) {
            return TRACER_START_TOP;
        }
        return TRACER_START_CROSSHAIR;
    }

    public void setTracerStartMode(String tracerStartMode) {
        if (TRACER_START_TOP.equalsIgnoreCase(tracerStartMode)) {
            this.tracerStartMode = TRACER_START_TOP;
        } else {
            this.tracerStartMode = TRACER_START_CROSSHAIR;
        }
    }

    public boolean isTracerStartTop() {
        return TRACER_START_TOP.equals(getTracerStartMode());
    }

    public double getTracerTopOffset() {
        if (Double.isNaN(tracerTopOffset) || Double.isInfinite(tracerTopOffset)) {
            return 0.42;
        }
        if (tracerTopOffset < 0.0) {
            return 0.0;
        }
        return Math.min(tracerTopOffset, 1.5);
    }

    public void setTracerTopOffset(double tracerTopOffset) {
        if (Double.isNaN(tracerTopOffset) || Double.isInfinite(tracerTopOffset)) {
            this.tracerTopOffset = 0.42;
            return;
        }
        if (tracerTopOffset < 0.0) {
            this.tracerTopOffset = 0.0;
            return;
        }
        this.tracerTopOffset = Math.min(tracerTopOffset, 1.5);
    }

    @Override
    public boolean isEnableCompression() {
        return enableCompression;
    }

    public void setEnableCompression(boolean enableCompression) {
        this.enableCompression = enableCompression;
    }

    @Override
    public String getCompressionSuite() {
        return TransportOptions.normalizeSuite(compressionSuite);
    }

    public void setCompressionSuite(String compressionSuite) {
        this.compressionSuite = TransportOptions.normalizeSuite(compressionSuite);
    }

    @Override
    public boolean isWsPlainNoDeflate() {
        return wsPlainNoDeflate;
    }

    public void setWsPlainNoDeflate(boolean wsPlainNoDeflate) {
        this.wsPlainNoDeflate = wsPlainNoDeflate;
    }

    @Override
    public boolean isQuicTlsKeyLogEnabled() {
        return quicTlsKeyLogEnabled;
    }

    public void setQuicTlsKeyLogEnabled(boolean quicTlsKeyLogEnabled) {
        this.quicTlsKeyLogEnabled = quicTlsKeyLogEnabled;
    }

    public int getUpdateInterval() {
        return updateInterval;
    }

    public void setUpdateInterval(int updateInterval) {
        if (updateInterval < 1) {
            this.updateInterval = 1;
        } else {
            this.updateInterval = Math.min(updateInterval, 1000);
        }
    }

    public boolean isUploadEntities() {
        return uploadEntities;
    }

    public void setUploadEntities(boolean uploadEntities) {
        this.uploadEntities = uploadEntities;
    }

    public String getEntityReportMode() {
        return ENTITY_REPORT_FIXED.equalsIgnoreCase(entityReportMode)
                ? ENTITY_REPORT_FIXED : ENTITY_REPORT_AUTO;
    }

    public void setEntityReportMode(String entityReportMode) {
        this.entityReportMode = ENTITY_REPORT_FIXED.equalsIgnoreCase(entityReportMode)
                ? ENTITY_REPORT_FIXED : ENTITY_REPORT_AUTO;
    }

    public int getEntityReportFixedIntervalTicks() {
        return Math.max(1, Math.min(1000, entityReportFixedIntervalTicks));
    }

    public void setEntityReportFixedIntervalTicks(int ticks) {
        entityReportFixedIntervalTicks = Math.max(1, Math.min(1000, ticks));
    }

    public List<String> getEntityAllowedTypes() { return safeRuleList(entityAllowedTypes); }
    public List<String> getEntityDeniedTypes() { return safeRuleList(entityDeniedTypes); }
    public List<String> getEntityAllowedNames() { return safeRuleList(entityAllowedNames); }
    public List<String> getEntityDeniedNames() { return safeRuleList(entityDeniedNames); }

    public synchronized boolean addEntityFilterRule(String kind, String rawValue) {
        String value = normalizeEntityFilterValue(kind, rawValue);
        if (value.isEmpty() || entityFilterRuleCount() >= MAX_ENTITY_FILTER_RULES) return false;
        List<String> target = mutableEntityFilterRules(kind);
        if (target == null || target.contains(value)) return false;
        target.add(value);
        invalidateEntityUploadFilter();
        return true;
    }

    public synchronized boolean removeEntityFilterRule(String kind, String rawValue) {
        List<String> target = mutableEntityFilterRules(kind);
        if (target == null) return false;
        String value = normalizeEntityFilterValue(kind, rawValue);
        boolean removed = target.remove(value);
        if (removed) invalidateEntityUploadFilter();
        return removed;
    }

    public synchronized int entityFilterRuleCount() {
        return safeRuleList(entityAllowedTypes).size()
                + safeRuleList(entityDeniedTypes).size()
                + safeRuleList(entityAllowedNames).size()
                + safeRuleList(entityDeniedNames).size();
    }

    public EntityUploadFilter getEntityUploadFilter() {
        EntityUploadFilter cached = compiledEntityUploadFilter;
        if (cached != null) return cached;
        synchronized (this) {
            if (compiledEntityUploadFilter == null) {
                int remaining = MAX_ENTITY_FILTER_RULES;
                Set<String> allowedTypes = limitedRules(entityAllowedTypes, true, remaining);
                remaining -= allowedTypes.size();
                Set<String> deniedTypes = limitedRules(entityDeniedTypes, true, remaining);
                remaining -= deniedTypes.size();
                Set<String> allowedNames = limitedRules(entityAllowedNames, false, remaining);
                remaining -= allowedNames.size();
                Set<String> deniedNames = limitedRules(entityDeniedNames, false, remaining);
                compiledEntityUploadFilter = new EntityUploadFilter(
                        allowedTypes, deniedTypes, allowedNames, deniedNames, entityUploadSettingsRevision);
            }
            return compiledEntityUploadFilter;
        }
    }

    private List<String> mutableEntityFilterRules(String kind) {
        if (entityAllowedTypes == null) entityAllowedTypes = new ArrayList<>();
        if (entityDeniedTypes == null) entityDeniedTypes = new ArrayList<>();
        if (entityAllowedNames == null) entityAllowedNames = new ArrayList<>();
        if (entityDeniedNames == null) entityDeniedNames = new ArrayList<>();
        return switch (kind == null ? "" : kind) {
            case ENTITY_FILTER_ALLOW_TYPE -> entityAllowedTypes;
            case ENTITY_FILTER_DENY_TYPE -> entityDeniedTypes;
            case ENTITY_FILTER_ALLOW_NAME -> entityAllowedNames;
            case ENTITY_FILTER_DENY_NAME -> entityDeniedNames;
            default -> null;
        };
    }

    private static String normalizeEntityFilterValue(String kind, String rawValue) {
        if (rawValue == null) return "";
        String value = rawValue.trim();
        if (value.length() > MAX_ENTITY_FILTER_VALUE_LENGTH) {
            value = value.substring(0, MAX_ENTITY_FILTER_VALUE_LENGTH);
        }
        if (ENTITY_FILTER_ALLOW_TYPE.equals(kind) || ENTITY_FILTER_DENY_TYPE.equals(kind)) {
            value = EntityUploadFilter.normalizeType(value);
            if (!NAMESPACED_ENTITY_TYPE.matcher(value).matches()) return "";
        }
        return value;
    }

    private static Set<String> limitedRules(List<String> source, boolean type, int limit) {
        if (limit <= 0 || source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String kind = type ? ENTITY_FILTER_ALLOW_TYPE : ENTITY_FILTER_ALLOW_NAME;
        for (String raw : source) {
            String value = normalizeEntityFilterValue(kind, raw);
            if (!value.isEmpty()) values.add(value);
            if (values.size() >= limit) break;
        }
        return Set.copyOf(values);
    }

    private static List<String> safeRuleList(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private void invalidateEntityUploadFilter() {
        entityUploadSettingsRevision++;
        compiledEntityUploadFilter = null;
    }

    public boolean isUploadSharedWaypoints() {
        return uploadSharedWaypoints;
    }

    public void setUploadSharedWaypoints(boolean uploadSharedWaypoints) {
        this.uploadSharedWaypoints = uploadSharedWaypoints;
    }

    public boolean isShowSharedWaypoints() {
        return showSharedWaypoints;
    }

    public void setShowSharedWaypoints(boolean showSharedWaypoints) {
        this.showSharedWaypoints = showSharedWaypoints;
    }

    public boolean isShowOwnSharedWaypointsOnMinimap() {
        return showOwnSharedWaypointsOnMinimap;
    }

    public void setShowOwnSharedWaypointsOnMinimap(boolean showOwnSharedWaypointsOnMinimap) {
        this.showOwnSharedWaypointsOnMinimap = showOwnSharedWaypointsOnMinimap;
    }

    public boolean isXrayMarkersAndBoxes() {
        return xrayMarkersAndBoxes;
    }

    public void setXrayMarkersAndBoxes(boolean xrayMarkersAndBoxes) {
        this.xrayMarkersAndBoxes = xrayMarkersAndBoxes;
    }

    public boolean isShowNetworkTrafficHud() {
        return showNetworkTrafficHud;
    }

    public void setShowNetworkTrafficHud(boolean showNetworkTrafficHud) {
        this.showNetworkTrafficHud = showNetworkTrafficHud;
    }

    public boolean isEnableMiddleDoubleClickMark() {
        return enableMiddleDoubleClickMark;
    }

    public void setEnableMiddleDoubleClickMark(boolean enableMiddleDoubleClickMark) {
        this.enableMiddleDoubleClickMark = enableMiddleDoubleClickMark;
    }

    public boolean isEnableMiddleClickCancelWaypoint() {
        return enableMiddleClickCancelWaypoint;
    }

    public void setEnableMiddleClickCancelWaypoint(boolean enableMiddleClickCancelWaypoint) {
        this.enableMiddleClickCancelWaypoint = enableMiddleClickCancelWaypoint;
    }

    public boolean isAutoCancelWaypointOnEntityDeath() {
        return autoCancelWaypointOnEntityDeath;
    }

    public void setAutoCancelWaypointOnEntityDeath(boolean autoCancelWaypointOnEntityDeath) {
        this.autoCancelWaypointOnEntityDeath = autoCancelWaypointOnEntityDeath;
    }

    public int getWaypointTimeoutSeconds() {
        if (waypointTimeoutSeconds < 10) {
            return 10;
        }
        return Math.min(waypointTimeoutSeconds, 3600);
    }

    public void setWaypointTimeoutSeconds(int waypointTimeoutSeconds) {
        if (waypointTimeoutSeconds < 10) {
            this.waypointTimeoutSeconds = 10;
            return;
        }
        this.waypointTimeoutSeconds = Math.min(waypointTimeoutSeconds, 3600);
    }

    public boolean isEnableLongTermWaypoint() {
        return enableLongTermWaypoint;
    }

    public void setEnableLongTermWaypoint(boolean enableLongTermWaypoint) {
        this.enableLongTermWaypoint = enableLongTermWaypoint;
    }

    public int getLongTermWaypointTimeoutSeconds() {
        if (longTermWaypointTimeoutSeconds < 30) {
            return 30;
        }
        return Math.min(longTermWaypointTimeoutSeconds, 86400);
    }

    public void setLongTermWaypointTimeoutSeconds(int longTermWaypointTimeoutSeconds) {
        if (longTermWaypointTimeoutSeconds < 30) {
            this.longTermWaypointTimeoutSeconds = 30;
            return;
        }
        this.longTermWaypointTimeoutSeconds = Math.min(longTermWaypointTimeoutSeconds, 86400);
    }

    public int getMaxQuickMarkCount() {
        if (maxQuickMarkCount < 1) {
            return 1;
        }
        return Math.min(maxQuickMarkCount, 20);
    }

    public void setMaxQuickMarkCount(int maxQuickMarkCount) {
        if (maxQuickMarkCount < 1) {
            this.maxQuickMarkCount = 1;
            return;
        }
        this.maxQuickMarkCount = Math.min(maxQuickMarkCount, 20);
    }

    public String getWaypointUiStyle() {
        if (WAYPOINT_UI_RING.equalsIgnoreCase(waypointUiStyle)) {
            return WAYPOINT_UI_RING;
        }
        if (WAYPOINT_UI_PIN.equalsIgnoreCase(waypointUiStyle)) {
            return WAYPOINT_UI_PIN;
        }
        return WAYPOINT_UI_BEACON;
    }

    public void setWaypointUiStyle(String waypointUiStyle) {
        if (WAYPOINT_UI_RING.equalsIgnoreCase(waypointUiStyle)) {
            this.waypointUiStyle = WAYPOINT_UI_RING;
            return;
        }
        if (WAYPOINT_UI_PIN.equalsIgnoreCase(waypointUiStyle)) {
            this.waypointUiStyle = WAYPOINT_UI_PIN;
            return;
        }
        this.waypointUiStyle = WAYPOINT_UI_BEACON;
    }

    public double getWaypointBeaconBeamWidth() {
        if (Double.isNaN(waypointBeaconBeamWidth) || Double.isInfinite(waypointBeaconBeamWidth)) {
            return 0.32D;
        }
        if (waypointBeaconBeamWidth < 0.05D) {
            return 0.05D;
        }
        return Math.min(waypointBeaconBeamWidth, 4.0D);
    }

    public void setWaypointBeaconBeamWidth(double waypointBeaconBeamWidth) {
        if (Double.isNaN(waypointBeaconBeamWidth) || Double.isInfinite(waypointBeaconBeamWidth)) {
            this.waypointBeaconBeamWidth = 0.32D;
            return;
        }
        if (waypointBeaconBeamWidth < 0.05D) {
            this.waypointBeaconBeamWidth = 0.05D;
            return;
        }
        this.waypointBeaconBeamWidth = Math.min(waypointBeaconBeamWidth, 4.0D);
    }

    public double getWaypointBeaconBeamHeight() {
        if (Double.isNaN(waypointBeaconBeamHeight) || Double.isInfinite(waypointBeaconBeamHeight)) {
            return 7.6D;
        }
        if (waypointBeaconBeamHeight < 0.5D) {
            return 0.5D;
        }
        return Math.min(waypointBeaconBeamHeight, 256.0D);
    }

    public void setWaypointBeaconBeamHeight(double waypointBeaconBeamHeight) {
        if (Double.isNaN(waypointBeaconBeamHeight) || Double.isInfinite(waypointBeaconBeamHeight)) {
            this.waypointBeaconBeamHeight = 7.6D;
            return;
        }
        if (waypointBeaconBeamHeight < 0.5D) {
            this.waypointBeaconBeamHeight = 0.5D;
            return;
        }
        this.waypointBeaconBeamHeight = Math.min(waypointBeaconBeamHeight, 256.0D);
    }

    public double getTampermonkeyBeamWidth() {
        if (Double.isNaN(tampermonkeyBeamWidth) || Double.isInfinite(tampermonkeyBeamWidth)) {
            return 0.34D;
        }
        if (tampermonkeyBeamWidth < 0.05D) {
            return 0.05D;
        }
        return Math.min(tampermonkeyBeamWidth, 8.0D);
    }

    public void setTampermonkeyBeamWidth(double tampermonkeyBeamWidth) {
        if (Double.isNaN(tampermonkeyBeamWidth) || Double.isInfinite(tampermonkeyBeamWidth)) {
            this.tampermonkeyBeamWidth = 0.34D;
            return;
        }
        if (tampermonkeyBeamWidth < 0.05D) {
            this.tampermonkeyBeamWidth = 0.05D;
            return;
        }
        this.tampermonkeyBeamWidth = Math.min(tampermonkeyBeamWidth, 8.0D);
    }

    public double getTampermonkeyBeamHeight() {
        if (Double.isNaN(tampermonkeyBeamHeight) || Double.isInfinite(tampermonkeyBeamHeight)) {
            return 384.0D;
        }
        if (tampermonkeyBeamHeight < 1.0D) {
            return 1.0D;
        }
        return Math.min(tampermonkeyBeamHeight, 1024.0D);
    }

    public void setTampermonkeyBeamHeight(double tampermonkeyBeamHeight) {
        if (Double.isNaN(tampermonkeyBeamHeight) || Double.isInfinite(tampermonkeyBeamHeight)) {
            this.tampermonkeyBeamHeight = 384.0D;
            return;
        }
        if (tampermonkeyBeamHeight < 1.0D) {
            this.tampermonkeyBeamHeight = 1.0D;
            return;
        }
        this.tampermonkeyBeamHeight = Math.min(tampermonkeyBeamHeight, 1024.0D);
    }

    public boolean isAutoConnectOnMultiplayerJoin() {
        return autoConnectOnMultiplayerJoin;
    }

    public void setAutoConnectOnMultiplayerJoin(boolean autoConnectOnMultiplayerJoin) {
        this.autoConnectOnMultiplayerJoin = autoConnectOnMultiplayerJoin;
    }

    @Override
    public boolean isUseSystemProxy() {
        return useSystemProxy;
    }

    @Override
    public void setUseSystemProxy(boolean useSystemProxy) {
        this.useSystemProxy = useSystemProxy;
    }

    @Override
    public boolean isAllowInsecureTls() {
        return allowInsecureTls;
    }

    @Override
    public void setAllowInsecureTls(boolean allowInsecureTls) {
        this.allowInsecureTls = allowInsecureTls;
    }

    @Override
    public int getUpdateIntervalTicks() {
        return getUpdateInterval();
    }

    @Override
    public String getTabHistorySyncMode() {
        if (TAB_HISTORY_OFF.equals(tabHistorySyncMode) || TAB_HISTORY_FULL.equals(tabHistorySyncMode)) {
            return tabHistorySyncMode;
        }
        return TAB_HISTORY_ON_DEMAND;
    }

    public void setTabHistorySyncMode(String mode) {
        tabHistorySyncMode = mode;
    }

    @Override
    public String getExternalRelationSyncMode() {
        if (EXTERNAL_RELATION_OFF.equals(externalRelationSyncMode)) {
            return EXTERNAL_RELATION_OFF;
        }
        return EXTERNAL_RELATION_ON_DEMAND;
    }

    public void setExternalRelationSyncMode(String mode) {
        externalRelationSyncMode = mode;
    }

    public boolean isPreferLocalDataForRender() {
        return preferLocalDataForRender;
    }

    public void setPreferLocalDataForRender(boolean preferLocalDataForRender) {
        this.preferLocalDataForRender = preferLocalDataForRender;
    }

    public boolean isBattleMapSyncEnabled() {
        return battleMapSyncEnabled;
    }

    public void setBattleMapSyncEnabled(boolean battleMapSyncEnabled) {
        this.battleMapSyncEnabled = battleMapSyncEnabled;
    }

    public String getBattleMapSourceId() {
        if (battleMapSourceId == null || battleMapSourceId.isBlank()) {
            return IntegrationIds.NODEMC_BATTLE_MAP;
        }
        return IntegrationIds.canonicalize(battleMapSourceId);
    }

    public void setBattleMapSourceId(String sourceId) {
        String normalized = IntegrationIds.canonicalize(sourceId);
        battleMapSourceId = normalized.isBlank() ? IntegrationIds.NODEMC_BATTLE_MAP : normalized;
    }

    public int getBattleMapUpdateIntervalTicks() {
        if (battleMapUpdateIntervalTicks < 1) {
            return 1;
        }
        return Math.min(battleMapUpdateIntervalTicks, 1000);
    }

    public void setBattleMapUpdateIntervalTicks(int battleMapUpdateIntervalTicks) {
        if (battleMapUpdateIntervalTicks < 1) {
            this.battleMapUpdateIntervalTicks = 1;
            return;
        }
        this.battleMapUpdateIntervalTicks = Math.min(battleMapUpdateIntervalTicks, 1000);
    }

    public int getBattleMapKeepaliveIntervalSeconds() {
        if (battleMapKeepaliveIntervalSeconds < 5) {
            return 5;
        }
        return Math.min(battleMapKeepaliveIntervalSeconds, 3600);
    }

    public void setBattleMapKeepaliveIntervalSeconds(int battleMapKeepaliveIntervalSeconds) {
        if (battleMapKeepaliveIntervalSeconds < 5) {
            this.battleMapKeepaliveIntervalSeconds = 5;
            return;
        }
        this.battleMapKeepaliveIntervalSeconds = Math.min(battleMapKeepaliveIntervalSeconds, 3600);
    }

    public int getBattleMapCacheRetentionSeconds() {
        if (battleMapCacheRetentionSeconds < 60) {
            return 60;
        }
        return Math.min(battleMapCacheRetentionSeconds, 604800);
    }

    public void setBattleMapCacheRetentionSeconds(int battleMapCacheRetentionSeconds) {
        if (battleMapCacheRetentionSeconds < 60) {
            this.battleMapCacheRetentionSeconds = 60;
            return;
        }
        this.battleMapCacheRetentionSeconds = Math.min(battleMapCacheRetentionSeconds, 604800);
    }

    public boolean isBattleMapDebugEnabled() {
        return battleMapDebugEnabled;
    }

    public void setBattleMapDebugEnabled(boolean battleMapDebugEnabled) {
        this.battleMapDebugEnabled = battleMapDebugEnabled;
    }
}
