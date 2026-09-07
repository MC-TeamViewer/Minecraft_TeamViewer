package fun.prof_chen.teamviewer.main_code.config.ui;

import fun.prof_chen.teamviewer.main_code.bridge.NetworkManager;
import fun.prof_chen.teamviewer.main_code.client.bridge.ClientControlGateway;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationCapability;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationIds;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationRole;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationSupportStatus;
import fun.prof_chen.teamviewer.main_code.config.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static fun.prof_chen.teamviewer.main_code.config.ui.ConfigControlId.*;

/**
 * The complete platform-neutral TeamViewRelay configuration UI state machine.
 * Minecraft adapters render the immutable page view and forward edits/actions here.
 */
public final class ConfigUiSession implements ConfigUiController {
    private static final int HEIGHT = 20;
    private static final int LABEL_HEIGHT = 12;
    private static final int LABEL_SPACING = 12;

    private final ClientControlGateway control;
    private final Config config;
    private final Map<ConfigControlId, String> textValues = new HashMap<>();
    private String originalUrl;
    private String originalRoomCode;
    private boolean originalAutoConnect;
    private boolean originalAllowInsecureTls;
    private boolean allowInsecureTls;
    private int entityFilterListPage;
    private String editingEntityFilterKind = Config.ENTITY_FILTER_ALLOW_TYPE;
    private String editingEntityFilterOriginalValue = "";

    public ConfigUiSession(ClientControlGateway control) {
        this.control = Objects.requireNonNull(control, "control");
        this.config = Objects.requireNonNull(control.getConfig(), "config");
        captureRootBaseline();
        initializeTextValues();
    }

    @Override
    public ConfigPageView page(ConfigPageId pageId, int width, int height) {
        return switch (pageId) {
            case ROOT -> rootPage(width, height);
            case DISPLAY -> displayPage(width, height);
            case NETWORK -> networkPage(width, height);
            case ENTITY_UPLOAD -> entityUploadPage(width, height);
            case ENTITY_FILTERS -> entityFiltersPage(width, height);
            case ENTITY_FILTER_EDIT -> entityFilterEditPage(width, height);
            case COLOR -> colorPage(width, height);
            case WAYPOINT -> waypointPage(width, height);
            case WAYPOINT_SHAPE -> waypointShapePage(width, height);
            case PACKET_CAPTURE -> packetCapturePage(width, height);
        };
    }

    @Override
    public void setText(ConfigControlId id, String value) {
        if (id != null && value != null) {
            textValues.put(id, value);
        }
    }

    @Override
    public void setChecked(ConfigControlId id, boolean checked) {
        if (ALLOW_INSECURE_TLS.equals(id)) {
            allowInsecureTls = checked;
        }
    }

    @Override
    public ConfigUiAction activate(ConfigPageId currentPage, ConfigControlId id) {
        if (id == null) {
            return ConfigUiAction.stay();
        }
        if (id.isEntityRuleAction()) return activateEntityRuleAction(id);
        return switch (id.value()) {
            case "OPEN_DISPLAY" -> ConfigUiAction.open(ConfigPageId.DISPLAY);
            case "OPEN_NETWORK" -> ConfigUiAction.open(ConfigPageId.NETWORK);
            case "OPEN_PLUGINS" -> ConfigUiAction.openPluginManager();
            case "OPEN_ENTITY_UPLOAD" -> ConfigUiAction.open(ConfigPageId.ENTITY_UPLOAD);
            case "OPEN_ENTITY_FILTERS" -> ConfigUiAction.open(ConfigPageId.ENTITY_FILTERS);
            case "OPEN_COLOR" -> ConfigUiAction.open(ConfigPageId.COLOR);
            case "OPEN_WAYPOINT" -> ConfigUiAction.open(ConfigPageId.WAYPOINT);
            case "OPEN_WAYPOINT_SHAPE" -> ConfigUiAction.open(ConfigPageId.WAYPOINT_SHAPE);
            case "OPEN_PACKET_CAPTURE" -> ConfigUiAction.open(ConfigPageId.PACKET_CAPTURE);
            case "BACK" -> {
                applyPageFields(currentPage);
                yield ConfigUiAction.closeToParent();
            }
            case "SAVE_ROOT" -> {
                saveRoot();
                yield ConfigUiAction.closeToParent();
            }
            case "AUTO_CONNECT" -> toggleAutoConnect();
            case "CONNECT" -> connect();
            case "DISCONNECT" -> disconnect();
            case "SHOW_BOXES" -> toggle(config.isShowBoxes(), config::setShowBoxes);
            case "SHOW_LINES" -> toggle(config.isShowLines(), config::setShowLines);
            case "SHOW_LAST_SEEN_PLAYERS" -> toggleAndSave(config.isShowLastSeenPlayers(), config::setShowLastSeenPlayers);
            case "SHOW_LAST_SEEN_BOXES" -> toggleAndSave(config.isShowLastSeenBoxes(), config::setShowLastSeenBoxes);
            case "SHOW_LAST_SEEN_LINES" -> toggleAndSave(config.isShowLastSeenLines(), config::setShowLastSeenLines);
            case "TRACER_START_MODE" -> cycleTracerMode();
            case "XRAY_MARKERS_AND_BOXES" -> toggle(config.isXrayMarkersAndBoxes(), config::setXrayMarkersAndBoxes);
            case "SHOW_NETWORK_TRAFFIC_HUD" -> toggle(config.isShowNetworkTrafficHud(), config::setShowNetworkTrafficHud);
            case "SHOW_SHARED_WAYPOINTS" -> toggle(config.isShowSharedWaypoints(), config::setShowSharedWaypoints);
            case "SHOW_OWN_SHARED_WAYPOINTS" -> toggle(config.isShowOwnSharedWaypointsOnMinimap(), config::setShowOwnSharedWaypointsOnMinimap);
            case "MIDDLE_DOUBLE_CLICK_MARK" -> toggle(config.isEnableMiddleDoubleClickMark(), config::setEnableMiddleDoubleClickMark);
            case "MIDDLE_CLICK_CANCEL" -> toggle(config.isEnableMiddleClickCancelWaypoint(), config::setEnableMiddleClickCancelWaypoint);
            case "AUTO_CANCEL_ON_ENTITY_DEATH" -> toggle(config.isAutoCancelWaypointOnEntityDeath(), config::setAutoCancelWaypointOnEntityDeath);
            case "ENABLE_LONG_TERM_WAYPOINT" -> toggle(config.isEnableLongTermWaypoint(), config::setEnableLongTermWaypoint);
            case "WAYPOINT_UI_STYLE" -> cycleWaypointStyle();
            case "UPLOAD_ENTITIES" -> toggle(config.isUploadEntities(), config::setUploadEntities);
            case "ENTITY_REPORT_MODE" -> {
                config.setEntityReportMode(Config.ENTITY_REPORT_AUTO.equals(config.getEntityReportMode())
                        ? Config.ENTITY_REPORT_FIXED : Config.ENTITY_REPORT_AUTO);
                config.save();
                yield ConfigUiAction.stay();
            }
            case "ENTITY_FILTER_PREVIOUS" -> {
                entityFilterListPage = Math.max(0, entityFilterListPage - 1);
                yield ConfigUiAction.reload();
            }
            case "ENTITY_FILTER_NEXT" -> {
                entityFilterListPage++;
                yield ConfigUiAction.reload();
            }
            case "ENTITY_FILTER_SAVE" -> saveEntityFilterRule();
            case "UPLOAD_SHARED_WAYPOINTS" -> toggle(config.isUploadSharedWaypoints(), config::setUploadSharedWaypoints);
            case "USE_SYSTEM_PROXY" -> toggle(config.isUseSystemProxy(), config::setUseSystemProxy);
            case "PREFER_LOCAL_DATA" -> toggle(config.isPreferLocalDataForRender(), config::setPreferLocalDataForRender);
            case "BATTLE_MAP_SYNC" -> toggle(config.isBattleMapSyncEnabled(), config::setBattleMapSyncEnabled);
            case "BATTLE_MAP_SOURCE" -> cycleBattleMapSource();
            case "BATTLE_MAP_DEBUG" -> toggle(config.isBattleMapDebugEnabled(), config::setBattleMapDebugEnabled);
            case "PACKET_CAPTURE_START" -> startPacketCapture();
            case "PACKET_CAPTURE_STOP" -> stopPacketCapture();
            default -> ConfigUiAction.stay();
        };
    }

