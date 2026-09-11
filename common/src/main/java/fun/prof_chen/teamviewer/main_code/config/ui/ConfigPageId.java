package fun.prof_chen.teamviewer.main_code.config.ui;

public enum ConfigPageId {
    ROOT("screen.mc_teamviewer.config.title"),
    DISPLAY("screen.mc_teamviewer.display_config.title"),
    NETWORK("screen.mc_teamviewer.network_config.title"),
    ENTITY_UPLOAD("screen.mc_teamviewer.entity_upload.title"),
    ENTITY_FILTERS("screen.mc_teamviewer.entity_upload.filters_title"),
    ENTITY_FILTER_EDIT("screen.mc_teamviewer.entity_upload.filter_edit_title"),
    COLOR("screen.mc_teamviewer.color_config.title"),
    WAYPOINT("screen.mc_teamviewer.waypoint_config.title"),
    WAYPOINT_SHAPE("screen.mc_teamviewer.waypoint_shape_config.title"),
    PACKET_CAPTURE("screen.mc_teamviewer.packet_capture.title"),
    DEBUG_SETTINGS("screen.mc_teamviewer.debug_settings.title");

    private final String titleKey;

    ConfigPageId(String titleKey) {
        this.titleKey = titleKey;
    }

    public String titleKey() {
        return titleKey;
    }
}
