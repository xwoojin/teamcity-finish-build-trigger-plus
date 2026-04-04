package xwoojin.teamcity.trigger;

/**
 * Property key constants for Finish Build Trigger (Plus).
 */
public final class FinishBuildTriggerPlusConstants {

    /** Trigger type ID registered with TeamCity. */
    public static final String TRIGGER_NAME = "FinishBuildTriggerPlus";

    // ── Trigger property keys ────────────────────────────────────────────────

    /** ExternalId of the build configuration to watch. */
    public static final String WATCHED_BUILD_TYPE_ID = "watchedBuildTypeId";

    /** "true" = only react to successful builds. */
    public static final String AFTER_SUCCESSFUL_BUILD_ONLY = "afterSuccessfulBuildOnly";

    /** "true" = queue one build per enabled compatible agent (mirrors Schedule Trigger). */
    public static final String TRIGGER_ON_ALL_AGENTS = "triggerBuildOnAllCompatibleAgents";

    /** Non-negative integer; minutes to wait after watched build finishes before triggering. */
    public static final String WAIT_MINUTES = "waitMinutes";

    // ── CustomDataStorage keys ───────────────────────────────────────────────

    /** Build-id of the last watched build we already triggered for. */
    public static final String LAST_BUILD_ID_KEY = "lastTriggeredBuildId";

    // ── Controller / resource path ───────────────────────────────────────────

    /** URL suffix served by FinishBuildTriggerPlusController. */
    public static final String EDIT_PARAMS_HTML = "editFinishBuildTriggerPlus.html";

    private FinishBuildTriggerPlusConstants() {}
}