    @Override
    public ConfigUiAction close(ConfigPageId pageId) {
        if (pageId == ConfigPageId.ROOT) {
            cancelRoot();
        } else {
            applyPageFields(pageId);
        }
        return ConfigUiAction.closeToParent();
    }

    public void saveRoot() {
        String url = value(SERVER_URL).trim();
        if (!url.isEmpty()) {
            config.setServerURL(url);
        }
        config.setRoomCode(value(ROOM_CODE).trim());
        config.setAllowInsecureTls(allowInsecureTls);
        config.save();
        captureRootBaseline();
    }

    public void cancelRoot() {
        config.setServerURL(originalUrl);
        config.setRoomCode(originalRoomCode);
        config.setAutoConnectOnMultiplayerJoin(originalAutoConnect);
        config.setAllowInsecureTls(originalAllowInsecureTls);
        allowInsecureTls = originalAllowInsecureTls;
        textValues.put(SERVER_URL, originalUrl);
        textValues.put(ROOM_CODE, originalRoomCode);
    }

    public boolean hasUnsavedRootChanges() {
        return !value(SERVER_URL).trim().equals(originalUrl)
                || !value(ROOM_CODE).trim().equals(originalRoomCode)
                || config.isAutoConnectOnMultiplayerJoin() != originalAutoConnect
                || allowInsecureTls != originalAllowInsecureTls;
    }

    private void captureRootBaseline() {
        originalUrl = config.getServerURL();
        originalRoomCode = config.getRoomCode();
        originalAutoConnect = config.isAutoConnectOnMultiplayerJoin();
        originalAllowInsecureTls = config.isAllowInsecureTls();
        allowInsecureTls = originalAllowInsecureTls;
    }

    private void initializeTextValues() {
        textValues.put(SERVER_URL, config.getServerURL());
        textValues.put(ROOM_CODE, config.getRoomCode());
        textValues.put(RENDER_DISTANCE, String.valueOf(config.getRenderDistance()));
        textValues.put(TRACER_TOP_OFFSET, String.valueOf(config.getTracerTopOffset()));
        textValues.put(BOX_COLOR, color(config.getBoxColor()));
        textValues.put(LINE_COLOR, color(config.getLineColor()));
        textValues.put(FRIENDLY_TEAM_COLOR, color(config.getFriendlyTeamColor()));
        textValues.put(NEUTRAL_TEAM_COLOR, color(config.getNeutralTeamColor()));
        textValues.put(ENEMY_TEAM_COLOR, color(config.getEnemyTeamColor()));
        textValues.put(WAYPOINT_TIMEOUT, String.valueOf(config.getWaypointTimeoutSeconds()));
        textValues.put(LONG_TERM_WAYPOINT_TIMEOUT, String.valueOf(config.getLongTermWaypointTimeoutSeconds()));
        textValues.put(QUICK_MARK_MAX_COUNT, String.valueOf(config.getMaxQuickMarkCount()));
        textValues.put(WAYPOINT_BEAM_WIDTH, String.valueOf(config.getWaypointBeaconBeamWidth()));
        textValues.put(WAYPOINT_BEAM_HEIGHT, String.valueOf(config.getWaypointBeaconBeamHeight()));
        textValues.put(TAMPERMONKEY_BEAM_WIDTH, String.valueOf(config.getTampermonkeyBeamWidth()));
        textValues.put(TAMPERMONKEY_BEAM_HEIGHT, String.valueOf(config.getTampermonkeyBeamHeight()));
        textValues.put(UPDATE_INTERVAL, String.valueOf(config.getUpdateInterval()));
        textValues.put(ENTITY_REPORT_FIXED_INTERVAL, String.valueOf(config.getEntityReportFixedIntervalTicks()));
        textValues.put(ENTITY_FILTER_VALUE, "");
        textValues.put(BATTLE_MAP_UPDATE_INTERVAL, String.valueOf(config.getBattleMapUpdateIntervalTicks()));
        textValues.put(BATTLE_MAP_KEEPALIVE_INTERVAL, String.valueOf(config.getBattleMapKeepaliveIntervalSeconds()));
        textValues.put(BATTLE_MAP_CACHE_RETENTION, String.valueOf(config.getBattleMapCacheRetentionSeconds()));
    }

