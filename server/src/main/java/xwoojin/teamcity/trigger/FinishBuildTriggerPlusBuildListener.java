package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.BuildTriggerDescriptor;
import jetbrains.buildServer.serverSide.AgentCompatibility;
import jetbrains.buildServer.serverSide.AgentDescription;
import jetbrains.buildServer.serverSide.BuildAgentManager;
import jetbrains.buildServer.serverSide.BuildCustomizer;
import jetbrains.buildServer.serverSide.BuildCustomizerFactory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Event-based triggering engine for Finish Build Trigger (Plus).
 *
 * <p>Replaces the old {@link jetbrains.buildServer.buildTriggers.PolledBuildTrigger}
 * loop: instead of polling every 60 s, subscribes to TeamCity's {@code buildFinished}
 * events for ~0-second reaction time matching the built-in Finish Build Trigger.
 *
 * <h3>Single mode</h3>
 * Each matching {@code buildFinished} event fires the target build immediately
 * (respecting {@code afterSuccessfulBuildOnly} and {@code waitMinutes}).
 *
 * <h3>Multi mode (AND)</h3>
 * Per-trigger in-memory state records the most recently-finished build for each
 * watched build configuration. As soon as every watched configuration has a
 * recorded finish (and the time window is satisfied), the trigger fires and
 * the state is cleared — requiring each watched build to produce a <em>new</em>
 * finish before the next fire.
 */
