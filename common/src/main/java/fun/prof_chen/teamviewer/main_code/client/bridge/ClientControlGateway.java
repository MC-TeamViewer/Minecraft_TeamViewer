package fun.prof_chen.teamviewer.main_code.client.bridge;

import fun.prof_chen.teamviewer.main_code.bridge.NetworkManager;
import fun.prof_chen.teamviewer.main_code.config.Config;
import fun.prof_chen.teamviewer.main_code.client.sdk.IntegrationCapability;
import fun.prof_chen.teamviewer.main_code.plugin.PluginSnapshot;
import fun.prof_chen.teamviewer.main_code.plugin.DisabledPluginSnapshot;
import fun.prof_chen.teamviewer.main_code.plugin.PluginFileOperationResult;
import fun.prof_chen.teamviewer.main_code.client.model.PlayerRelationView;
import fun.prof_chen.teamviewer.api.PlayerInteractionState;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Version-neutral control surface used by Minecraft-specific screens.
 */
public interface ClientControlGateway {
    Config getConfig();

    NetworkManager getNetworkManager();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    void reconnect();

    /** Show a short platform-native action-bar notification. */
    void showActionBar(String message);

    default PlayerRelationView resolvePlayerRelation(UUID playerId) {
        return null;
    }

    default PlayerInteractionState playerInteraction(UUID playerId) {
        return PlayerInteractionState.unresolved();
    }

    default List<PluginSnapshot> getIntegrationPlugins() { return List.of(); }
    default List<IntegrationCapability> getIntegrationCapabilities() {
        return getIntegrationPlugins().stream().flatMap(plugin -> plugin.capabilities().stream()).toList();
    }
    default PluginSnapshot getIntegrationPlugin(String pluginId) { return null; }
    default boolean setIntegrationPluginEnabled(String pluginId, boolean enabled) { return false; }
    default boolean setIntegrationPluginSetting(String pluginId, String key, Object value) { return false; }
    default boolean invokeIntegrationPluginAction(String pluginId, String actionId) { return false; }
    default boolean rescanIntegrationPlugins() { return false; }
    default Path copyBuiltinIntegrationPlugin(String pluginId) { return null; }
    default PluginFileOperationResult copyBuiltinIntegrationPluginResult(String pluginId) {
        Path path = copyBuiltinIntegrationPlugin(pluginId);
        return path == null
                ? new PluginFileOperationResult(PluginFileOperationResult.Code.IO_ERROR, null, "Copy failed")
                : PluginFileOperationResult.success(path);
    }
    default List<DisabledPluginSnapshot> getDisabledIntegrationPlugins() { return List.of(); }
    default DisabledPluginSnapshot getDisabledIntegrationPlugin(String storageId) { return null; }
    default PluginFileOperationResult uninstallIntegrationPlugin(String pluginId) {
        return new PluginFileOperationResult(PluginFileOperationResult.Code.NOT_FOUND, null, "Plugin manager unavailable");
    }
    default PluginFileOperationResult restoreIntegrationPlugin(String storageId) {
        return new PluginFileOperationResult(PluginFileOperationResult.Code.NOT_FOUND, null, "Plugin manager unavailable");
    }
    default PluginFileOperationResult deleteDisabledIntegrationPlugin(String storageId) {
        return new PluginFileOperationResult(PluginFileOperationResult.Code.NOT_FOUND, null, "Plugin manager unavailable");
    }
    default boolean openIntegrationPluginDirectory(Path path) { return false; }

    default void disconnect() {
        // setEnabled(false) 内部已调用 networkManager.disconnect(),无需重复
        setEnabled(false);
    }
}
