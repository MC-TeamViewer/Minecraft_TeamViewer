package fun.prof_chen.teamviewer.main_code.config.ui;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable string control key; plugin controls can carry a plugin ID and setting key. */
public record ConfigControlId(String value) {
    private static final String PLUGIN_PREFIX = "plugin:";
    private static final String PLUGIN_RUNTIME_PREFIX = "plugin-runtime:";
    private static final String SETTING_PREFIX = "setting:";
    private static final String ENTITY_RULE_PREFIX = "entity-rule:";

    public ConfigControlId {
        value = Objects.requireNonNull(value, "value");
    }

    public static ConfigControlId plugin(String pluginId, String action) {
        return new ConfigControlId(PLUGIN_PREFIX + action + ":" + pluginId);
    }

    public static ConfigControlId setting(String pluginId, String key) {
        return new ConfigControlId(SETTING_PREFIX + pluginId + ":" + key);
    }

    public static ConfigControlId pluginRuntimeAction(String pluginId, String actionId) {
        return new ConfigControlId(PLUGIN_RUNTIME_PREFIX + encode(pluginId) + ":" + encode(actionId));
    }

    public static ConfigControlId entityRule(String action, String kind, String value) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return new ConfigControlId(ENTITY_RULE_PREFIX + action + ":" + kind + ":" + encoded);
    }

    public boolean isEntityRuleAction() { return value.startsWith(ENTITY_RULE_PREFIX); }

    public String entityRuleAction() {
        if (!isEntityRuleAction()) return "";
        int separator = value.indexOf(':', ENTITY_RULE_PREFIX.length());
        return separator < 0 ? "" : value.substring(ENTITY_RULE_PREFIX.length(), separator);
    }

    public String entityRuleKind() {
        if (!isEntityRuleAction()) return "";
        int first = value.indexOf(':', ENTITY_RULE_PREFIX.length());
        int second = first < 0 ? -1 : value.indexOf(':', first + 1);
        return second < 0 ? "" : value.substring(first + 1, second);
    }

    public String entityRuleValue() {
        if (!isEntityRuleAction()) return "";
        int first = value.indexOf(':', ENTITY_RULE_PREFIX.length());
        int second = first < 0 ? -1 : value.indexOf(':', first + 1);
        if (second < 0 || second + 1 >= value.length()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(value.substring(second + 1)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public boolean isPluginAction() { return value.startsWith(PLUGIN_PREFIX); }
    public boolean isPluginRuntimeAction() { return value.startsWith(PLUGIN_RUNTIME_PREFIX); }
    public boolean isPluginSetting() { return value.startsWith(SETTING_PREFIX); }

    public String pluginAction() {
        if (!isPluginAction()) return "";
        int separator = value.indexOf(':', PLUGIN_PREFIX.length());
        return separator < 0 ? "" : value.substring(PLUGIN_PREFIX.length(), separator);
    }

    public String pluginId() {
        if (isPluginRuntimeAction()) {
            int separator = value.indexOf(':', PLUGIN_RUNTIME_PREFIX.length());
            return separator < 0 ? "" : decode(value.substring(PLUGIN_RUNTIME_PREFIX.length(), separator));
        }
        if (isPluginAction()) {
            int separator = value.indexOf(':', PLUGIN_PREFIX.length());
            return separator < 0 ? "" : value.substring(separator + 1);
        }
        if (isPluginSetting()) {
            int separator = value.indexOf(':', SETTING_PREFIX.length());
            return separator < 0 ? "" : value.substring(SETTING_PREFIX.length(), separator);
        }
        return "";
    }

    public String pluginRuntimeActionId() {
        if (!isPluginRuntimeAction()) return "";
        int separator = value.indexOf(':', PLUGIN_RUNTIME_PREFIX.length());
        return separator < 0 ? "" : decode(value.substring(separator + 1));
    }

    public String settingKey() {
        if (!isPluginSetting()) return "";
        int separator = value.indexOf(':', SETTING_PREFIX.length());
        return separator < 0 ? "" : value.substring(separator + 1);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static ConfigControlId fixed(String value) { return new ConfigControlId(value); }

    public static final ConfigControlId SERVER_URL = fixed("SERVER_URL");
    public static final ConfigControlId ROOM_CODE = fixed("ROOM_CODE");
    public static final ConfigControlId ALLOW_INSECURE_TLS = fixed("ALLOW_INSECURE_TLS");
    public static final ConfigControlId OPEN_DISPLAY = fixed("OPEN_DISPLAY");
    public static final ConfigControlId OPEN_NETWORK = fixed("OPEN_NETWORK");
    public static final ConfigControlId OPEN_PLUGINS = fixed("OPEN_PLUGINS");
    public static final ConfigControlId OPEN_ENTITY_UPLOAD = fixed("OPEN_ENTITY_UPLOAD");
    public static final ConfigControlId OPEN_ENTITY_FILTERS = fixed("OPEN_ENTITY_FILTERS");
    public static final ConfigControlId SAVE_ROOT = fixed("SAVE_ROOT");
    public static final ConfigControlId AUTO_CONNECT = fixed("AUTO_CONNECT");
    public static final ConfigControlId CONNECT = fixed("CONNECT");
    public static final ConfigControlId DISCONNECT = fixed("DISCONNECT");
    public static final ConfigControlId CONNECTION_STATUS = fixed("CONNECTION_STATUS");
    public static final ConfigControlId SAVE_HINT = fixed("SAVE_HINT");
    public static final ConfigControlId RENDER_DISTANCE = fixed("RENDER_DISTANCE");
    public static final ConfigControlId TRACER_TOP_OFFSET = fixed("TRACER_TOP_OFFSET");
    public static final ConfigControlId SHOW_BOXES = fixed("SHOW_BOXES");
    public static final ConfigControlId SHOW_LINES = fixed("SHOW_LINES");
    public static final ConfigControlId SHOW_LAST_SEEN_PLAYERS = fixed("SHOW_LAST_SEEN_PLAYERS");
    public static final ConfigControlId SHOW_LAST_SEEN_BOXES = fixed("SHOW_LAST_SEEN_BOXES");
    public static final ConfigControlId SHOW_LAST_SEEN_LINES = fixed("SHOW_LAST_SEEN_LINES");
    public static final ConfigControlId TRACER_START_MODE = fixed("TRACER_START_MODE");
    public static final ConfigControlId XRAY_MARKERS_AND_BOXES = fixed("XRAY_MARKERS_AND_BOXES");
    public static final ConfigControlId OPEN_COLOR = fixed("OPEN_COLOR");
    public static final ConfigControlId OPEN_WAYPOINT = fixed("OPEN_WAYPOINT");
    public static final ConfigControlId SHOW_NETWORK_TRAFFIC_HUD = fixed("SHOW_NETWORK_TRAFFIC_HUD");
    public static final ConfigControlId BOX_COLOR = fixed("BOX_COLOR");
    public static final ConfigControlId LINE_COLOR = fixed("LINE_COLOR");
    public static final ConfigControlId FRIENDLY_TEAM_COLOR = fixed("FRIENDLY_TEAM_COLOR");
    public static final ConfigControlId NEUTRAL_TEAM_COLOR = fixed("NEUTRAL_TEAM_COLOR");
    public static final ConfigControlId ENEMY_TEAM_COLOR = fixed("ENEMY_TEAM_COLOR");
    public static final ConfigControlId WAYPOINT_TIMEOUT = fixed("WAYPOINT_TIMEOUT");
    public static final ConfigControlId LONG_TERM_WAYPOINT_TIMEOUT = fixed("LONG_TERM_WAYPOINT_TIMEOUT");
    public static final ConfigControlId QUICK_MARK_MAX_COUNT = fixed("QUICK_MARK_MAX_COUNT");
    public static final ConfigControlId WAYPOINT_UI_STYLE = fixed("WAYPOINT_UI_STYLE");
    public static final ConfigControlId SHOW_SHARED_WAYPOINTS = fixed("SHOW_SHARED_WAYPOINTS");
    public static final ConfigControlId SHOW_OWN_SHARED_WAYPOINTS = fixed("SHOW_OWN_SHARED_WAYPOINTS");
    public static final ConfigControlId MIDDLE_DOUBLE_CLICK_MARK = fixed("MIDDLE_DOUBLE_CLICK_MARK");
    public static final ConfigControlId MIDDLE_CLICK_CANCEL = fixed("MIDDLE_CLICK_CANCEL");
    public static final ConfigControlId AUTO_CANCEL_ON_ENTITY_DEATH = fixed("AUTO_CANCEL_ON_ENTITY_DEATH");
    public static final ConfigControlId ENABLE_LONG_TERM_WAYPOINT = fixed("ENABLE_LONG_TERM_WAYPOINT");
    public static final ConfigControlId OPEN_WAYPOINT_SHAPE = fixed("OPEN_WAYPOINT_SHAPE");
    public static final ConfigControlId WAYPOINT_BEAM_WIDTH = fixed("WAYPOINT_BEAM_WIDTH");
    public static final ConfigControlId WAYPOINT_BEAM_HEIGHT = fixed("WAYPOINT_BEAM_HEIGHT");
    public static final ConfigControlId TAMPERMONKEY_BEAM_WIDTH = fixed("TAMPERMONKEY_BEAM_WIDTH");
    public static final ConfigControlId TAMPERMONKEY_BEAM_HEIGHT = fixed("TAMPERMONKEY_BEAM_HEIGHT");
    public static final ConfigControlId UPDATE_INTERVAL = fixed("UPDATE_INTERVAL");
    public static final ConfigControlId BATTLE_MAP_UPDATE_INTERVAL = fixed("BATTLE_MAP_UPDATE_INTERVAL");
    public static final ConfigControlId BATTLE_MAP_KEEPALIVE_INTERVAL = fixed("BATTLE_MAP_KEEPALIVE_INTERVAL");
    public static final ConfigControlId BATTLE_MAP_CACHE_RETENTION = fixed("BATTLE_MAP_CACHE_RETENTION");
    public static final ConfigControlId UPLOAD_ENTITIES = fixed("UPLOAD_ENTITIES");
    public static final ConfigControlId ENTITY_REPORT_MODE = fixed("ENTITY_REPORT_MODE");
    public static final ConfigControlId ENTITY_REPORT_FIXED_INTERVAL = fixed("ENTITY_REPORT_FIXED_INTERVAL");
    public static final ConfigControlId ENTITY_FILTER_PREVIOUS = fixed("ENTITY_FILTER_PREVIOUS");
    public static final ConfigControlId ENTITY_FILTER_NEXT = fixed("ENTITY_FILTER_NEXT");
    public static final ConfigControlId ENTITY_FILTER_PAGE_STATUS = fixed("ENTITY_FILTER_PAGE_STATUS");
    public static final ConfigControlId ENTITY_FILTER_EMPTY = fixed("ENTITY_FILTER_EMPTY");
    public static final ConfigControlId ENTITY_FILTER_VALUE = fixed("ENTITY_FILTER_VALUE");
    public static final ConfigControlId ENTITY_FILTER_SAVE = fixed("ENTITY_FILTER_SAVE");
    public static final ConfigControlId UPLOAD_SHARED_WAYPOINTS = fixed("UPLOAD_SHARED_WAYPOINTS");
    public static final ConfigControlId USE_SYSTEM_PROXY = fixed("USE_SYSTEM_PROXY");
    public static final ConfigControlId PREFER_LOCAL_DATA = fixed("PREFER_LOCAL_DATA");
    public static final ConfigControlId BATTLE_MAP_SYNC = fixed("BATTLE_MAP_SYNC");
    public static final ConfigControlId BATTLE_MAP_SOURCE = fixed("BATTLE_MAP_SOURCE");
    public static final ConfigControlId BATTLE_MAP_DEBUG = fixed("BATTLE_MAP_DEBUG");
    public static final ConfigControlId OPEN_PACKET_CAPTURE = fixed("OPEN_PACKET_CAPTURE");
    public static final ConfigControlId PACKET_CAPTURE_DESCRIPTION = fixed("PACKET_CAPTURE_DESCRIPTION");
    public static final ConfigControlId PACKET_CAPTURE_STATUS = fixed("PACKET_CAPTURE_STATUS");
    public static final ConfigControlId PACKET_CAPTURE_CURRENT_PATH = fixed("PACKET_CAPTURE_CURRENT_PATH");
    public static final ConfigControlId PACKET_CAPTURE_LAST_PATH = fixed("PACKET_CAPTURE_LAST_PATH");
    public static final ConfigControlId PACKET_CAPTURE_START = fixed("PACKET_CAPTURE_START");
    public static final ConfigControlId PACKET_CAPTURE_STOP = fixed("PACKET_CAPTURE_STOP");
    public static final ConfigControlId OPEN_DEBUG_SETTINGS = fixed("OPEN_DEBUG_SETTINGS");
    public static final ConfigControlId DEBUG_SETTINGS_HINT = fixed("DEBUG_SETTINGS_HINT");
    public static final ConfigControlId CYCLE_COMPRESSION_SUITE = fixed("CYCLE_COMPRESSION_SUITE");
    public static final ConfigControlId TOGGLE_WS_PLAIN_NO_DEFLATE = fixed("TOGGLE_WS_PLAIN_NO_DEFLATE");
    public static final ConfigControlId PLUGIN_RESCAN = fixed("PLUGIN_RESCAN");
    public static final ConfigControlId PLUGIN_TAB_INSTALLED = fixed("PLUGIN_TAB_INSTALLED");
    public static final ConfigControlId PLUGIN_TAB_DISABLED = fixed("PLUGIN_TAB_DISABLED");
    public static final ConfigControlId PLUGIN_COMPACT_BACK = fixed("PLUGIN_COMPACT_BACK");
    public static final ConfigControlId PLUGIN_DIALOG_CLOSE = fixed("PLUGIN_DIALOG_CLOSE");
    public static final ConfigControlId PLUGIN_RUNTIME_CONFIRM = fixed("PLUGIN_RUNTIME_CONFIRM");
    public static final ConfigControlId PLUGIN_PREVIOUS = fixed("PLUGIN_PREVIOUS");
    public static final ConfigControlId PLUGIN_NEXT = fixed("PLUGIN_NEXT");
    public static final ConfigControlId PLUGIN_PAGE_STATUS = fixed("PLUGIN_PAGE_STATUS");
    public static final ConfigControlId PLUGIN_EMPTY_STATUS = fixed("PLUGIN_EMPTY_STATUS");
    public static final ConfigControlId DISABLED_PLUGIN_EMPTY_STATUS = fixed("DISABLED_PLUGIN_EMPTY_STATUS");
    public static final ConfigControlId DISABLED_PLUGIN_PATH = fixed("DISABLED_PLUGIN_PATH");
    public static final ConfigControlId PLUGIN_OPEN_DISABLED = fixed("PLUGIN_OPEN_DISABLED");
    public static final ConfigControlId DISABLED_PLUGIN_PREVIOUS = fixed("DISABLED_PLUGIN_PREVIOUS");
    public static final ConfigControlId DISABLED_PLUGIN_NEXT = fixed("DISABLED_PLUGIN_NEXT");
    public static final ConfigControlId PLUGIN_GUIDE_OPEN_DIRECTORY = fixed("PLUGIN_GUIDE_OPEN_DIRECTORY");
    public static final ConfigControlId PLUGIN_GUIDE_RETURN_LIST = fixed("PLUGIN_GUIDE_RETURN_LIST");
    public static final ConfigControlId PLUGIN_OPERATION_STATUS = fixed("PLUGIN_OPERATION_STATUS");
    public static final ConfigControlId BACK = fixed("BACK");
}
