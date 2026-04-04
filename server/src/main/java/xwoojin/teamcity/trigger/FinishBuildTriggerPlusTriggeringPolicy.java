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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polling-based triggering policy for Finish Build Trigger (Plus).
 *
 * <p>Polled every {@link #getPollInterval} seconds (default 60 s).  On each poll:
 * <ol>
 *   <li>Looks up the latest finished build of the watched configuration.</li>
 *   <li>Skips if it is the same build we already triggered for.</li>
 *   <li>If {@code waitMinutes > 0}, waits until {@code finishTime + waitMinutes} before acting.</li>
 *   <li>Creates a {@link BuildCustomizer} carrying the watched build's triggering user
 *       and custom metadata parameters, then enqueues via {@link BuildPromotion#addToQueue}.</li>
 *   <li>Persists the triggered build-id so we don't fire twice for the same build.</li>
 * </ol>
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

    private final ProjectManager myProjectManager;
    private final BuildAgentManager myBuildAgentManager;

    public FinishBuildTriggerPlusTriggeringPolicy(@NotNull ProjectManager projectManager,
                                                  @NotNull BuildAgentManager buildAgentManager) {
        this.myProjectManager    = projectManager;
        this.myBuildAgentManager = buildAgentManager;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Called once when the trigger is first activated on a build configuration.
     * We snapshot the current last-finished build so we don't fire immediately.
     */
    @Override
    public void triggerActivated(@NotNull PolledTriggerContext context)
            throws BuildTriggerException {

        String btId = watchedBuildTypeId(context);
        if (btId == null) return;

        SBuildType watchedBt = myProjectManager.findBuildTypeByExternalId(btId);
        if (watchedBt == null) return;

        SFinishedBuild lastBuild = watchedBt.getLastChangesFinished();
        if (lastBuild != null) {
            String id = String.valueOf(lastBuild.getBuildId());
            context.getCustomDataStorage().putValue(
                    FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY, id);
            LOG.info("[FinishBuildTriggerPlus] triggerActivated: saved initial build id="
                    + id + " for watched=" + btId);
        } else {
            LOG.info("[FinishBuildTriggerPlus] triggerActivated: no finished builds yet"
                    + " for watched=" + btId);
        }
    }

    // ── Main poll loop ───────────────────────────────────────────────────────

    @Override
    public void triggerBuild(@NotNull PolledTriggerContext context)
            throws BuildTriggerException {

        Map<String, String> props = context.getTriggerDescriptor().getProperties();

        // ── resolve watched build configuration ──────────────────────────────
        String btId = props.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        if (btId == null || btId.trim().isEmpty()) {
            LOG.warn("[FinishBuildTriggerPlus] No watched build type configured; skipping.");
            return;
        }

        SBuildType watchedBt = myProjectManager.findBuildTypeByExternalId(btId.trim());
        if (watchedBt == null) {
            LOG.warn("[FinishBuildTriggerPlus] Watched build type not found: " + btId);
            return;
        }

        // ── read options ─────────────────────────────────────────────────────
        boolean successfulOnly = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY));
        boolean allAgents = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS));
        int waitMinutes = parseWaitMinutes(
                props.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES));

        // ── find latest relevant finished build ───────────────────────────────
        SFinishedBuild latestBuild = successfulOnly
                ? watchedBt.getLastChangesSuccessfullyFinished()
                : watchedBt.getLastChangesFinished();

        if (latestBuild == null) {
            LOG.debug("[FinishBuildTriggerPlus] No finished builds yet for: " + btId);
            return;
        }

        String latestBuildId = String.valueOf(latestBuild.getBuildId());

        // ── skip if we already triggered for this build ──────────────────────
        CustomDataStorage storage = context.getCustomDataStorage();
        String lastTriggeredId = storage.getValue(
                FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY);

        if (latestBuildId.equals(lastTriggeredId)) {
            LOG.debug("[FinishBuildTriggerPlus] Already triggered for build id="
                    + latestBuildId + "; skipping.");
            return;
        }

        // ── honour wait delay ────────────────────────────────────────────────
        if (waitMinutes > 0) {
            Date finishDate = latestBuild.getFinishDate();
            if (finishDate == null) {
                LOG.debug("[FinishBuildTriggerPlus] Build " + latestBuildId
                        + " has no finish date yet; skipping.");
                return;
            }
            long triggerAt   = finishDate.getTime() + ((long) waitMinutes * 60_000L);
            long remaining   = triggerAt - System.currentTimeMillis();
            if (remaining > 0) {
                LOG.debug("[FinishBuildTriggerPlus] Delay in effect: "
                        + (remaining / 1_000) + "s remaining"
                        + " (watchedBuild=" + latestBuildId + ")");
                return;
            }
        }

        // ── resolve the watched build's triggering user ──────────────────────
        SUser watchedBuildUser = resolveWatchedBuildUser(latestBuild);
        if (watchedBuildUser != null) {
            LOG.info("[FinishBuildTriggerPlus] Watched build #" + latestBuildId
                    + " was triggered by user: " + watchedBuildUser.getUsername());
        } else {
            LOG.info("[FinishBuildTriggerPlus] Watched build #" + latestBuildId
                    + " was not triggered by a user (trigger/schedule/etc.)");
        }

        // ── build custom metadata parameters ─────────────────────────────────
        Map<String, String> customParams = new HashMap<>();
        customParams.put(PARAM_WATCHED_BUILD_TYPE_ID,    latestBuild.getBuildTypeExternalId());
        customParams.put(PARAM_WATCHED_BUILD_CONF_NAME,  latestBuild.getBuildTypeName());
        customParams.put(PARAM_WATCHED_BUILD_NUMBER,     latestBuild.getBuildNumber());
        customParams.put(PARAM_WATCHED_BUILD_ID,         latestBuildId);

        // Resolve project full path (e.g. "Release / Client / KR / Android")
        SBuildType watchedBuildType = latestBuild.getBuildType();
        if (watchedBuildType != null && watchedBuildType.getProject() != null) {
            customParams.put(PARAM_WATCHED_PROJECT_NAME, watchedBuildType.getProject().getFullName());
        }

        // ── fire! ────────────────────────────────────────────────────────────
        String comment = "Triggered by Finish Build Trigger (Plus)"
                + " [watched build #" + latestBuildId + "]";

        if (allAgents) {
            triggerOnAllAgents(context, watchedBuildUser, customParams, comment);
        } else {
            BuildCustomizer customizer = context.createBuildCustomizer(watchedBuildUser);
            customizer.addParametersIfAbsent(customParams);
            BuildPromotion promotion = customizer.createPromotion();
            promotion.addToQueue(comment);
            LOG.info("[FinishBuildTriggerPlus] Queued: "
                    + context.getBuildType().getFullName()
                    + " (watchedBuild=" + latestBuildId + ")");
        }

        // ── persist the triggered build id ───────────────────────────────────
        storage.putValue(FinishBuildTriggerPlusConstants.LAST_BUILD_ID_KEY, latestBuildId);
        storage.flush();
        LOG.info("[FinishBuildTriggerPlus] State saved: lastTriggeredBuildId=" + latestBuildId);
    }

    // ── All-agents helper ────────────────────────────────────────────────────

    /**
     * Queues one build per enabled, authorized, compatible agent — identical to the
     * "Trigger build on all enabled and compatible agents" behaviour of Schedule Trigger.
     * Each queued build carries the watched-build user context and custom parameters.
     */
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

    // ── Poll interval ────────────────────────────────────────────────────────

    @Override
    public int getPollInterval(@NotNull PolledTriggerContext context) {
        return 60; // seconds
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the user who triggered the watched build.
     * Returns {@code null} if the build was triggered by another trigger, schedule, etc.
     */
    @Nullable
    private SUser resolveWatchedBuildUser(@NotNull SFinishedBuild watchedBuild) {
        TriggeredBy triggeredBy = watchedBuild.getTriggeredBy();
        if (triggeredBy == null) return null;
        if (!triggeredBy.isTriggeredByUser()) return null;
        return triggeredBy.getUser();
    }

    private String watchedBuildTypeId(@NotNull PolledTriggerContext context) {
        String id = context.getTriggerDescriptor().getProperties()
                .get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        return (id != null && !id.trim().isEmpty()) ? id.trim() : null;
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
