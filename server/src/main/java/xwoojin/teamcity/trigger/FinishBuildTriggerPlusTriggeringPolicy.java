package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.BuildTriggerException;
import jetbrains.buildServer.buildTriggers.PolledBuildTrigger;
import jetbrains.buildServer.buildTriggers.PolledTriggerContext;
import jetbrains.buildServer.serverSide.AgentCompatibility;
import jetbrains.buildServer.serverSide.AgentDescription;
import jetbrains.buildServer.serverSide.BuildAgentManager;
import jetbrains.buildServer.serverSide.BuildCustomizer;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polling-based triggering policy for Finish Build Trigger (Plus).
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Single mode</b> — watches one build configuration (backward-compatible).</li>
 *   <li><b>Multi mode</b> — watches multiple build configurations; triggers only when
 *       ALL have produced a new finished build since the last trigger (AND condition).</li>
 * </ul>
 *
 * <p>Polled every {@link #getPollInterval} seconds (default 60 s).
 */
public class FinishBuildTriggerPlusTriggeringPolicy extends PolledBuildTrigger {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusTriggeringPolicy.class.getName());

    /** Custom configuration parameter keys injected into the triggered build. */
    private static final String PARAM_WATCHED_BUILD_TYPE_ID    = "teamcity.build.triggered.BuildTypeId";
    private static final String PARAM_WATCHED_BUILD_CONF_NAME = "teamcity.build.triggered.BuildConfName";
    private static final String PARAM_WATCHED_PROJECT_NAME    = "teamcity.build.triggered.ProjectConfName";
    private static final String PARAM_WATCHED_BUILD_NUMBER    = "teamcity.build.triggered.BuildNumber";
    private static final String PARAM_WATCHED_BUILD_ID        = "teamcity.build.triggered.BuildId";
    private static final String PARAM_WATCHED_BUILD_COUNT     = "teamcity.build.triggered.BuildCount";

    private final ProjectManager myProjectManager;
    private final BuildAgentManager myBuildAgentManager;

    public FinishBuildTriggerPlusTriggeringPolicy(@NotNull ProjectManager projectManager,
                                                  @NotNull BuildAgentManager buildAgentManager) {
        this.myProjectManager    = projectManager;
        this.myBuildAgentManager = buildAgentManager;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void triggerActivated(@NotNull PolledTriggerContext context)
            throws BuildTriggerException {

        String[] btIds = parseWatchedBuildTypeIds(context);
        if (btIds == null || btIds.length == 0) return;

        CustomDataStorage storage = context.getCustomDataStorage();
        boolean multi = btIds.length > 1;

        for (String btId : btIds) {
            SBuildType watchedBt = findBuildType(btId);
            if (watchedBt == null) continue;

            SFinishedBuild lastBuild = watchedBt.getLastChangesFinished();
            if (lastBuild != null) {
                String id = String.valueOf(lastBuild.getBuildId());
                String key = multi
                        ? FinishBuildTriggerPlusConstants.MULTI_LAST_BUILD_ID_PREFIX + btId
                        : FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY;
                storage.putValue(key, id);
                LOG.info("[FinishBuildTriggerPlus] triggerActivated: saved initial build id="
                        + id + " for watched=" + btId);
            } else {
                LOG.info("[FinishBuildTriggerPlus] triggerActivated: no finished builds yet"
                        + " for watched=" + btId);
            }
        }
    }

    // ── Main poll loop ───────────────────────────────────────────────────────

    @Override
    public void triggerBuild(@NotNull PolledTriggerContext context)
            throws BuildTriggerException {

        String[] btIds = parseWatchedBuildTypeIds(context);
        if (btIds == null || btIds.length == 0) {
            LOG.warn("[FinishBuildTriggerPlus] No watched build type configured; skipping.");
            return;
        }

        // Self-reference guard: filter out the current build type
        String selfId = context.getBuildType().getExternalId();
        List<String> filtered = new ArrayList<>();
        for (String id : btIds) {
            if (id.equals(selfId)) {
                LOG.warn("[FinishBuildTriggerPlus] Ignoring self-referencing watched build: "
                        + id);
            } else {
                filtered.add(id);
            }
        }
        if (filtered.isEmpty()) {
            LOG.warn("[FinishBuildTriggerPlus] All watched builds are self-referencing; skipping.");
            return;
        }
        btIds = filtered.toArray(new String[0]);

        if (btIds.length == 1) {
            triggerBuildSingle(context, btIds[0]);
        } else {
            triggerBuildMulti(context, btIds);
        }
    }

    // ── Single-build mode (original behavior) ────────────────────────────────

    private void triggerBuildSingle(@NotNull PolledTriggerContext context,
                                    @NotNull String btId) {

        Map<String, String> props = context.getTriggerDescriptor().getProperties();

        SBuildType watchedBt = findBuildType(btId);
        if (watchedBt == null) {
            LOG.warn("[FinishBuildTriggerPlus] Watched build type not found: " + btId);
            return;
        }

        boolean successfulOnly = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY));
        boolean allAgents = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS));
        boolean sameAgent = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_SAME_AGENT));
        int waitMinutes = parseWaitMinutes(
                props.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES));

        SFinishedBuild latestBuild = successfulOnly
                ? watchedBt.getLastChangesSuccessfullyFinished()
                : watchedBt.getLastChangesFinished();

        if (latestBuild == null) {
            LOG.debug("[FinishBuildTriggerPlus] No finished builds yet for: " + btId);
            return;
        }

        String latestBuildId = String.valueOf(latestBuild.getBuildId());

        CustomDataStorage storage = context.getCustomDataStorage();
        String lastTriggeredId = storage.getValue(
                FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY);

        if (latestBuildId.equals(lastTriggeredId)) {
            LOG.debug("[FinishBuildTriggerPlus] Already triggered for build id="
                    + latestBuildId + "; skipping.");
            return;
        }

        if (waitMinutes > 0) {
            Date finishDate = latestBuild.getFinishDate();
            if (finishDate == null) return;
            long triggerAt = finishDate.getTime() + ((long) waitMinutes * 60_000L);
            if (System.currentTimeMillis() < triggerAt) {
                LOG.debug("[FinishBuildTriggerPlus] Delay in effect: "
                        + ((triggerAt - System.currentTimeMillis()) / 1_000) + "s remaining");
                return;
            }
        }

        SUser watchedBuildUser = resolveWatchedBuildUser(latestBuild);

        Map<String, String> customParams = new HashMap<>();
        customParams.put(PARAM_WATCHED_BUILD_TYPE_ID,    latestBuild.getBuildTypeExternalId());
        customParams.put(PARAM_WATCHED_BUILD_CONF_NAME,  latestBuild.getBuildTypeName());
        customParams.put(PARAM_WATCHED_BUILD_NUMBER,     latestBuild.getBuildNumber());
        customParams.put(PARAM_WATCHED_BUILD_ID,         latestBuildId);

        SBuildType watchedBuildType = latestBuild.getBuildType();
        if (watchedBuildType != null && watchedBuildType.getProject() != null) {
            customParams.put(PARAM_WATCHED_PROJECT_NAME, watchedBuildType.getProject().getFullName());
        }

        String comment = "Triggered by Finish Build Trigger (Plus)"
                + " [watched build #" + latestBuildId + "]";

        if (allAgents) {
            triggerOnAllAgents(context, watchedBuildUser, customParams, comment);
        } else if (sameAgent) {
            triggerOnSameAgent(context, watchedBuildUser, customParams, comment, latestBuild);
        } else {
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            LOG.info("[FinishBuildTriggerPlus] Queued: "
                    + context.getBuildType().getFullName()
                    + " (watchedBuild=" + latestBuildId + ")");
        }

        storage.putValue(FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY, latestBuildId);
        storage.flush();
        LOG.info("[FinishBuildTriggerPlus] State saved: lastTriggeredBuildId=" + latestBuildId);
    }

    // ── Multi-build mode (AND condition) ─────────────────────────────────────

    /**
     * Triggers only when ALL watched builds have produced a new finished build
     * since the last time we triggered.
     */
    private void triggerBuildMulti(@NotNull PolledTriggerContext context,
                                   @NotNull String[] btIds) {

        Map<String, String> props = context.getTriggerDescriptor().getProperties();

        boolean successfulOnly = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY));
        boolean allAgents = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS));
        boolean sameAgent = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_SAME_AGENT));
        int waitMinutes = parseWaitMinutes(
                props.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES));

        CustomDataStorage storage = context.getCustomDataStorage();

        // Check each watched build — all must have a NEW finished build
        Map<String, SFinishedBuild> newBuilds = new LinkedHashMap<>();

        for (String btId : btIds) {
            SBuildType watchedBt = findBuildType(btId);
            if (watchedBt == null) {
                LOG.warn("[FinishBuildTriggerPlus] Multi-mode: watched build not found: " + btId);
                return;
            }

            SFinishedBuild latest = successfulOnly
                    ? watchedBt.getLastChangesSuccessfullyFinished()
                    : watchedBt.getLastChangesFinished();

            if (latest == null) {
                LOG.debug("[FinishBuildTriggerPlus] Multi-mode: no finished builds for: " + btId);
                return;
            }

            String latestId = String.valueOf(latest.getBuildId());
            String storageKey = FinishBuildTriggerPlusConstants.MULTI_LAST_BUILD_ID_PREFIX + btId;
            String lastTriggeredId = storage.getValue(storageKey);

            if (latestId.equals(lastTriggeredId)) {
                LOG.debug("[FinishBuildTriggerPlus] Multi-mode: " + btId
                        + " has no new build since last trigger (id=" + latestId + ")");
                return;
            }

            newBuilds.put(btId, latest);
        }

        // ALL watched builds have new finished builds — check AND time window
        int windowHours = parseAndTimeWindowHours(
                props.get(FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS));
        if (windowHours > 0) {
            Date earliest = null;
            Date latest = null;
            for (SFinishedBuild build : newBuilds.values()) {
                Date fd = build.getFinishDate();
                if (fd == null) continue;
                if (earliest == null || fd.before(earliest)) earliest = fd;
                if (latest == null || fd.after(latest)) latest = fd;
            }
            if (earliest != null && latest != null) {
                long spanMs = latest.getTime() - earliest.getTime();
                long windowMs = (long) windowHours * 3_600_000L;
                if (spanMs > windowMs) {
                    LOG.info("[FinishBuildTriggerPlus] Multi-mode: time span between builds ("
                            + (spanMs / 3_600_000L) + "h) exceeds window ("
                            + windowHours + "h); skipping.");
                    return;
                }
            }
        }

        // Check wait delay
        if (waitMinutes > 0) {
            Date latestFinish = null;
            for (SFinishedBuild build : newBuilds.values()) {
                Date fd = build.getFinishDate();
                if (fd != null && (latestFinish == null || fd.after(latestFinish))) {
                    latestFinish = fd;
                }
            }
            if (latestFinish != null) {
                long triggerAt = latestFinish.getTime() + ((long) waitMinutes * 60_000L);
                long remaining = triggerAt - System.currentTimeMillis();
                if (remaining > 0) {
                    LOG.debug("[FinishBuildTriggerPlus] Multi-mode: delay in effect, "
                            + (remaining / 1_000) + "s remaining");
                    return;
                }
            }
        }

        // Build custom params with indexed values
        Map<String, String> customParams = new HashMap<>();
        customParams.put(PARAM_WATCHED_BUILD_COUNT, String.valueOf(newBuilds.size()));

        int idx = 1;
        for (Map.Entry<String, SFinishedBuild> entry : newBuilds.entrySet()) {
            SFinishedBuild build = entry.getValue();
            String prefix = "teamcity.build.triggered." + idx + ".";

            customParams.put(prefix + "BuildTypeId",   build.getBuildTypeExternalId());
            customParams.put(prefix + "BuildConfName", build.getBuildTypeName());
            customParams.put(prefix + "BuildNumber",   build.getBuildNumber());
            customParams.put(prefix + "BuildId",       String.valueOf(build.getBuildId()));

            SBuildType bt = build.getBuildType();
            if (bt != null && bt.getProject() != null) {
                customParams.put(prefix + "ProjectConfName", bt.getProject().getFullName());
            }
            idx++;
        }

        // Resolve user from the most recently finished build
        SUser watchedBuildUser = null;
        Date latestFinish = null;
        for (SFinishedBuild build : newBuilds.values()) {
            Date fd = build.getFinishDate();
            if (fd != null && (latestFinish == null || fd.after(latestFinish))) {
                latestFinish = fd;
                SUser u = resolveWatchedBuildUser(build);
                if (u != null) watchedBuildUser = u;
            }
        }

        // Fire!
        StringBuilder ids = new StringBuilder();
        for (SFinishedBuild b : newBuilds.values()) {
            if (ids.length() > 0) ids.append(", ");
            ids.append("#").append(b.getBuildId());
        }
        String comment = "Triggered by Finish Build Trigger (Plus)"
                + " [multi-build: " + ids + "]";

        if (allAgents) {
            triggerOnAllAgents(context, watchedBuildUser, customParams, comment);
        } else if (sameAgent) {
            // Use the agent from the most recently finished watched build
            SFinishedBuild mostRecent = null;
            for (SFinishedBuild build : newBuilds.values()) {
                Date fd = build.getFinishDate();
                if (fd != null && (mostRecent == null
                        || fd.after(mostRecent.getFinishDate()))) {
                    mostRecent = build;
                }
            }
            triggerOnSameAgent(context, watchedBuildUser, customParams, comment, mostRecent);
        } else {
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            LOG.info("[FinishBuildTriggerPlus] Multi-mode queued: "
                    + context.getBuildType().getFullName()
                    + " (watched builds: " + ids + ")");
        }

        // Persist all build IDs
        for (Map.Entry<String, SFinishedBuild> entry : newBuilds.entrySet()) {
            String storageKey = FinishBuildTriggerPlusConstants.MULTI_LAST_BUILD_ID_PREFIX
                    + entry.getKey();
            storage.putValue(storageKey, String.valueOf(entry.getValue().getBuildId()));
        }
        storage.flush();
        LOG.info("[FinishBuildTriggerPlus] Multi-mode state saved for "
                + newBuilds.size() + " watched builds");
    }

    // ── All-agents helper ────────────────────────────────────────────────────

    private void triggerOnAllAgents(@NotNull PolledTriggerContext context,
                                    @Nullable SUser watchedBuildUser,
                                    @NotNull Map<String, String> customParams,
                                    @NotNull String comment) {

        SBuildType buildType = context.getBuildType();
        List<AgentCompatibility> compatibilities = buildType.getAgentCompatibilities();

        if (compatibilities.isEmpty()) {
            LOG.warn("[FinishBuildTriggerPlus] getAgentCompatibilities() returned empty list"
                    + " for " + buildType.getFullName()
                    + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            return;
        }

        int queued = 0;
        for (AgentCompatibility compat : compatibilities) {
            if (!compat.isCompatible() || !compat.isActive()) continue;

            AgentDescription agentDesc = compat.getAgentDescription();
            if (!(agentDesc instanceof SBuildAgent)) continue;

            SBuildAgent agent = (SBuildAgent) agentDesc;
            if (!agent.isEnabled() || !agent.isAuthorized()) continue;

            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            BuildPromotion promotion = customizer.createPromotion();
            promotion.addToQueue(agent, comment);

            LOG.info("[FinishBuildTriggerPlus] Queued on agent '"
                    + agent.getName() + "' for " + buildType.getFullName());
            queued++;
        }

        if (queued == 0) {
            LOG.warn("[FinishBuildTriggerPlus] No enabled compatible agents found for "
                    + buildType.getFullName()
                    + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
        } else {
            LOG.info("[FinishBuildTriggerPlus] Queued " + queued
                    + " build(s) across all compatible agents for "
                    + buildType.getFullName());
        }
    }

    // ── Same-agent helper ─────────────────────────────────────────────────────

    /**
     * Queues the triggered build on the same agent that ran the watched build.
     * If the agent is unavailable (disconnected, disabled, or unauthorized),
     * falls back to an unassigned queue entry so the build is not silently lost.
     */
    private void triggerOnSameAgent(@NotNull PolledTriggerContext context,
                                    @Nullable SUser watchedBuildUser,
                                    @NotNull Map<String, String> customParams,
                                    @NotNull String comment,
                                    @Nullable SFinishedBuild watchedBuild) {

        SBuildType buildType = context.getBuildType();
        SBuildAgent watchedAgent = (watchedBuild != null) ? watchedBuild.getAgent() : null;

        if (watchedAgent == null) {
            LOG.warn("[FinishBuildTriggerPlus] Same-agent mode: watched build agent not found"
                    + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            return;
        }

        if (!watchedAgent.isRegistered() || !watchedAgent.isEnabled()
                || !watchedAgent.isAuthorized()) {
            LOG.warn("[FinishBuildTriggerPlus] Same-agent mode: agent '"
                    + watchedAgent.getName() + "' is not available (registered="
                    + watchedAgent.isRegistered() + ", enabled=" + watchedAgent.isEnabled()
                    + ", authorized=" + watchedAgent.isAuthorized()
                    + "); falling back to unassigned queue entry.");
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            return;
        }

        BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
        customizer.addParametersIfAbsent(customParams);
        BuildPromotion promotion = customizer.createPromotion();
        promotion.addToQueue(watchedAgent, comment);

        LOG.info("[FinishBuildTriggerPlus] Same-agent mode: queued on agent '"
                + watchedAgent.getName() + "' for " + buildType.getFullName());
    }

    // ── Poll interval ────────────────────────────────────────────────────────

    @Override
    public int getPollInterval(@NotNull PolledTriggerContext context) {
        return 60; // seconds
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Finds a build type by external ID first, then by internal ID as fallback.
     * This ensures triggers remain functional even after build configuration renames.
     */
    @Nullable
    private SBuildType findBuildType(@NotNull String id) {
        SBuildType bt = myProjectManager.findBuildTypeByExternalId(id);
        if (bt != null) return bt;
        return myProjectManager.findBuildTypeById(id);
    }

    @Nullable
    private SUser resolveWatchedBuildUser(@NotNull SFinishedBuild watchedBuild) {
        TriggeredBy triggeredBy = watchedBuild.getTriggeredBy();
        if (triggeredBy == null) return null;
        if (!triggeredBy.isTriggeredByUser()) return null;
        return triggeredBy.getUser();
    }

    /**
     * Parses the {@code watchedBuildTypeId} property, which may contain a single
     * external ID or a comma-separated list of external IDs.
     */
    @Nullable
    private String[] parseWatchedBuildTypeIds(@NotNull PolledTriggerContext context) {
        String raw = context.getTriggerDescriptor().getProperties()
                .get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        if (raw == null || raw.trim().isEmpty()) return null;

        String[] parts = raw.split(",");
        List<String> ids = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        return ids.isEmpty() ? null : ids.toArray(new String[0]);
    }

    private int parseAndTimeWindowHours(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 3; // default 3 hours
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : 3;
        } catch (NumberFormatException e) {
            LOG.warn("[FinishBuildTriggerPlus] Invalid andTimeWindowHours value: '"
                    + raw + "'; using default 3.");
            return 3;
        }
    }

    private int parseWaitMinutes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            LOG.warn("[FinishBuildTriggerPlus] Invalid waitMinutes value: '" + raw
                    + "'; treating as 0.");
            return 0;
        }
    }
}