public class FinishBuildTriggerPlusBuildListener extends BuildServerAdapter {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusBuildListener.class.getName());

    // ── Injected parameter keys ──────────────────────────────────────────────
    /**
     * Always injected — value is the number of watched builds (1 in single mode, N in multi).
     * The {@code env.} prefix makes TeamCity expose this as an environment variable
     * ({@code TRIGGERED_BUILDCOUNT}) in addition to a configuration parameter.
     */
    private static final String PARAM_WATCHED_BUILD_COUNT     = "env.triggered.BuildCount";
    /**
     * Prefix for per-watched-build parameters. Always indexed (1-based) regardless
     * of whether the trigger is in single or multi mode — callers can read
     * {@code env.triggered.1.BuildStatus} uniformly. The {@code env.} prefix makes
     * them available as environment variables on the build agent.
     */
    private static final String PARAM_PREFIX = "env.triggered.";

    private static final String STATUS_SUCCESS  = "success";
    private static final String STATUS_FAILURE  = "failure";
    private static final String STATUS_CANCELED = "canceled";

    private final ProjectManager myProjectManager;
    private final BuildAgentManager myBuildAgentManager;
    private final BuildCustomizerFactory myBuildCustomizerFactory;

    /**
     * Shared scheduler for {@code waitMinutes} delays. Daemon-threaded so it
     * never blocks JVM shutdown. Single-threaded — the workload is tiny.
     */
    private final ScheduledExecutorService myScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FBTPlus-EventDelay");
                t.setDaemon(true);
                return t;
            });

    /**
     * Multi-mode AND state.
     * <p>Outer key: {@code targetBtInternalId + "::" + triggerId}
     * <p>Inner map: {@code watchedExternalId → last finished SBuild} — the
     * most recent qualifying finish we've observed for that watched build.
     * <p>Inner map access is serialized via {@code synchronized(innerMap)}.
     */
    private final Map<String, Map<String, SBuild>> myMultiState = new ConcurrentHashMap<>();

    public FinishBuildTriggerPlusBuildListener(@NotNull SBuildServer buildServer,
                                               @NotNull ProjectManager projectManager,
                                               @NotNull BuildAgentManager buildAgentManager,
                                               @NotNull BuildCustomizerFactory buildCustomizerFactory) {
        this.myProjectManager        = projectManager;
        this.myBuildAgentManager     = buildAgentManager;
        this.myBuildCustomizerFactory = buildCustomizerFactory;
        buildServer.addListener(this);
        LOG.info("[FinishBuildTriggerPlus] Event-based build listener registered.");
    }

    // ── Build finished event ─────────────────────────────────────────────────

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        try {
            handleBuildFinished(build);
        } catch (Throwable t) {
            LOG.warnAndDebugDetails(
                    "[FinishBuildTriggerPlus] Unhandled error reacting to buildFinished #"
                            + build.getBuildId(), t);
        }
    }

    private void handleBuildFinished(@NotNull SBuild finished) {
        String finishedExtId = finished.getBuildTypeExternalId();
        if (finishedExtId == null || finishedExtId.isEmpty()) return;

        // Walk every build configuration, finding FBT+ triggers whose watched
        // list references the just-finished build's external (or internal) id.
        for (SBuildType target : myProjectManager.getAllBuildTypes()) {
            Collection<BuildTriggerDescriptor> triggers;
            try {
                triggers = target.getBuildTriggersCollection();
            } catch (Exception e) {
                continue;
            }
            if (triggers.isEmpty()) continue;

            for (BuildTriggerDescriptor td : triggers) {
                if (!FinishBuildTriggerPlusConstants.TRIGGER_NAME.equals(td.getTriggerName())) continue;

                List<String> watchedIds = parseWatchedIds(td);
                if (watchedIds.isEmpty()) continue;

                // Match against finished build's external id (primary) and
                // internal id (fallback in case the trigger stored an internal id).
                String finishedInternalId = finished.getBuildTypeId();
                boolean matches = watchedIds.contains(finishedExtId)
                        || (finishedInternalId != null && watchedIds.contains(finishedInternalId));
                if (!matches) continue;

                // Self-reference guard
                if (finishedExtId.equals(target.getExternalId())) {
                    LOG.warn("[FinishBuildTriggerPlus] Ignoring self-referencing watched build: "
                            + finishedExtId);
                    continue;
                }

                Map<String, String> props = td.getProperties();
                boolean successfulOnly = "true".equals(
                        props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY));
                if (successfulOnly && !isSuccessfulFinish(finished)) {
                    LOG.debug("[FinishBuildTriggerPlus] Skipping non-successful build #"
                            + finished.getBuildId() + " (afterSuccessfulBuildOnly is on).");
                    continue;
                }

                if (watchedIds.size() == 1) {
                    scheduleFireSingle(target, td, finished);
                } else {
                    handleMultiModeEvent(target, td, watchedIds, finished);
                }
            }
        }
    }

    // ── Single mode ──────────────────────────────────────────────────────────

    private void scheduleFireSingle(@NotNull SBuildType target,
                                    @NotNull BuildTriggerDescriptor td,
                                    @NotNull SBuild watched) {
        int waitMinutes = parseWaitMinutes(
                td.getProperties().get(FinishBuildTriggerPlusConstants.WAIT_MINUTES));
        if (waitMinutes <= 0) {
            fireSingle(target, td, watched);
            return;
        }

        long delayMs = delayRemainingMs(watched, waitMinutes);
        if (delayMs <= 0) {
            fireSingle(target, td, watched);
        } else {
            LOG.info("[FinishBuildTriggerPlus] Delaying single-mode trigger on "
                    + target.getExternalId() + " by " + (delayMs / 1000) + "s");
            myScheduler.schedule(() -> {
                try {
                    fireSingle(target, td, watched);
                } catch (Throwable t) {
                    LOG.warnAndDebugDetails(
                            "[FinishBuildTriggerPlus] Delayed single-mode fire failed", t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void fireSingle(@NotNull SBuildType target,
                            @NotNull BuildTriggerDescriptor td,
                            @NotNull SBuild watched) {
        // Reuse the multi-mode indexed format so downstream build scripts can
        // read `teamcity.build.triggered.1.*` + `BuildCount` uniformly in both
        // single and multi modes.
        LinkedHashMap<String, SBuild> oneEntry = new LinkedHashMap<>();
        String key = watched.getBuildTypeExternalId();
        if (key == null) key = watched.getBuildTypeId();
        oneEntry.put(key, watched);
        fireMulti(target, td, oneEntry);
    }

    // ── Multi mode (AND) ─────────────────────────────────────────────────────

    private void handleMultiModeEvent(@NotNull SBuildType target,
                                      @NotNull BuildTriggerDescriptor td,
                                      @NotNull List<String> watchedIds,
                                      @NotNull SBuild finished) {
        String finishedExtId = finished.getBuildTypeExternalId();
        String finishedInternalId = finished.getBuildTypeId();

        // Normalize the matching watched id (prefer ext id)
        String matchedWatchedId;
        if (finishedExtId != null && watchedIds.contains(finishedExtId)) {
            matchedWatchedId = finishedExtId;
        } else if (finishedInternalId != null && watchedIds.contains(finishedInternalId)) {
            matchedWatchedId = finishedInternalId;
        } else {
            return; // defensive
        }

        String stateKey = target.getBuildTypeId() + "::" + td.getId();
        Map<String, SBuild> state = myMultiState.computeIfAbsent(
                stateKey, k -> new LinkedHashMap<>());

        LinkedHashMap<String, SBuild> snapshot; // of newBuilds in watched-order, once AND is satisfied
        synchronized (state) {
            state.put(matchedWatchedId, finished);

            // Check AND condition: every watched id has an entry
            boolean allPresent = true;
            for (String id : watchedIds) {
                if (!state.containsKey(id)) { allPresent = false; break; }
            }
            if (!allPresent) {
                LOG.debug("[FinishBuildTriggerPlus] Multi-mode: "
                        + state.size() + "/" + watchedIds.size()
                        + " watched builds finished for target " + target.getExternalId());
                return;
            }

            // Check time window constraint
            int windowHours = parseAndTimeWindowHours(
                    td.getProperties().get(FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS));
            if (windowHours > 0) {
                Date earliest = null, latest = null;
                for (SBuild b : state.values()) {
                    Date fd = b.getFinishDate();
                    if (fd == null) continue;
                    if (earliest == null || fd.before(earliest)) earliest = fd;
                    if (latest == null || fd.after(latest)) latest = fd;
                }
                if (earliest != null && latest != null) {
                    long spanMs = latest.getTime() - earliest.getTime();
                    long windowMs = (long) windowHours * 3_600_000L;
                    if (spanMs > windowMs) {
                        LOG.info("[FinishBuildTriggerPlus] Multi-mode: span "
                                + (spanMs / 3_600_000L) + "h exceeds window "
                                + windowHours + "h — waiting for fresher finishes.");
                        return;
                    }
                }
            }

            // Snapshot in the order the user declared the watched list
            snapshot = new LinkedHashMap<>();
            for (String id : watchedIds) snapshot.put(id, state.get(id));

            // Clear so next fire requires every watched build to re-finish
            state.clear();
        }

        // Fire (outside the synchronized block — addToQueue is thread-safe)
        scheduleFireMulti(target, td, snapshot);
    }

    private void scheduleFireMulti(@NotNull SBuildType target,
                                   @NotNull BuildTriggerDescriptor td,
                                   @NotNull LinkedHashMap<String, SBuild> newBuilds) {
        int waitMinutes = parseWaitMinutes(
                td.getProperties().get(FinishBuildTriggerPlusConstants.WAIT_MINUTES));
        if (waitMinutes <= 0) {
            fireMulti(target, td, newBuilds);
            return;
        }

        // Delay measured from the latest finish among the watched builds
        Date latestFinish = null;
        for (SBuild b : newBuilds.values()) {
            Date fd = b.getFinishDate();
            if (fd != null && (latestFinish == null || fd.after(latestFinish))) {
                latestFinish = fd;
            }
        }
        long triggerAt = (latestFinish != null ? latestFinish.getTime() : System.currentTimeMillis())
                + ((long) waitMinutes * 60_000L);
        long delayMs = triggerAt - System.currentTimeMillis();

        if (delayMs <= 0) {
            fireMulti(target, td, newBuilds);
        } else {
            LOG.info("[FinishBuildTriggerPlus] Delaying multi-mode trigger on "
                    + target.getExternalId() + " by " + (delayMs / 1000) + "s");
            myScheduler.schedule(() -> {
                try {
                    fireMulti(target, td, newBuilds);
                } catch (Throwable t) {
                    LOG.warnAndDebugDetails(
                            "[FinishBuildTriggerPlus] Delayed multi-mode fire failed", t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void fireMulti(@NotNull SBuildType target,
                           @NotNull BuildTriggerDescriptor td,
                           @NotNull LinkedHashMap<String, SBuild> newBuilds) {
        Map<String, String> props = td.getProperties();
        boolean allAgents = "true".equals(props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS));
        boolean sameAgent = "true".equals(props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_SAME_AGENT));

        Map<String, String> customParams = new HashMap<>();
        customParams.put(PARAM_WATCHED_BUILD_COUNT, String.valueOf(newBuilds.size()));

        int idx = 1;
        for (SBuild build : newBuilds.values()) {
            String prefix = PARAM_PREFIX + idx + ".";
            customParams.put(prefix + "BuildTypeId",   build.getBuildTypeExternalId());
            customParams.put(prefix + "BuildConfName", build.getBuildTypeName());
            customParams.put(prefix + "BuildNumber",   build.getBuildNumber());
            customParams.put(prefix + "BuildId",       String.valueOf(build.getBuildId()));
            customParams.put(prefix + "BuildStatus",   resolveStatus(build));

            SBuildType bt = build.getBuildType();
            if (bt != null && bt.getProject() != null) {
                customParams.put(prefix + "ProjectConfName", bt.getProject().getFullName());
            }
            idx++;
        }

        // Use the user from the most recently finished watched build
        SUser user = null;
        SBuild mostRecent = null;
        Date latest = null;
        for (SBuild b : newBuilds.values()) {
            Date fd = b.getFinishDate();
            if (fd != null && (latest == null || fd.after(latest))) {
                latest = fd;
                mostRecent = b;
                SUser u = resolveWatchedBuildUser(b);
                if (u != null) user = u;
            }
        }

        StringBuilder ids = new StringBuilder();
        for (SBuild b : newBuilds.values()) {
            if (ids.length() > 0) ids.append(", ");
            ids.append("#").append(b.getBuildId());
        }
        String comment = "Triggered by Finish Build Trigger (Plus)"
                + " [multi-build: " + ids + "]";

        dispatchQueue(target, user, customParams, comment, mostRecent, allAgents, sameAgent);
    }

    // ── Queue dispatch (all-agents / same-agent / default) ───────────────────

    private void dispatchQueue(@NotNull SBuildType target,
                               @Nullable SUser user,
                               @NotNull Map<String, String> customParams,
                               @NotNull String comment,
                               @Nullable SBuild watchedForAgent,
                               boolean allAgents,
                               boolean sameAgent) {
        if (allAgents) {
            queueOnAllAgents(target, user, customParams, comment);
        } else if (sameAgent) {
            queueOnSameAgent(target, user, customParams, comment, watchedForAgent);
        } else {
            BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            LOG.info("[FinishBuildTriggerPlus] Queued: " + target.getFullName());
        }
    }

    private void queueOnAllAgents(@NotNull SBuildType target,
                                  @Nullable SUser user,
                                  @NotNull Map<String, String> customParams,
                                  @NotNull String comment) {
        List<AgentCompatibility> compatibilities = target.getAgentCompatibilities();
        if (compatibilities.isEmpty()) {
            LOG.warn("[FinishBuildTriggerPlus] getAgentCompatibilities() empty for "
                    + target.getFullName() + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
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

            BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
            customizer.addParametersIfAbsent(customParams);
            BuildPromotion promotion = customizer.createPromotion();
            promotion.addToQueue(agent, comment);
            LOG.info("[FinishBuildTriggerPlus] Queued on agent '"
                    + agent.getName() + "' for " + target.getFullName());
            queued++;
        }

        if (queued == 0) {
            LOG.warn("[FinishBuildTriggerPlus] No eligible agents for "
                    + target.getFullName() + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
        }
    }

    private void queueOnSameAgent(@NotNull SBuildType target,
                                  @Nullable SUser user,
                                  @NotNull Map<String, String> customParams,
                                  @NotNull String comment,
                                  @Nullable SBuild watched) {
        SBuildAgent watchedAgent = (watched != null) ? watched.getAgent() : null;
        if (watchedAgent == null
                || !watchedAgent.isRegistered()
                || !watchedAgent.isEnabled()
                || !watchedAgent.isAuthorized()) {
            LOG.warn("[FinishBuildTriggerPlus] Same-agent mode: agent unavailable for "
                    + target.getFullName() + "; falling back to unassigned queue entry.");
            BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
            customizer.addParametersIfAbsent(customParams);
            customizer.createPromotion().addToQueue(comment);
            return;
        }

        BuildCustomizer customizer = myBuildCustomizerFactory.createBuildCustomizer(target, user);
        customizer.addParametersIfAbsent(customParams);
        BuildPromotion promotion = customizer.createPromotion();
        promotion.addToQueue(watchedAgent, comment);
        LOG.info("[FinishBuildTriggerPlus] Same-agent mode: queued on '"
                + watchedAgent.getName() + "' for " + target.getFullName());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isSuccessfulFinish(@NotNull SBuild build) {
        if (build.getCanceledInfo() != null) return false;
        return build.getBuildStatus().isSuccessful();
    }

    @NotNull
    private static String resolveStatus(@NotNull SBuild build) {
        if (build.getCanceledInfo() != null) return STATUS_CANCELED;
        return build.getBuildStatus().isSuccessful() ? STATUS_SUCCESS : STATUS_FAILURE;
    }

    @Nullable
    private static SUser resolveWatchedBuildUser(@NotNull SBuild watched) {
        TriggeredBy tb = watched.getTriggeredBy();
        if (tb == null || !tb.isTriggeredByUser()) return null;
        return tb.getUser();
    }

    @NotNull
    private static List<String> parseWatchedIds(@NotNull BuildTriggerDescriptor td) {
        String raw = td.getProperties().get(
                FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int parseWaitMinutes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseAndTimeWindowHours(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 3;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : 3;
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private static long delayRemainingMs(@NotNull SBuild watched, int waitMinutes) {
        Date finishDate = watched.getFinishDate();
        long base = (finishDate != null) ? finishDate.getTime() : System.currentTimeMillis();
        long triggerAt = base + ((long) waitMinutes * 60_000L);
        return triggerAt - System.currentTimeMillis();
    }
}