    private ConfigPageView rootPage(int width, int height) {
        int componentWidth = 200;
        int totalHeight = 30 * 5 + 25 * 4;
        int start = (height - totalHeight) / 2;
        int y = start + 30;
        int x = (width - componentWidth) / 2;
        int half = (componentWidth - 4) / 2;
        List<ConfigControlView> controls = new ArrayList<>();
        controls.add(field(SERVER_URL, x, y, componentWidth, "screen.mc_teamviewer.config.url",
                "screen.mc_teamviewer.config.url_hint", null, 2048));
        y += 30;
        controls.add(field(ROOM_CODE, x, y, half, "screen.mc_teamviewer.config.room_code",
                "screen.mc_teamviewer.config.room_code_hint", null, 64));
        controls.add(ConfigControlView.checkbox(ALLOW_INSECURE_TLS, new UiRect(x + half + 4, y, half, HEIGHT),
                tr("screen.mc_teamviewer.config.allow_insecure_tls"), tr("screen.mc_teamviewer.config.allow_insecure_tls.tooltip"),
                allowInsecureTls));
        y += 30;
        controls.add(button(OPEN_DISPLAY, x, y, 99, "screen.mc_teamviewer.config.display_settings", null));
        controls.add(button(OPEN_NETWORK, x + 101, y, 99, "screen.mc_teamviewer.config.network_settings", null));
        y += 25;
        controls.add(button(OPEN_PLUGINS, x, y, componentWidth, "screen.mc_teamviewer.config.integration_plugins", null));
        y += 25;
        ConfigControlView save = button(SAVE_ROOT, x, y, half, "screen.mc_teamviewer.config.save_network_settings", null);
        if (hasUnsavedRootChanges()) {
            save = new ConfigControlView(save.id(), save.kind(), save.bounds(), save.labelBounds(), save.label(), save.hint(),
                    save.tooltip(), save.value(), save.maxLength(), save.checked(), save.active(), save.visible(), 0x55FF55,
                    save.alignment());
        }
        controls.add(save);
        controls.add(ConfigControlView.button(AUTO_CONNECT, new UiRect(x + half + 4, y, half, HEIGHT),
                UiText.toggle("screen.mc_teamviewer.config.auto_connect_compact", config.isAutoConnectOnMultiplayerJoin()), null, true));
        y += 25;
        controls.add(ConfigControlView.button(CONNECT, new UiRect(x, y, 99, HEIGHT),
                tr(control.isEnabled() ? "screen.mc_teamviewer.config.reconnect" : "screen.mc_teamviewer.config.connect"), null, true));
        controls.add(ConfigControlView.button(DISCONNECT, new UiRect(x + 101, y, 99, HEIGHT),
                tr("screen.mc_teamviewer.config.disconnect"), null,
                control.isEnabled() || control.getNetworkManager().isConnected()));
        y += 25;
        NetworkManager.ConnectionStage stage = control.getNetworkManager().getConnectionStage();
        UiText status = stage == NetworkManager.ConnectionStage.FAILED
                ? tr("screen.mc_teamviewer.config.connection_failed_short")
                : UiText.translatable("screen.mc_teamviewer.config.connection_status", tr(statusKey(stage)));
        controls.add(ConfigControlView.text(CONNECTION_STATUS, new UiRect(x, y, componentWidth, HEIGHT), status,
                stage == NetworkManager.ConnectionStage.FAILED
                        ? (isBlank(control.getNetworkManager().getLastConnectionError())
                        ? tr("screen.mc_teamviewer.config.unknown_error")
                        : UiText.literal(control.getNetworkManager().getLastConnectionError())) : null,
                statusColor(stage), true, ConfigControlView.TextAlignment.CENTER));
        y += 30;
        controls.add(ConfigControlView.text(SAVE_HINT, new UiRect(x, y, componentWidth, HEIGHT),
                tr("screen.mc_teamviewer.config.save_required_hint"), null, 0xFFAA00,
                hasUnsavedRootChanges(), ConfigControlView.TextAlignment.CENTER));
        return new ConfigPageView(ConfigPageId.ROOT, tr("screen.mc_teamviewer.config.title"), start - 30, controls);
    }

