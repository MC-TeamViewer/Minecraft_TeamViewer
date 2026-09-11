package fun.prof_chen.teamviewer.main_code.config.ui;

import fun.prof_chen.teamviewer.main_code.bridge.NetworkManager;
import fun.prof_chen.teamviewer.main_code.client.bridge.ClientControlGateway;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationCapability;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationImplementationSource;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationIds;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationSupportStatus;
import fun.prof_chen.teamviewer.main_code.client.sdk.PluginRuntimeStatus;
import fun.prof_chen.teamviewer.main_code.config.Config;
import fun.prof_chen.teamviewer.main_code.model.RemotePlayerInfo;
import fun.prof_chen.teamviewer.main_code.network.abstraction.RuntimeGateway;
import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportProcess;
import fun.prof_chen.teamviewer.main_code.plugin.PluginManifest;
import fun.prof_chen.teamviewer.main_code.plugin.PluginRuntimeAction;
import fun.prof_chen.teamviewer.main_code.plugin.PluginRuntimeState;
import fun.prof_chen.teamviewer.main_code.plugin.PluginSnapshot;
import fun.prof_chen.teamviewer.main_code.plugin.PluginSettingState;
import fun.prof_chen.teamviewer.main_code.plugin.DisabledPluginSnapshot;
import fun.prof_chen.teamviewer.main_code.plugin.PluginFileOperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUiSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesAllSevenCompletePages() {
        ConfigUiSession session = session(new Config());
        assertEquals(EnumSet.allOf(ConfigPageId.class), EnumSet.allOf(ConfigPageId.class).stream()
                .filter(page -> !session.page(page, 854, 480).controls().isEmpty())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ConfigPageId.class))));
        for (ConfigPageId page : ConfigPageId.values()) {
            List<ConfigControlId> ids = session.page(page, 854, 480).controls().stream()
                    .map(ConfigControlView::id).toList();
            assertEquals(ids.size(), Set.copyOf(ids).size(), page + " contains duplicate control IDs");
        }

        assertContains(session, ConfigPageId.ROOT,
                ConfigControlId.SERVER_URL, ConfigControlId.ROOM_CODE, ConfigControlId.ALLOW_INSECURE_TLS,
                ConfigControlId.OPEN_DISPLAY, ConfigControlId.OPEN_NETWORK, ConfigControlId.CONNECT,
                ConfigControlId.DISCONNECT, ConfigControlId.CONNECTION_STATUS);
        assertContains(session, ConfigPageId.DISPLAY,
                ConfigControlId.RENDER_DISTANCE, ConfigControlId.SHOW_BOXES, ConfigControlId.SHOW_LINES,
                ConfigControlId.TRACER_START_MODE, ConfigControlId.OPEN_COLOR, ConfigControlId.OPEN_WAYPOINT);
        assertTrue(session.page(ConfigPageId.DISPLAY, 854, 480).controls().stream()
                .noneMatch(control -> control.id().value().startsWith("JOURNEYMAP_REMOTE_")),
                "JourneyMap-owned settings must only be rendered by the plugin detail page");
        assertContains(session, ConfigPageId.NETWORK,
                ConfigControlId.UPDATE_INTERVAL, ConfigControlId.UPLOAD_ENTITIES,
                ConfigControlId.BATTLE_MAP_SOURCE, ConfigControlId.OPEN_DEBUG_SETTINGS,
                ConfigControlId.OPEN_ENTITY_UPLOAD);
        assertContains(session, ConfigPageId.ENTITY_UPLOAD,
                ConfigControlId.UPLOAD_ENTITIES, ConfigControlId.ENTITY_REPORT_MODE,
                ConfigControlId.ENTITY_REPORT_FIXED_INTERVAL, ConfigControlId.OPEN_ENTITY_FILTERS);
        assertContains(session, ConfigPageId.WAYPOINT,
                ConfigControlId.WAYPOINT_TIMEOUT, ConfigControlId.QUICK_MARK_MAX_COUNT,
                ConfigControlId.MIDDLE_DOUBLE_CLICK_MARK, ConfigControlId.AUTO_CANCEL_ON_ENTITY_DEATH,
                ConfigControlId.OPEN_WAYPOINT_SHAPE);
        assertContains(session, ConfigPageId.COLOR,
                ConfigControlId.BOX_COLOR, ConfigControlId.LINE_COLOR, ConfigControlId.FRIENDLY_TEAM_COLOR,
                ConfigControlId.NEUTRAL_TEAM_COLOR, ConfigControlId.ENEMY_TEAM_COLOR);
        assertContains(session, ConfigPageId.WAYPOINT_SHAPE,
                ConfigControlId.WAYPOINT_BEAM_WIDTH, ConfigControlId.WAYPOINT_BEAM_HEIGHT,
                ConfigControlId.TAMPERMONKEY_BEAM_WIDTH, ConfigControlId.TAMPERMONKEY_BEAM_HEIGHT);
        assertContains(session, ConfigPageId.PACKET_CAPTURE,
                ConfigControlId.PACKET_CAPTURE_START, ConfigControlId.PACKET_CAPTURE_STOP,
                ConfigControlId.PACKET_CAPTURE_CURRENT_PATH, ConfigControlId.PACKET_CAPTURE_LAST_PATH);
        assertContains(session, ConfigPageId.DEBUG_SETTINGS,
                ConfigControlId.OPEN_PACKET_CAPTURE, ConfigControlId.CYCLE_COMPRESSION_SUITE,
                ConfigControlId.TOGGLE_WS_PLAIN_NO_DEFLATE);
    }

    @Test
    void entityFilterEditorStoresExactCompiledRules() {
        Config config = new Config();
        config.setStoragePath(tempDir.resolve("entity-filter.json"));
        ConfigUiSession session = session(config);
        ConfigControlId addType = ConfigControlId.entityRule(
                "add", Config.ENTITY_FILTER_ALLOW_TYPE, "");
        ConfigUiAction edit = session.activate(ConfigPageId.ENTITY_FILTERS, addType);
        assertEquals(ConfigPageId.ENTITY_FILTER_EDIT, edit.targetPage());
        session.setText(ConfigControlId.ENTITY_FILTER_VALUE, " Minecraft:Zombie ");
        ConfigUiAction saved = session.activate(
                ConfigPageId.ENTITY_FILTER_EDIT, ConfigControlId.ENTITY_FILTER_SAVE);
        assertEquals(ConfigPageId.ENTITY_FILTERS, saved.targetPage());
        assertEquals(List.of("minecraft:zombie"), config.getEntityAllowedTypes());
        assertTrue(config.getEntityUploadFilter().allows("minecraft:zombie", null));
        assertFalse(config.getEntityUploadFilter().allows("minecraft:cow", null));
    }

    @Test
    void rootCancelAndSaveMatchLegacySemantics() {
        Config config = new Config();
        config.setStoragePath(tempDir.resolve("teamviewer.json"));
        config.setServerURL("ws://old");
        config.setRoomCode("old-room");
        ConfigUiSession session = session(config);

        session.setText(ConfigControlId.SERVER_URL, "ws://new");
        session.setText(ConfigControlId.ROOM_CODE, "new-room");
        session.activate(ConfigPageId.ROOT, ConfigControlId.AUTO_CONNECT);
        session.setChecked(ConfigControlId.ALLOW_INSECURE_TLS, true);
        assertTrue(session.hasUnsavedRootChanges());

        session.cancelRoot();
        assertEquals("ws://old", config.getServerURL());
        assertEquals("old-room", config.getRoomCode());
        assertFalse(config.isAutoConnectOnMultiplayerJoin());
        assertFalse(config.isAllowInsecureTls());

        session.setText(ConfigControlId.SERVER_URL, "ws://saved");
        session.setText(ConfigControlId.ROOM_CODE, "saved-room");
        session.setChecked(ConfigControlId.ALLOW_INSECURE_TLS, true);
        session.saveRoot();
        assertEquals("ws://saved", config.getServerURL());
        assertEquals("saved-room", config.getRoomCode());
        assertTrue(config.isAllowInsecureTls());
        assertFalse(session.hasUnsavedRootChanges());
        assertTrue(tempDir.resolve("teamviewer.json").toFile().isFile());
    }

    @Test
    void secondaryFieldsUseCommonValidationAndToggleLogic() {
        Config config = new Config();
        ConfigUiSession session = session(config);
        session.setText(ConfigControlId.WAYPOINT_TIMEOUT, "1");
        session.setText(ConfigControlId.LONG_TERM_WAYPOINT_TIMEOUT, "999999");
        session.setText(ConfigControlId.QUICK_MARK_MAX_COUNT, "99");
        session.close(ConfigPageId.WAYPOINT);
        assertEquals(10, config.getWaypointTimeoutSeconds());
        assertEquals(86400, config.getLongTermWaypointTimeoutSeconds());
        assertEquals(20, config.getMaxQuickMarkCount());

        boolean before = config.isShowBoxes();
        session.activate(ConfigPageId.DISPLAY, ConfigControlId.SHOW_BOXES);
        assertEquals(!before, config.isShowBoxes());
    }

    @Test
    void invalidColorsPreserveExistingValuesWithoutBlockingLaterFields() {
        Config config = new Config();
        config.setBoxColor(0xFF010203);
        config.setEnemyTeamColor(0xFF040506);
        ConfigUiSession session = session(config);

        session.setText(ConfigControlId.BOX_COLOR, "not-a-color");
        session.setText(ConfigControlId.LINE_COLOR, "#123456");
        session.setText(ConfigControlId.FRIENDLY_TEAM_COLOR, "0x80ABCDEF");
        session.setText(ConfigControlId.NEUTRAL_TEAM_COLOR, "-16711936");
        session.setText(ConfigControlId.ENEMY_TEAM_COLOR, "2147483648");
        session.close(ConfigPageId.COLOR);

        assertEquals(0xFF010203, config.getBoxColor());
        assertEquals(0xFF123456, config.getLineColor());
        assertEquals(0x80ABCDEF, config.getFriendlyTeamColor());
        assertEquals(-16711936, config.getNeutralTeamColor());
        assertEquals(0xFF040506, config.getEnemyTeamColor());

        session.setText(ConfigControlId.BOX_COLOR, "   ");
        session.close(ConfigPageId.COLOR);
        assertEquals(0xFF010203, config.getBoxColor());
    }

    @Test
    void journeyMapLegacySettingsAreIdenticalAndSafeInBothLayouts() {
        List<PluginManifest.SettingDefinition> definitions = List.of(
                new PluginManifest.SettingDefinition("show_remote_players", "boolean",
                        "Show remote players", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition("show_last_seen_players", "boolean",
                        "Show last-seen players", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition("show_online_map_markers", "boolean",
                        "Show online map markers", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition("show_online_world_beacons", "boolean",
                        "Show online world beacons", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition("show_offline_map_markers", "boolean",
                        "Show offline map markers", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition("show_offline_world_beacons", "boolean",
                        "Show offline world beacons", true, null, null, List.of(), false));
        PluginSnapshot plugin = new PluginSnapshot(
                IntegrationIds.PLUGIN_JOURNEYMAP, "JourneyMap", "1.1.0", true, true, true,
                PluginRuntimeStatus.ACTIVE, "", null,
                Map.of("show_remote_players", true, "show_last_seen_players", true,
                        "show_online_map_markers", true, "show_online_world_beacons", true,
                        "show_offline_map_markers", true, "show_offline_world_beacons", true),
                definitions, Map.of(
                        "show_remote_players", new PluginSettingState(true, true,
                                "Map and world visibility follow JourneyMap's global waypoint settings"),
                        "show_last_seen_players", new PluginSettingState(true, true,
                                "Map and world visibility follow JourneyMap's global waypoint settings"),
                        "show_online_map_markers", new PluginSettingState(false, false, ""),
                        "show_online_world_beacons", new PluginSettingState(false, false, ""),
                        "show_offline_map_markers", new PluginSettingState(false, false, ""),
                        "show_offline_world_beacons", new PluginSettingState(false, false, "")), List.of());
        FakeControl control = new FakeControl(new Config(), plugin);
        PluginManagerUiController manager = new PluginManagerUiSession(control);

        ConfigControlId online = ConfigControlId.setting(plugin.id(), "show_remote_players");
        ConfigControlId offline = ConfigControlId.setting(plugin.id(), "show_last_seen_players");
        ConfigControlId marker = ConfigControlId.setting(plugin.id(), "show_online_map_markers");
        PluginManagerView wide = manager.view(854, 480);
        assertEquals(List.of(online, offline), wide.detail().settings().stream()
                .map(PluginManagerView.SettingView::id).toList());
        manager.activate(marker);
        assertFalse(control.changedSettings.containsKey("show_online_map_markers"));

        PluginManagerView compactList = manager.view(640, 360);
        manager.activate(compactList.items().get(0).selectId());
        PluginManagerView compactDetail = manager.view(640, 360);
        assertEquals(List.of(online, offline), compactDetail.detail().settings().stream()
                .map(PluginManagerView.SettingView::id).toList());
    }

    @Test
    void battleMapSourceSelectorDoesNotInventUnregisteredFallbacks() {
        Config config = new Config();
        config.setBattleMapSourceId("missing-selected-source");
        ConfigUiSession session = new ConfigUiSession(new FakeControl(config));

        session.activate(ConfigPageId.NETWORK, ConfigControlId.BATTLE_MAP_SOURCE);

        assertEquals("missing-selected-source", config.getBattleMapSourceId());
    }

    @Test
    void pluginManagerUsesDenseWideAndCompactLayoutsWithToggleOnlyInTheList() {
        PluginSnapshot plugin = pluginWithSettings("custom.dense");
        PluginManagerUiController manager = new PluginManagerUiSession(
                new FakeControl(new Config(), plugin));

        PluginManagerView wide = manager.view(854, 480);
        assertFalse(wide.compact());
        assertFalse(wide.compactDetail());
        assertTrue(wide.listBounds().x() + wide.listBounds().width() <= wide.detailBounds().x());
        assertTrue(wide.detailBounds().x() + wide.detailBounds().width()
                <= wide.frame().x() + wide.frame().width());
        assertEquals(1, wide.items().size());
        assertTrue(wide.items().get(0).toggleVisible());
        assertTrue(wide.detail().actions().stream()
                .noneMatch(action -> "toggle".equals(action.id().pluginAction())));
        List<ConfigControlId> actionIds = new ArrayList<>(List.of(
                wide.installedTab().id(), wide.disabledTab().id(),
                wide.rescanAction().id(), wide.closeAction().id()));
        wide.items().forEach(item -> {
            actionIds.add(item.selectId());
            if (item.toggleId() != null) actionIds.add(item.toggleId());
        });
        wide.detail().settings().forEach(setting -> actionIds.add(setting.id()));
        wide.detail().actions().forEach(action -> actionIds.add(action.id()));
        assertEquals(actionIds.size(), Set.copyOf(actionIds).size());

        PluginManagerView compactList = manager.view(640, 360);
        assertTrue(compactList.compact());
        assertFalse(compactList.compactDetail());
        manager.activate(compactList.items().get(0).selectId());
        PluginManagerView compactDetail = manager.view(640, 360);
        assertTrue(compactDetail.compactDetail());
        assertNotNull(compactDetail.detail().compactBack());
        int firstLineY = compactDetail.detail().lines().get(0).bounds().y();
        manager.scrollDetail(24);
        int scrolledLineY = manager.view(640, 360).detail().lines().get(0).bounds().y();
        assertTrue(scrolledLineY < firstLineY,
                () -> "detail did not scroll: " + firstLineY + " -> " + scrolledLineY);
        manager.activate(ConfigControlId.PLUGIN_COMPACT_BACK);
        assertFalse(manager.view(640, 360).compactDetail());
    }

    @Test
    void relationPluginShowsCurrentRecognitionWithoutOverwritingManualSettings() {
        String pluginId = IntegrationIds.PLUGIN_TAB_LABEL_RELATIONS;
        List<PluginManifest.SettingDefinition> definitions = List.of(
                new PluginManifest.SettingDefinition(
                        "relation_source_mode", "enum", "Relation source mode", "automatic_only",
                        null, null,
                        List.of("automatic_only", "manual_only", "manual_first", "automatic_first"), false),
                new PluginManifest.SettingDefinition(
                        "friendly_tags", "string", "Friendly tags", "饶州",
                        null, null, List.of(), false),
                new PluginManifest.SettingDefinition(
                        "enemy_tags", "string", "Enemy tags", "星辉",
                        null, null, List.of(), false));
        PluginSnapshot plugin = new PluginSnapshot(
                pluginId, "Tab Label Player Relations", "1.4.0", true, true, true,
                PluginRuntimeStatus.ACTIVE, "", null,
                Map.of("relation_source_mode", "automatic_only",
                        "friendly_tags", "饶州", "enemy_tags", "星辉"),
                definitions, List.of(new IntegrationCapability(
                        IntegrationIds.TAB_LABEL_RELATIONS, "player-relation",
                        IntegrationSupportStatus.AVAILABLE, "", pluginId,
                        IntegrationImplementationSource.LUA, PluginRuntimeStatus.ACTIVE)))
                .withRuntimeState(List.of(
                        new PluginRuntimeState("source.status", "导入状态", "已导入"),
                        new PluginRuntimeState("effective.friendly", "当前 Tab 友军", "2 人: Alice, Bob")));
        PluginManagerView managerView = new PluginManagerUiSession(
                new FakeControl(new Config(), plugin)).view(854, 480);
        assertEquals("screen.mc_teamviewer.integration_plugin.manager.runtime_state",
                managerView.detail().runtimeSectionTitle().translationKey());
        int firstRuntime = managerView.detail().runtimeLineStartIndex();
        assertEquals(1, firstRuntime);
        assertEquals("导入状态", managerView.detail().lines().get(firstRuntime).primary().literal());
        PluginManagerView.SettingView policy = managerView.detail().settings().stream()
                .filter(value -> "relation_source_mode".equals(value.id().settingKey()))
                .findFirst().orElseThrow();
        assertEquals(
                "screen.mc_teamviewer.integration_plugin.setting.tab_label_relation_source_mode.automatic_only",
                policy.valueLabel().translationKey());
    }

    @Test
    void pluginManagerWrapsDescriptionAndConfirmsRuntimeActionBeforeInvocation() {
        String pluginId = IntegrationIds.PLUGIN_TAB_LABEL_RELATIONS;
        String description = "默认仅采用 /town 或 /t 的自动识别结果；可在下方切换手动标签与自动识别结果的采用关系。";
        PluginSnapshot plugin = new PluginSnapshot(
                pluginId, "Tab Label Player Relations", "1.4.0", true, true, true,
                PluginRuntimeStatus.ACTIVE, "", null, Map.of(), List.of(), Map.of(),
                List.of(), false, description, List.of(),
                List.of(new PluginRuntimeAction(
                        "clear_automatic_relations", "清空自动识别", "只清空自动结果",
                        true, true, "确定清空当前自动识别结果吗？")));
        FakeControl control = new FakeControl(new Config(), plugin);
        PluginManagerUiController manager = new PluginManagerUiSession(control);

        PluginManagerView narrow = manager.view(320, 360);
        assertNotNull(narrow.detail().description());
        assertEquals("screen.mc_teamviewer.integration_plugin.tab_label_relations.description",
                narrow.detail().description().text().translationKey());
        assertTrue(narrow.detail().description().bounds().height() > 11,
                "narrow plugin details must reserve more than one line for the description");
        ConfigControlId clear = ConfigControlId.pluginRuntimeAction(
                pluginId, "clear_automatic_relations");
        assertTrue(narrow.detail().actions().stream().anyMatch(action -> clear.equals(action.id())));

        manager.activate(clear);
        assertEquals(0, control.invokedPluginActionCount);
        assertEquals(PluginManagerView.DialogKind.RUNTIME_ACTION_CONFIRM,
                manager.view(704, 360).dialog().kind());
        manager.activate(ConfigControlId.PLUGIN_RUNTIME_CONFIRM);
        assertEquals(1, control.invokedPluginActionCount);
        assertEquals(pluginId, control.invokedPluginId);
        assertEquals("clear_automatic_relations", control.invokedPluginActionId);

    }

    @Test
    void pluginManagerCommitsTextOnNavigationAndAppliesBooleanAndEnumImmediately() {
        PluginSnapshot plugin = pluginWithSettings("custom.settings");
        FakeControl control = new FakeControl(new Config(), plugin);
        PluginManagerUiController manager = new PluginManagerUiSession(control);
        manager.view(854, 480);

        manager.activate(ConfigControlId.setting(plugin.id(), "visible"));
        manager.activate(ConfigControlId.setting(plugin.id(), "mode"));
        assertEquals(false, control.changedSettings.get("visible"));
        assertEquals("compact", control.changedSettings.get("mode"));

        manager.setText(ConfigControlId.setting(plugin.id(), "limit"), "27");
        manager.setText(ConfigControlId.setting(plugin.id(), "color"), "#42c6d7");
        manager.activate(ConfigControlId.PLUGIN_TAB_DISABLED);
        assertEquals("27", control.changedSettings.get("limit"));
        assertEquals("#42c6d7", control.changedSettings.get("color"));
    }

    @Test
    void pluginManagerKeepsSelectionAndPerTabScrollThenFallsBackAfterRescan() {
        List<PluginSnapshot> plugins = new ArrayList<>();
        for (int index = 0; index < 10; index++) plugins.add(plugin("custom." + index, false));
        FakeControl control = new FakeControl(new Config(), plugins);
        PluginManagerUiController manager = new PluginManagerUiSession(control);
        manager.view(854, 260);
        manager.moveSelection(9);
        PluginManagerView scrolled = manager.view(854, 260);
        assertEquals("custom.9", scrolled.items().stream().filter(PluginManagerView.ListItemView::selected)
                .findFirst().orElseThrow().selectId().pluginId());
        String firstVisible = scrolled.items().get(0).selectId().pluginId();

        manager.activate(ConfigControlId.PLUGIN_TAB_DISABLED);
        manager.activate(ConfigControlId.PLUGIN_TAB_INSTALLED);
        PluginManagerView restored = manager.view(854, 260);
        assertEquals(firstVisible, restored.items().get(0).selectId().pluginId());
        assertEquals("custom.9", restored.items().stream().filter(PluginManagerView.ListItemView::selected)
                .findFirst().orElseThrow().selectId().pluginId());

        control.plugins = new ArrayList<>(plugins.subList(0, 9));
        manager.activate(ConfigControlId.PLUGIN_RESCAN);
        PluginManagerView fallback = manager.view(854, 260);
        assertEquals("custom.0", fallback.items().get(0).selectId().pluginId());
        assertTrue(fallback.items().get(0).selected());
    }

    @Test
    void pluginManagerMapsEveryRuntimeStateAndUsesModalDisabledPluginFlow() {
        for (PluginRuntimeStatus status : PluginRuntimeStatus.values()) {
            PluginSnapshot plugin = new PluginSnapshot(
                    "state." + status.name().toLowerCase(), status.name(), "1", false, true, true,
                    status, "", tempDir.resolve(status.name()), Map.of(), List.of(), List.of(), false);
            PluginManagerView view = new PluginManagerUiSession(
                    new FakeControl(new Config(), plugin)).view(854, 480);
            PluginManagerView.ListItemView item = view.items().get(0);
            boolean available = status == PluginRuntimeStatus.ACTIVE
                    || status == PluginRuntimeStatus.DISABLED;
            assertEquals(available, item.toggleActive(), status.name());
            assertEquals(expectedStatusColor(status), item.statusColor(), status.name());
        }

        PluginSnapshot custom = plugin("custom.modal", false);
        DisabledPluginSnapshot disabled = new DisabledPluginSnapshot(
                "custom.modal-disabled", custom.id(), custom.name(), custom.version(),
                "custom.modal", false, 1L, tempDir.resolve("disabled/custom.modal"));
        FakeControl control = new FakeControl(new Config(), custom);
        control.disabledPlugins = List.of(disabled);
        PluginManagerUiController manager = new PluginManagerUiSession(control);
        manager.activate(ConfigControlId.PLUGIN_TAB_DISABLED);
        PluginManagerView disabledView = manager.view(854, 480);
        assertEquals(3, disabledView.detail().actions().size());
        manager.activate(ConfigControlId.plugin(disabled.storageId(), "disabled-open-dir"));
        assertEquals(disabled.storagePath(), control.openedPath);
        manager.activate(ConfigControlId.plugin(disabled.storageId(), "disabled-restore"));
        assertEquals(disabled.storageId(), control.restoredStorageId);
        manager.activate(ConfigControlId.plugin(disabled.storageId(), "disabled-delete"));
        PluginManagerView confirm = manager.view(854, 480);
        assertEquals(PluginManagerView.DialogKind.DELETE_CONFIRM, confirm.dialog().kind());
        manager.activate(ConfigControlId.plugin(disabled.storageId(), "disabled-delete-confirm"));
        assertEquals(disabled.storageId(), control.deletedStorageId);
        assertNotNull(manager.view(854, 480).message());
    }

    @Test
    void pluginManagerShowsCopyGuideAndNonBlockingFailureFeedback() {
        PluginSnapshot builtIn = plugin("teamviewer.copy-test", true);
        FakeControl successControl = new FakeControl(new Config(), builtIn);
        successControl.copyPath = tempDir.resolve("plugins/copy-test.custom");
        PluginManagerUiController success = new PluginManagerUiSession(successControl);
        success.view(854, 480);
        success.activate(ConfigControlId.plugin(builtIn.id(), "copy"));
        assertEquals(PluginManagerView.DialogKind.COPY_GUIDE,
                success.view(854, 480).dialog().kind());

        FakeControl failureControl = new FakeControl(new Config(), builtIn);
        PluginManagerUiController failure = new PluginManagerUiSession(failureControl);
        failure.view(854, 480);
        failure.activate(ConfigControlId.plugin(builtIn.id(), "copy"));
        PluginManagerView failed = failure.view(854, 480);
        assertNotNull(failed.message());
        assertEquals(null, failed.dialog());
    }

    private PluginSnapshot pluginWithSettings(String id) {
        List<PluginManifest.SettingDefinition> settings = List.of(
                new PluginManifest.SettingDefinition(
                        "visible", "boolean", "Visible", true, null, null, List.of(), false),
                new PluginManifest.SettingDefinition(
                        "mode", "enum", "Mode", "full", null, null,
                        List.of("full", "compact"), false),
                new PluginManifest.SettingDefinition(
                        "limit", "integer", "Limit", 10, 0.0, 100.0, List.of(), false),
                new PluginManifest.SettingDefinition(
                        "color", "color", "Color", "#ffffff", null, null, List.of(), false));
        return new PluginSnapshot(
                id, "A very long plugin name used to exercise clipping", "2026.7.29",
                false, true, true, PluginRuntimeStatus.ACTIVE, "diagnostic detail",
                tempDir.resolve(id), Map.of(
                "visible", true, "mode", "full", "limit", 10, "color", "#ffffff"),
                settings, List.of(
                new IntegrationCapability(
                        id + ".map", "battle-map-source", IntegrationSupportStatus.AVAILABLE, "",
                        id, IntegrationImplementationSource.LUA, PluginRuntimeStatus.ACTIVE),
                new IntegrationCapability(
                        id + ".waypoint", "shared-waypoint-consumer",
                        IntegrationSupportStatus.MOD_NOT_INSTALLED, "Missing optional dependency",
                        id, IntegrationImplementationSource.LUA, PluginRuntimeStatus.ACTIVE)));
    }

    private static int expectedStatusColor(PluginRuntimeStatus status) {
        return switch (status) {
            case ACTIVE -> PluginManagerView.SUCCESS;
            case DISABLED -> PluginManagerView.MUTED;
            case PENDING_RESTART -> PluginManagerView.WARNING;
            case INCOMPATIBLE, LOAD_FAILED, SUSPENDED -> PluginManagerView.ERROR;
        };
    }

    private PluginSnapshot plugin(String id, boolean builtIn) {
        return new PluginSnapshot(id, id + " name", "1.0.0", builtIn, true, true,
                PluginRuntimeStatus.ACTIVE, "", builtIn ? null : tempDir.resolve(id), Map.of(),
                List.of(), List.of(new IntegrationCapability(
                id + "-map", "battle-map-source", IntegrationSupportStatus.AVAILABLE, "",
                id, IntegrationImplementationSource.JAVA_NATIVE, PluginRuntimeStatus.ACTIVE)));
    }

    private ConfigUiSession session(Config config) {
        return new ConfigUiSession(new FakeControl(config));
    }

    private static void assertContains(ConfigUiSession session, ConfigPageId page, ConfigControlId... expected) {
        Set<ConfigControlId> ids = session.page(page, 854, 480).controls().stream()
                .map(ConfigControlView::id)
                .collect(Collectors.toSet());
        for (ConfigControlId id : expected) {
            assertTrue(ids.contains(id), () -> page + " missing " + id);
        }
    }

    private static final class FakeControl implements ClientControlGateway {
        private final Config config;
        private final NetworkManager network = new NetworkManager(
                new HashMap<UUID, RemotePlayerInfo>(), runtime(), (uri, options, listener) -> null);
        private List<PluginSnapshot> plugins;
        private final Map<String, Object> changedSettings = new HashMap<>();
        private List<DisabledPluginSnapshot> disabledPlugins = List.of();
        private Path copyPath;
        private Path openedPath;
        private String uninstalledPluginId;
        private String restoredStorageId;
        private String deletedStorageId;
        private String invokedPluginId;
        private String invokedPluginActionId;
        private int invokedPluginActionCount;
        private boolean enabled;
        private Boolean lastEnabledValue;

        private FakeControl(Config config) {
            this(config, List.of());
        }

        private FakeControl(Config config, PluginSnapshot plugin) {
            this(config, plugin == null ? List.of() : List.of(plugin));
        }

        private FakeControl(Config config, List<PluginSnapshot> plugins) {
            this.config = config;
            this.plugins = new ArrayList<>(plugins);
        }

        @Override
        public Config getConfig() { return config; }
        @Override
        public NetworkManager getNetworkManager() { return network; }
        @Override
        public boolean isEnabled() { return enabled; }
        @Override
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        @Override
        public void reconnect() { }
        @Override
        public void showActionBar(String message) { }
        @Override
        public List<PluginSnapshot> getIntegrationPlugins() { return List.copyOf(plugins); }
        @Override
        public PluginSnapshot getIntegrationPlugin(String pluginId) {
            return plugins.stream().filter(value -> value.id().equals(pluginId)).findFirst().orElse(null);
        }
        @Override
        public boolean setIntegrationPluginEnabled(String pluginId, boolean enabled) {
            lastEnabledValue = enabled;
            return true;
        }
        @Override
        public boolean setIntegrationPluginSetting(String pluginId, String key, Object value) {
            changedSettings.put(key, value);
            return true;
        }
        @Override
        public boolean invokeIntegrationPluginAction(String pluginId, String actionId) {
            invokedPluginId = pluginId;
            invokedPluginActionId = actionId;
            invokedPluginActionCount++;
            return true;
        }
        @Override
        public PluginFileOperationResult copyBuiltinIntegrationPluginResult(String pluginId) {
            return copyPath == null
                    ? new PluginFileOperationResult(PluginFileOperationResult.Code.IO_ERROR, null, "copy failed")
                    : PluginFileOperationResult.success(copyPath);
        }
        @Override
        public List<DisabledPluginSnapshot> getDisabledIntegrationPlugins() { return disabledPlugins; }
        @Override
        public DisabledPluginSnapshot getDisabledIntegrationPlugin(String storageId) {
            return disabledPlugins.stream().filter(value -> value.storageId().equals(storageId))
                    .findFirst().orElse(null);
        }
        @Override
        public PluginFileOperationResult uninstallIntegrationPlugin(String pluginId) {
            uninstalledPluginId = pluginId;
            return PluginFileOperationResult.success(tempDirPath(pluginId));
        }
        @Override
        public PluginFileOperationResult restoreIntegrationPlugin(String storageId) {
            restoredStorageId = storageId;
            return PluginFileOperationResult.success(tempDirPath(storageId));
        }
        @Override
        public PluginFileOperationResult deleteDisabledIntegrationPlugin(String storageId) {
            deletedStorageId = storageId;
            return PluginFileOperationResult.success(tempDirPath(storageId));
        }
        @Override
        public boolean openIntegrationPluginDirectory(Path path) {
            openedPath = path;
            return true;
        }

        private static Path tempDirPath(String value) {
            return Path.of("build", "plugin-test", value);
        }

        private static RuntimeGateway runtime() {
            return new RuntimeGateway() {
                @Override
                public String getCurrentDimensionId() { return "minecraft:overworld"; }
                @Override
                public UUID getLocalPlayerId() { return null; }
                @Override
                public String getClientProgramVersion() { return "test"; }
                @Override
                public String getClientProtocolVersion() { return "0.6.2"; }
                @Override
                public String getClientMinCompatibleProtocolVersion() { return "0.6.1"; }
                @Override
                public String getServerProtocolFallbackVersion() { return "0.0.0"; }
                @Override
                public String getProgramVersionUnknown() { return "unknown"; }
                @Override
                public Path getLogsDirectory() { return Path.of("build", "test-logs"); }
            };
        }
    }
}
