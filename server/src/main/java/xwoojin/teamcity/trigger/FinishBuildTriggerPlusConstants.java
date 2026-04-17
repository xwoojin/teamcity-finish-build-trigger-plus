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

    /** "true" = queue the triggered build on the same agent that ran the watched build. */
    public static final String TRIGGER_ON_SAME_AGENT = "triggerOnSameAgent";

    /** Non-negative integer; minutes to wait after watched build finishes before triggering. */
    public static final String WAIT_MINUTES = "waitMinutes";

    /** Positive integer; watched time frame in hours — max span between finish times (AND mode). Default 3. */
    public static final String AND_TIME_WINDOW_HOURS = "andTimeWindowHours";

    // ── CustomDataStorage keys ───────────────────────────────────────────────

    /** Build-id of the last watched build we already triggered for (single mode). */
    public static final String LAST_BUILD_ID_KEY = "lastTriggeredBuildId";

    /** Prefix for per-build-type storage keys in multi-build mode. */
    public static final String MULTI_LAST_BUILD_ID_PREFIX = "multi.lastBuildId.";

    // ── Controller / resource path ───────────────────────────────────────────

    /** URL suffix served by FinishBuildTriggerPlusController. */
    public static final String EDIT_PARAMS_HTML = "editFinishBuildTriggerPlus.html";

    private FinishBuildTriggerPlusConstants() {}
}