    private ConfigPageView displayPage(int width, int height) {
        int start = (height - (30 * 3 + 25 * 8)) / 2;
        int y = start + 30;
        int left = (width - 346) / 2;
        int right = left + 176;
        List<ConfigControlView> c = new ArrayList<>();
        c.add(field(RENDER_DISTANCE, left, y, 170, "screen.mc_teamviewer.config.render_distance",
                "screen.mc_teamviewer.config.render_distance_hint", "screen.mc_teamviewer.config.render_distance.tooltip", 100));
        c.add(field(TRACER_TOP_OFFSET, right, y, 170, "screen.mc_teamviewer.config.tracer_top_offset",
                "screen.mc_teamviewer.config.tracer_top_offset_hint", "screen.mc_teamviewer.config.tracer_top_offset.tooltip", 10));
        y += 30;
        c.add(toggleButtonWithTooltip(SHOW_BOXES, left, y, 170, "screen.mc_teamviewer.config.show_boxes", config.isShowBoxes()));
        c.add(toggleButtonWithTooltip(SHOW_LINES, right, y, 170, "screen.mc_teamviewer.config.show_tracking_lines", config.isShowLines()));
        y += 25;
        String tracerMode = config.isTracerStartTop()
                ? "screen.mc_teamviewer.config.tracer_start_mode.top" : "screen.mc_teamviewer.config.tracer_start_mode.crosshair";
        c.add(ConfigControlView.button(TRACER_START_MODE, new UiRect(left, y, 170, HEIGHT),
                tr("screen.mc_teamviewer.config.tracer_start_mode").append(": ").append(""),
                tr("screen.mc_teamviewer.config.tracer_start_mode.tooltip"), true));
        // The mode suffix is a translated argument, so use a standard translatable label for adapters.
        c.set(c.size() - 1, ConfigControlView.button(TRACER_START_MODE, new UiRect(left, y, 170, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.config.value", tr("screen.mc_teamviewer.config.tracer_start_mode"), tr(tracerMode)),
                tr("screen.mc_teamviewer.config.tracer_start_mode.tooltip"), true));
        c.add(toggleButtonWithTooltip(XRAY_MARKERS_AND_BOXES, right, y, 170, "screen.mc_teamviewer.config.xray_markers_and_boxes", config.isXrayMarkersAndBoxes()));
        y += 25;
        c.add(button(OPEN_COLOR, left, y, 170, "screen.mc_teamviewer.config.color_settings", "screen.mc_teamviewer.config.color_settings.tooltip"));
        c.add(button(OPEN_WAYPOINT, right, y, 170, "screen.mc_teamviewer.config.waypoint_settings", "screen.mc_teamviewer.config.waypoint_settings.tooltip"));
        y += 25;
        c.add(toggleButtonWithTooltip(SHOW_NETWORK_TRAFFIC_HUD, left, y, 170, "screen.mc_teamviewer.config.show_network_traffic_hud", config.isShowNetworkTrafficHud()));
        y += 25;
        c.add(toggleButtonWithTooltip(SHOW_LAST_SEEN_PLAYERS, left, y, 346,
                "screen.mc_teamviewer.config.show_last_seen_players", config.isShowLastSeenPlayers()));
        y += 25;
        c.add(toggleButtonWithTooltip(SHOW_LAST_SEEN_BOXES, left, y, 170,
                "screen.mc_teamviewer.config.show_last_seen_boxes", config.isShowLastSeenBoxes()));
        c.add(toggleButtonWithTooltip(SHOW_LAST_SEEN_LINES, right, y, 170,
                "screen.mc_teamviewer.config.show_last_seen_lines", config.isShowLastSeenLines()));
        y += 25;
        c.add(button(BACK, left, y, 346, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.DISPLAY, tr("screen.mc_teamviewer.display_config.title"), start - 30, c);
    }

    private ConfigPageView networkPage(int width, int height) {
        int column = Math.max(150, Math.min(200, (width - 80 - 8) / 2));
        int left = (width - column * 2 - 8) / 2;
        int right = left + column + 8;
        int start = (height - (30 * 3 + 25 * 5)) / 2;
        int y = start + 30;
        List<ConfigControlView> c = new ArrayList<>();
        c.add(field(UPDATE_INTERVAL, left, y, column, "screen.mc_teamviewer.config.update_interval",
                "screen.mc_teamviewer.config.update_interval_hint", null, 5));
        c.add(field(BATTLE_MAP_UPDATE_INTERVAL, right, y, column, "screen.mc_teamviewer.config.battle_map_update_interval",
                "screen.mc_teamviewer.config.battle_map_update_interval_hint", null, 5));
        y += 30;
        c.add(field(BATTLE_MAP_KEEPALIVE_INTERVAL, left, y, column, "screen.mc_teamviewer.config.battle_map_keepalive_interval",
                "screen.mc_teamviewer.config.battle_map_keepalive_interval_hint", null, 5));
        c.add(field(BATTLE_MAP_CACHE_RETENTION, right, y, column, "screen.mc_teamviewer.config.battle_map_cache_retention",
                "screen.mc_teamviewer.config.battle_map_cache_retention_hint", null, 6));
        y += 30;
        c.add(toggleButton(UPLOAD_ENTITIES, left, y, column, "screen.mc_teamviewer.config.upload_entities", config.isUploadEntities()));
        c.add(toggleButton(UPLOAD_SHARED_WAYPOINTS, right, y, column, "screen.mc_teamviewer.config.upload_shared_waypoints", config.isUploadSharedWaypoints()));
        y += 25;
        c.add(toggleButton(PREFER_LOCAL_DATA, left, y, column, "screen.mc_teamviewer.config.prefer_local_data_for_rendering", config.isPreferLocalDataForRender()));
        c.add(toggleButton(USE_SYSTEM_PROXY, right, y, column, "screen.mc_teamviewer.config.use_system_proxy", config.isUseSystemProxy()));
        y += 25;
        c.add(toggleButton(BATTLE_MAP_SYNC, left, y, column, "screen.mc_teamviewer.config.battle_map_sync", config.isBattleMapSyncEnabled()));
        c.add(ConfigControlView.button(BATTLE_MAP_SOURCE, new UiRect(right, y, column, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.config.value",
                        tr("screen.mc_teamviewer.config.battle_map_source"), battleMapSourceLabel()),
                battleMapSourceTooltip(), true));
        y += 25;
        c.add(toggleButton(BATTLE_MAP_DEBUG, left, y, column, "screen.mc_teamviewer.config.battle_map_debug", config.isBattleMapDebugEnabled()));
        c.add(ConfigControlView.button(OPEN_PACKET_CAPTURE, new UiRect(right, y, column, HEIGHT),
                tr("screen.mc_teamviewer.config.packet_capture_page").append(
                        control.getNetworkManager().isPacketDumpCaptureActive() ? " [RUN]" : " [IDLE]"), null, true));
        y += 25;
        c.add(button(OPEN_ENTITY_UPLOAD, left, y, column * 2 + 8,
                "screen.mc_teamviewer.config.entity_upload_settings", null));
        y += 25;
        c.add(button(BACK, left, y, column * 2 + 8, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.NETWORK, tr("screen.mc_teamviewer.network_config.title"), start - 30, c);
    }

    private ConfigPageView entityUploadPage(int width, int height) {
        int x = (width - 346) / 2;
        int right = x + 176;
        int y = Math.max(48, (height - 180) / 2);
        List<ConfigControlView> controls = new ArrayList<>();
        controls.add(toggleButton(UPLOAD_ENTITIES, x, y, 170,
                "screen.mc_teamviewer.config.upload_entities", config.isUploadEntities()));
        String modeKey = Config.ENTITY_REPORT_FIXED.equals(config.getEntityReportMode())
                ? "screen.mc_teamviewer.entity_upload.mode.fixed"
                : "screen.mc_teamviewer.entity_upload.mode.auto";
        controls.add(ConfigControlView.button(ENTITY_REPORT_MODE, new UiRect(right, y, 170, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.config.value",
                        tr("screen.mc_teamviewer.entity_upload.mode"), tr(modeKey)),
                tr("screen.mc_teamviewer.entity_upload.mode.tooltip"), true));
        y += 34;
        controls.add(field(ENTITY_REPORT_FIXED_INTERVAL, x, y, 170,
                "screen.mc_teamviewer.entity_upload.fixed_interval",
                "screen.mc_teamviewer.entity_upload.fixed_interval_hint",
                "screen.mc_teamviewer.entity_upload.fixed_interval.tooltip", 4));
        controls.add(button(OPEN_ENTITY_FILTERS, right, y, 170,
                "screen.mc_teamviewer.entity_upload.filters", null));
        y += 42;
        controls.add(ConfigControlView.text(ENTITY_FILTER_PAGE_STATUS, new UiRect(x, y, 346, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.entity_upload.filter_count",
                        UiText.literal(String.valueOf(config.entityFilterRuleCount()))),
                null, 0xAAAAAA, true, ConfigControlView.TextAlignment.CENTER));
        y += 28;
        controls.add(button(BACK, x, y, 346, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.ENTITY_UPLOAD,
                tr("screen.mc_teamviewer.entity_upload.title"), 24, controls);
    }

    private ConfigPageView entityFiltersPage(int width, int height) {
        int x = (width - 430) / 2;
        int y = 42;
        int pageSize = Math.max(4, Math.min(8, (height - 180) / 24));
        List<EntityFilterRow> rows = entityFilterRows();
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        entityFilterListPage = Math.min(entityFilterListPage, pages - 1);
        int from = entityFilterListPage * pageSize;
        int to = Math.min(rows.size(), from + pageSize);
        List<ConfigControlView> controls = new ArrayList<>();
        for (int index = from; index < to; index++) {
            EntityFilterRow row = rows.get(index);
            controls.add(ConfigControlView.button(
                    ConfigControlId.entityRule("open", row.kind(), row.value()),
                    new UiRect(x, y, 350, HEIGHT),
                    UiText.translatable("screen.mc_teamviewer.entity_upload.filter_row",
                            tr(entityFilterKindKey(row.kind())), UiText.literal(row.value())),
                    null, true));
            controls.add(ConfigControlView.button(
                    ConfigControlId.entityRule("delete", row.kind(), row.value()),
                    new UiRect(x + 360, y, 70, HEIGHT),
                    tr("screen.mc_teamviewer.entity_upload.delete_rule"), null, true));
            y += 24;
        }
        if (rows.isEmpty()) {
            controls.add(ConfigControlView.text(ENTITY_FILTER_EMPTY, new UiRect(x, y, 430, HEIGHT),
                    tr("screen.mc_teamviewer.entity_upload.filters_empty"), null,
                    0xAAAAAA, true, ConfigControlView.TextAlignment.CENTER));
            y += 28;
        }
        controls.add(ConfigControlView.button(ENTITY_FILTER_PREVIOUS, new UiRect(x, y, 100, HEIGHT),
                tr("screen.mc_teamviewer.integration_plugin.previous"), null, entityFilterListPage > 0));
        controls.add(ConfigControlView.text(ENTITY_FILTER_PAGE_STATUS, new UiRect(x + 110, y, 210, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.integration_plugin.page",
                        UiText.literal(String.valueOf(entityFilterListPage + 1)),
                        UiText.literal(String.valueOf(pages))),
                null, 0xFFFFFF, true, ConfigControlView.TextAlignment.CENTER));
        controls.add(ConfigControlView.button(ENTITY_FILTER_NEXT, new UiRect(x + 330, y, 100, HEIGHT),
                tr("screen.mc_teamviewer.integration_plugin.next"), null, entityFilterListPage + 1 < pages));
        y += 28;
        String[] kinds = {
                Config.ENTITY_FILTER_ALLOW_TYPE, Config.ENTITY_FILTER_DENY_TYPE,
                Config.ENTITY_FILTER_ALLOW_NAME, Config.ENTITY_FILTER_DENY_NAME
        };
        for (int index = 0; index < kinds.length; index++) {
            controls.add(ConfigControlView.button(
                    ConfigControlId.entityRule("add", kinds[index], ""),
                    new UiRect(x + (index % 2) * 217, y + (index / 2) * 24, 213, HEIGHT),
                    tr(entityFilterAddKey(kinds[index])), null,
                    config.entityFilterRuleCount() < Config.MAX_ENTITY_FILTER_RULES));
        }
        y += 52;
        controls.add(button(BACK, x, y, 430, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.ENTITY_FILTERS,
                tr("screen.mc_teamviewer.entity_upload.filters_title"), 20, controls);
    }

    private ConfigPageView entityFilterEditPage(int width, int height) {
        int x = (width - 360) / 2;
        int y = Math.max(70, (height - 130) / 2);
        List<ConfigControlView> controls = new ArrayList<>();
        controls.add(ConfigControlView.text(ENTITY_FILTER_PAGE_STATUS, new UiRect(x, y, 360, HEIGHT),
                tr(entityFilterKindKey(editingEntityFilterKind)), null, 0xFFFFFF,
                true, ConfigControlView.TextAlignment.CENTER));
        y += 34;
        controls.add(field(ENTITY_FILTER_VALUE, x, y, 360,
                "screen.mc_teamviewer.entity_upload.rule_value",
                entityFilterIsType(editingEntityFilterKind)
                        ? "screen.mc_teamviewer.entity_upload.type_hint"
                        : "screen.mc_teamviewer.entity_upload.name_hint",
                null, Config.MAX_ENTITY_FILTER_VALUE_LENGTH));
        y += 38;
        controls.add(button(ENTITY_FILTER_SAVE, x, y, 176,
                "screen.mc_teamviewer.entity_upload.save_rule", null));
        controls.add(button(BACK, x + 184, y, 176, "screen.mc_teamviewer.config.cancel", null));
        return new ConfigPageView(ConfigPageId.ENTITY_FILTER_EDIT,
                tr("screen.mc_teamviewer.entity_upload.filter_edit_title"), 24, controls);
    }

    private ConfigPageView waypointPage(int width, int height) {
        int start = (height - (30 * 3 + 25 * 6)) / 2;
        int y = start + 30;
        int left = (width - 346) / 2;
        int right = left + 176;
        List<ConfigControlView> c = new ArrayList<>();
        c.add(field(WAYPOINT_TIMEOUT, left, y, 170, "screen.mc_teamviewer.config.waypoint_timeout",
                "screen.mc_teamviewer.config.waypoint_timeout_hint", "screen.mc_teamviewer.config.waypoint_timeout.tooltip", 10));
        c.add(field(LONG_TERM_WAYPOINT_TIMEOUT, right, y, 170, "screen.mc_teamviewer.config.long_term_waypoint_timeout",
                "screen.mc_teamviewer.config.long_term_waypoint_timeout_hint", "screen.mc_teamviewer.config.long_term_waypoint_timeout.tooltip", 10));
        y += 30;
        c.add(field(QUICK_MARK_MAX_COUNT, left, y, 170, "screen.mc_teamviewer.config.quick_mark_max_count",
                "screen.mc_teamviewer.config.quick_mark_max_count_hint", "screen.mc_teamviewer.config.quick_mark_max_count.tooltip", 10));
        String styleKey = "screen.mc_teamviewer.config.waypoint_ui_style." + config.getWaypointUiStyle();
        c.add(ConfigControlView.button(WAYPOINT_UI_STYLE, new UiRect(right, y, 170, HEIGHT),
                UiText.translatable("screen.mc_teamviewer.config.value", tr("screen.mc_teamviewer.config.waypoint_ui_style"), tr(styleKey)),
                tr("screen.mc_teamviewer.config.waypoint_ui_style.tooltip"), true));
        y += 30;
        c.add(toggleButtonWithTooltip(SHOW_SHARED_WAYPOINTS, left, y, 170, "screen.mc_teamviewer.config.show_shared_waypoints", config.isShowSharedWaypoints()));
        c.add(toggleButtonWithTooltip(SHOW_OWN_SHARED_WAYPOINTS, right, y, 170, "screen.mc_teamviewer.config.show_own_shared_waypoints_on_minimap", config.isShowOwnSharedWaypointsOnMinimap()));
        y += 25;
        c.add(toggleButtonWithTooltip(MIDDLE_DOUBLE_CLICK_MARK, left, y, 170, "screen.mc_teamviewer.config.middle_double_click_mark", config.isEnableMiddleDoubleClickMark()));
        c.add(toggleButtonWithTooltip(MIDDLE_CLICK_CANCEL, right, y, 170, "screen.mc_teamviewer.config.middle_click_cancel_waypoint", config.isEnableMiddleClickCancelWaypoint()));
        y += 25;
        c.add(toggleButtonWithTooltip(ENABLE_LONG_TERM_WAYPOINT, left, y, 170, "screen.mc_teamviewer.config.enable_long_term_waypoint", config.isEnableLongTermWaypoint()));
        c.add(toggleButtonWithTooltip(AUTO_CANCEL_ON_ENTITY_DEATH, right, y, 170, "screen.mc_teamviewer.config.auto_cancel_waypoint_on_entity_death", config.isAutoCancelWaypointOnEntityDeath()));
        y += 25;
        c.add(button(OPEN_WAYPOINT_SHAPE, left, y, 346, "screen.mc_teamviewer.config.waypoint_shape_settings", "screen.mc_teamviewer.config.waypoint_shape_settings.tooltip"));
        y += 25;
        c.add(button(BACK, left, y, 346, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.WAYPOINT, tr("screen.mc_teamviewer.waypoint_config.title"), start - 30, c);
    }

    private ConfigPageView waypointShapePage(int width, int height) {
        int start = (height - (30 * 3 + 25)) / 2;
        int y = start + 30;
        int left = (width - 346) / 2;
        int right = left + 176;
        List<ConfigControlView> c = new ArrayList<>();
        c.add(field(WAYPOINT_BEAM_WIDTH, left, y, 170, "screen.mc_teamviewer.config.waypoint_beacon_beam_width",
                "screen.mc_teamviewer.config.waypoint_beacon_beam_width_hint", "screen.mc_teamviewer.config.waypoint_beacon_beam_width.tooltip", 10));
        c.add(field(WAYPOINT_BEAM_HEIGHT, right, y, 170, "screen.mc_teamviewer.config.waypoint_beacon_beam_height",
                "screen.mc_teamviewer.config.waypoint_beacon_beam_height_hint", "screen.mc_teamviewer.config.waypoint_beacon_beam_height.tooltip", 10));
        y += 30;
        c.add(field(TAMPERMONKEY_BEAM_WIDTH, left, y, 170, "screen.mc_teamviewer.config.tampermonkey_beam_width",
                "screen.mc_teamviewer.config.tampermonkey_beam_width_hint", "screen.mc_teamviewer.config.tampermonkey_beam_width.tooltip", 10));
        c.add(field(TAMPERMONKEY_BEAM_HEIGHT, right, y, 170, "screen.mc_teamviewer.config.tampermonkey_beam_height",
                "screen.mc_teamviewer.config.tampermonkey_beam_height_hint", "screen.mc_teamviewer.config.tampermonkey_beam_height.tooltip", 10));
        y += 30;
        c.add(button(BACK, left, y, 346, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.WAYPOINT_SHAPE, tr("screen.mc_teamviewer.waypoint_shape_config.title"), start - 30, c);
    }

    private ConfigPageView colorPage(int width, int height) {
        int start = (height - 30 * 7) / 2;
        int y = start + 30;
        int x = (width - 200) / 2;
        List<ConfigControlView> c = new ArrayList<>();
        c.add(field(BOX_COLOR, x, y, 200, "screen.mc_teamviewer.color_config.box_color", null, null, 16));
        y += 30;
        c.add(field(LINE_COLOR, x, y, 200, "screen.mc_teamviewer.color_config.line_color", null, null, 16));
        y += 30;
        c.add(field(FRIENDLY_TEAM_COLOR, x, y, 200, "screen.mc_teamviewer.color_config.friendly_team_color", null, null, 16));
        y += 30;
        c.add(field(NEUTRAL_TEAM_COLOR, x, y, 200, "screen.mc_teamviewer.color_config.neutral_team_color", null, null, 16));
        y += 30;
        c.add(field(ENEMY_TEAM_COLOR, x, y, 200, "screen.mc_teamviewer.color_config.enemy_team_color", null, null, 16));
        y += 30;
        c.add(button(BACK, x, y, 200, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.COLOR, tr("screen.mc_teamviewer.color_config.title"), start - 30, c);
    }

    private ConfigPageView packetCapturePage(int width, int height) {
        int total = 18 * 5 + 28 * 3;
        int start = (height - total) / 2;
        int x = (width - 240) / 2;
        NetworkManager network = control.getNetworkManager();
        boolean active = network.isPacketDumpCaptureActive();
        String current = network.getPacketDumpCurrentPath();
        String last = network.getPacketDumpLastSavedPath();
        List<ConfigControlView> c = new ArrayList<>();
        c.add(ConfigControlView.text(PACKET_CAPTURE_DESCRIPTION, new UiRect(x, start, 240, 12),
                tr("screen.mc_teamviewer.packet_capture.description"), null, 0xE0E0E0, true, ConfigControlView.TextAlignment.CENTER));
        UiText status = !active ? tr("screen.mc_teamviewer.packet_capture.status_idle")
                : isBlank(current) ? tr("screen.mc_teamviewer.packet_capture.status_waiting")
                : tr("screen.mc_teamviewer.packet_capture.status_running");
        c.add(ConfigControlView.text(PACKET_CAPTURE_STATUS, new UiRect(x, start + 18, 240, 12),
                status, null, 0xFFD166, true, ConfigControlView.TextAlignment.CENTER));
        c.add(ConfigControlView.text(PACKET_CAPTURE_CURRENT_PATH, new UiRect(x, start + 36, 240, 12),
                isBlank(current) ? tr("screen.mc_teamviewer.packet_capture.current_file_empty")
                        : UiText.translatable("screen.mc_teamviewer.packet_capture.current_file", UiText.literal(abbreviatePath(current))),
                isBlank(current) ? null : UiText.literal(current), 0xA7F3D0, true, ConfigControlView.TextAlignment.CENTER));
        c.add(ConfigControlView.text(PACKET_CAPTURE_LAST_PATH, new UiRect(x, start + 54, 240, 12),
                isBlank(last) ? tr("screen.mc_teamviewer.packet_capture.last_file_empty")
                        : UiText.translatable("screen.mc_teamviewer.packet_capture.last_file", UiText.literal(abbreviatePath(last))),
                isBlank(last) ? null : UiText.literal(last), 0x93C5FD, true, ConfigControlView.TextAlignment.CENTER));
        int y = start + 72;
        c.add(ConfigControlView.button(PACKET_CAPTURE_START, new UiRect(x, y, 240, HEIGHT),
                tr("screen.mc_teamviewer.packet_capture.start"), null, !active));
        y += 28;
        c.add(ConfigControlView.button(PACKET_CAPTURE_STOP, new UiRect(x, y, 240, HEIGHT),
                tr("screen.mc_teamviewer.packet_capture.stop"), null, active));
        y += 28;
        c.add(button(BACK, x, y, 240, "screen.mc_teamviewer.config.back", null));
        return new ConfigPageView(ConfigPageId.PACKET_CAPTURE, tr("screen.mc_teamviewer.packet_capture.title"), start - 24, c);
    }

    private void applyPageFields(ConfigPageId page) {
        switch (page) {
            case DISPLAY -> applyDisplayFields();
            case NETWORK -> applyNetworkFields();
            case ENTITY_UPLOAD -> applyEntityUploadFields();
            case COLOR -> applyColorFields();
            case WAYPOINT -> applyWaypointFields();
            case WAYPOINT_SHAPE -> applyWaypointShapeFields();
            default -> { }
        }
    }

    private void applyDisplayFields() {
        try {
            String distance = value(RENDER_DISTANCE).trim();
            if (!distance.isEmpty()) {
                int parsed = Integer.parseInt(distance);
                if (parsed > 0) config.setRenderDistance(parsed);
            }
            String offset = value(TRACER_TOP_OFFSET).trim();
            if (!offset.isEmpty()) config.setTracerTopOffset(Double.parseDouble(offset));
        } catch (NumberFormatException ignored) { }
    }

    private void applyNetworkFields() {
        try {
            int update = Integer.parseInt(value(UPDATE_INTERVAL).trim());
            if (update > 0) config.setUpdateInterval(update);
            int battleUpdate = Integer.parseInt(value(BATTLE_MAP_UPDATE_INTERVAL).trim());
            if (battleUpdate > 0) config.setBattleMapUpdateIntervalTicks(battleUpdate);
            int keepalive = Integer.parseInt(value(BATTLE_MAP_KEEPALIVE_INTERVAL).trim());
            if (keepalive > 0) config.setBattleMapKeepaliveIntervalSeconds(keepalive);
            int retention = Integer.parseInt(value(BATTLE_MAP_CACHE_RETENTION).trim());
            if (retention > 0) config.setBattleMapCacheRetentionSeconds(retention);
        } catch (NumberFormatException ignored) { }
    }

    private void applyEntityUploadFields() {
        try {
            String value = value(ENTITY_REPORT_FIXED_INTERVAL).trim();
            if (!value.isEmpty()) config.setEntityReportFixedIntervalTicks(Integer.parseInt(value));
            config.save();
        } catch (NumberFormatException ignored) { }
    }

    private ConfigUiAction activateEntityRuleAction(ConfigControlId id) {
        String action = id.entityRuleAction();
        if ("delete".equals(action)) {
            if (config.removeEntityFilterRule(id.entityRuleKind(), id.entityRuleValue())) config.save();
            return ConfigUiAction.reload();
        }
        if ("add".equals(action) || "open".equals(action)) {
            editingEntityFilterKind = id.entityRuleKind();
            editingEntityFilterOriginalValue = "open".equals(action) ? id.entityRuleValue() : "";
            textValues.put(ENTITY_FILTER_VALUE, editingEntityFilterOriginalValue);
            return ConfigUiAction.open(ConfigPageId.ENTITY_FILTER_EDIT);
        }
        return ConfigUiAction.stay();
    }

    private ConfigUiAction saveEntityFilterRule() {
        String value = value(ENTITY_FILTER_VALUE);
        if (!editingEntityFilterOriginalValue.isEmpty()) {
            config.removeEntityFilterRule(editingEntityFilterKind, editingEntityFilterOriginalValue);
        }
        boolean saved = config.addEntityFilterRule(editingEntityFilterKind, value);
        if (!saved && !editingEntityFilterOriginalValue.isEmpty()) {
            config.addEntityFilterRule(editingEntityFilterKind, editingEntityFilterOriginalValue);
        }
        config.save();
        return saved ? ConfigUiAction.open(ConfigPageId.ENTITY_FILTERS) : ConfigUiAction.stay();
    }

    private List<EntityFilterRow> entityFilterRows() {
        List<EntityFilterRow> rows = new ArrayList<>();
        config.getEntityAllowedTypes().forEach(value -> rows.add(new EntityFilterRow(Config.ENTITY_FILTER_ALLOW_TYPE, value)));
        config.getEntityDeniedTypes().forEach(value -> rows.add(new EntityFilterRow(Config.ENTITY_FILTER_DENY_TYPE, value)));
        config.getEntityAllowedNames().forEach(value -> rows.add(new EntityFilterRow(Config.ENTITY_FILTER_ALLOW_NAME, value)));
        config.getEntityDeniedNames().forEach(value -> rows.add(new EntityFilterRow(Config.ENTITY_FILTER_DENY_NAME, value)));
        return rows;
    }

    private static boolean entityFilterIsType(String kind) {
        return Config.ENTITY_FILTER_ALLOW_TYPE.equals(kind) || Config.ENTITY_FILTER_DENY_TYPE.equals(kind);
    }

    private static String entityFilterKindKey(String kind) {
        return "screen.mc_teamviewer.entity_upload.filter_kind." + kind;
    }

    private static String entityFilterAddKey(String kind) {
        return "screen.mc_teamviewer.entity_upload.add_" + kind;
    }

    private void applyColorFields() {
        Integer parsed = parseColor(value(BOX_COLOR));
        if (parsed != null) config.setBoxColor(parsed);
        parsed = parseColor(value(LINE_COLOR));
        if (parsed != null) config.setLineColor(parsed);
        parsed = parseColor(value(FRIENDLY_TEAM_COLOR));
        if (parsed != null) config.setFriendlyTeamColor(parsed);
        parsed = parseColor(value(NEUTRAL_TEAM_COLOR));
        if (parsed != null) config.setNeutralTeamColor(parsed);
        parsed = parseColor(value(ENEMY_TEAM_COLOR));
        if (parsed != null) config.setEnemyTeamColor(parsed);
    }

    private void applyWaypointFields() {
        try {
            String normal = value(WAYPOINT_TIMEOUT).trim();
            if (!normal.isEmpty()) config.setWaypointTimeoutSeconds(Integer.parseInt(normal));
            String longTerm = value(LONG_TERM_WAYPOINT_TIMEOUT).trim();
            if (!longTerm.isEmpty()) config.setLongTermWaypointTimeoutSeconds(Integer.parseInt(longTerm));
            String max = value(QUICK_MARK_MAX_COUNT).trim();
            if (!max.isEmpty()) config.setMaxQuickMarkCount(Integer.parseInt(max));
        } catch (NumberFormatException ignored) { }
    }

    private void applyWaypointShapeFields() {
        try {
            config.setWaypointBeaconBeamWidth(Double.parseDouble(value(WAYPOINT_BEAM_WIDTH).trim()));
            config.setWaypointBeaconBeamHeight(Double.parseDouble(value(WAYPOINT_BEAM_HEIGHT).trim()));
            config.setTampermonkeyBeamWidth(Double.parseDouble(value(TAMPERMONKEY_BEAM_WIDTH).trim()));
            config.setTampermonkeyBeamHeight(Double.parseDouble(value(TAMPERMONKEY_BEAM_HEIGHT).trim()));
        } catch (NumberFormatException ignored) { }
    }

    private ConfigUiAction toggleAutoConnect() {
        config.setAutoConnectOnMultiplayerJoin(!config.isAutoConnectOnMultiplayerJoin());
        return ConfigUiAction.stay();
    }

    private ConfigUiAction connect() {
        saveRoot();
        if (control.isEnabled()) {
            control.reconnect();
        } else {
            control.setEnabled(true);
            control.reconnect();
        }
        return ConfigUiAction.stay();
    }

    private ConfigUiAction disconnect() {
        control.disconnect();
        return ConfigUiAction.stay();
    }

    private ConfigUiAction cycleTracerMode() {
        config.setTracerStartMode(config.isTracerStartTop() ? Config.TRACER_START_CROSSHAIR : Config.TRACER_START_TOP);
        return ConfigUiAction.stay();
    }

    private ConfigUiAction cycleWaypointStyle() {
        String next = switch (config.getWaypointUiStyle()) {
            case Config.WAYPOINT_UI_BEACON -> Config.WAYPOINT_UI_RING;
            case Config.WAYPOINT_UI_RING -> Config.WAYPOINT_UI_PIN;
            default -> Config.WAYPOINT_UI_BEACON;
        };
        config.setWaypointUiStyle(next);
        return ConfigUiAction.stay();
    }

    private ConfigUiAction cycleBattleMapSource() {
        List<String> sourceIds = control.getIntegrationCapabilities().stream()
                .filter(capability -> IntegrationRole.BATTLE_MAP_SOURCE.id().equals(capability.role()))
                .map(IntegrationCapability::id)
                .distinct().toList();
        if (sourceIds.isEmpty()) return ConfigUiAction.stay();
        String current = config.getBattleMapSourceId();
        int index = sourceIds.indexOf(current);
        config.setBattleMapSourceId(sourceIds.get((index + 1 + sourceIds.size()) % sourceIds.size()));
        return ConfigUiAction.stay();
    }

    private UiText battleMapSourceLabel() {
        String sourceId = config.getBattleMapSourceId();
        IntegrationCapability capability = battleMapCapability(sourceId);
        UiText name = capability == null ? UiText.literal(sourceId) : capabilityName(capability);
        if (capability == null) return name;
        return capability.status() == IntegrationSupportStatus.AVAILABLE
                ? name
                : t("screen.mc_teamviewer.integration_plugin.capability_with_status",
                name, supportStatusText(capability.status()));
    }

    private UiText battleMapSourceTooltip() {
        IntegrationCapability capability = battleMapCapability(config.getBattleMapSourceId());
        if (capability == null) return null;
        return capabilityDiagnostic(capability);
    }

    private IntegrationCapability battleMapCapability(String sourceId) {
        return control.getIntegrationCapabilities().stream()
                .filter(capability -> capability.id().equals(sourceId))
                .findFirst().orElse(null);
    }

    private ConfigUiAction startPacketCapture() {
        control.getNetworkManager().startPacketDumpCapture();
        control.showActionBar("§c[TV] 已开始抓包，游戏内将持续显示抓包提示");
        return ConfigUiAction.stay();
    }

    private ConfigUiAction stopPacketCapture() {
        control.getNetworkManager().stopPacketDumpCapture();
        control.showActionBar("§a[TV] 已结束抓包");
        return ConfigUiAction.stay();
    }

    private ConfigUiAction toggle(boolean current, BooleanSetter setter) {
        setter.set(!current);
        return ConfigUiAction.stay();
    }

    private ConfigUiAction toggleAndSave(boolean current, BooleanSetter setter) {
        setter.set(!current);
        config.save();
        return ConfigUiAction.stay();
    }

    private ConfigControlView field(ConfigControlId id, int x, int y, int width, String label,
                                    String hint, String tooltip, int maxLength) {
        return ConfigControlView.textField(id, new UiRect(x, y, width, HEIGHT),
                new UiRect(x, y - LABEL_SPACING, width, LABEL_HEIGHT), tr(label),
                hint == null ? null : tr(hint), tooltip == null ? null : tr(tooltip), value(id), maxLength);
    }

    private ConfigControlView button(ConfigControlId id, int x, int y, int width, String label, String tooltip) {
        return ConfigControlView.button(id, new UiRect(x, y, width, HEIGHT), tr(label),
                tooltip == null ? null : tr(tooltip), true);
    }

    private ConfigControlView toggleButton(ConfigControlId id, int x, int y, int width, String label, boolean enabled) {
        return ConfigControlView.button(id, new UiRect(x, y, width, HEIGHT), UiText.toggle(label, enabled),
                null, true);
    }

    private ConfigControlView toggleButtonWithTooltip(ConfigControlId id, int x, int y, int width, String label, boolean enabled) {
        return ConfigControlView.button(id, new UiRect(x, y, width, HEIGHT), UiText.toggle(label, enabled),
                tr(label + ".tooltip"), true);
    }

    private static ConfigControlView infoLine(int x, int y, int width, String key, int color) {
        return ConfigControlView.text(new ConfigControlId("info:" + key), new UiRect(x, y, width, HEIGHT),
                tr(key), null, color, true, ConfigControlView.TextAlignment.CENTER);
    }

    private static UiText capabilityName(IntegrationCapability capability) {
        return switch (capability.id()) {
            case IntegrationIds.JOURNEYMAP_PLAYERS -> tr("screen.mc_teamviewer.integration_plugin.capability.journeymap_players");
            case IntegrationIds.JOURNEYMAP_BEACONS -> tr("screen.mc_teamviewer.integration_plugin.capability.journeymap_beacons");
            case IntegrationIds.JOURNEYMAP_WAYPOINTS -> tr("screen.mc_teamviewer.integration_plugin.capability.journeymap_waypoints");
            case IntegrationIds.XAERO_WORLDMAP -> tr("screen.mc_teamviewer.integration_plugin.capability.xaero_worldmap");
            case IntegrationIds.XAERO_MINIMAP -> tr("screen.mc_teamviewer.integration_plugin.capability.xaero_minimap");
            case IntegrationIds.NODEMC_BATTLE_MAP -> tr("screen.mc_teamviewer.integration_plugin.capability.nodemc_battle_map");
            case IntegrationIds.SIMMC_BATTLE_MAP -> tr("screen.mc_teamviewer.integration_plugin.capability.simmc_battle_map");
            case IntegrationIds.EXAMPLE_REMOTE_PLAYER -> tr("screen.mc_teamviewer.integration_plugin.capability.example_remote_player");
            case IntegrationIds.EXAMPLE_SHARED_WAYPOINT -> tr("screen.mc_teamviewer.integration_plugin.capability.example_shared_waypoint");
            case IntegrationIds.EXAMPLE_BATTLE_MAP -> tr("screen.mc_teamviewer.integration_plugin.capability.example_battle_map");
            case IntegrationIds.TAB_LABEL_RELATIONS -> tr("screen.mc_teamviewer.integration_plugin.capability.tab_label_relations");
            default -> UiText.literal(capability.displayName());
        };
    }

    private static UiText supportStatusText(IntegrationSupportStatus status) {
        return tr("screen.mc_teamviewer.integration_plugin.support."
                + status.name().toLowerCase(Locale.ROOT));
    }

    private static UiText capabilityDiagnostic(IntegrationCapability capability) {
        if (capability.detail().isBlank()) return null;
        return t("screen.mc_teamviewer.integration_plugin.technical_detail",
                supportStatusText(capability.status()), UiText.literal(capability.detail()));
    }

    private String value(ConfigControlId id) {
        return textValues.getOrDefault(id, "");
    }

    private static UiText tr(String key) {
        return UiText.translatable(key);
    }

    private static UiText t(String key, UiText... arguments) {
        return UiText.translatable(key, arguments);
    }

    private static String color(int value) {
        return String.format(Locale.ROOT, "0x%08X", value);
    }

    private static Integer parseColor(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) return (int) Long.parseLong(text.substring(2), 16);
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16);
                if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String statusKey(NetworkManager.ConnectionStage stage) {
        return switch (stage) {
            case CONNECTED -> "connection.status.connected";
            case CONNECTING -> "connection.status.connecting";
            case CONNECTED_HANDSHAKING -> "connection.status.connected_handshaking";
            case FAILED -> "connection.status.failed";
            case DISCONNECTED -> "connection.status.disconnected";
        };
    }

    private static int statusColor(NetworkManager.ConnectionStage stage) {
        return switch (stage) {
            case CONNECTED -> 0x00FF00;
            case CONNECTING -> 0xFFFF55;
            case CONNECTED_HANDSHAKING -> 0x55FFFF;
            case FAILED -> 0xFFAA00;
            case DISCONNECTED -> 0xFF0000;
        };
    }

    private static String abbreviatePath(String path) {
        return path == null || path.length() <= 36 ? fallback(path, "") : "..." + path.substring(path.length() - 33);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String fallback(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    @FunctionalInterface
    private interface BooleanSetter {
        void set(boolean value);
    }

    private record EntityFilterRow(String kind, String value) { }
}
