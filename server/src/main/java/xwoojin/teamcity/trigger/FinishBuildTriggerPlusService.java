package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.BuildTriggerDescriptor;
import jetbrains.buildServer.buildTriggers.BuildTriggerService;
import jetbrains.buildServer.buildTriggers.BuildTriggeringPolicy;
import jetbrains.buildServer.serverSide.BuildAgentManager;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers "Finish Build Trigger (Plus)" as a TeamCity build trigger service.
 *
 * <p>Features beyond the standard Finish Build Trigger:
 * <ul>
 *   <li>Trigger build on <em>all</em> enabled compatible agents (like Schedule Trigger)</li>
 *   <li>Optional delay: wait N minutes after the watched build finishes before triggering</li>
 * </ul>
 */
public class FinishBuildTriggerPlusService extends BuildTriggerService {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusService.class.getName());

    private final ProjectManager myProjectManager;
    private final BuildAgentManager myBuildAgentManager;
    private final PluginDescriptor myPluginDescriptor;

    public FinishBuildTriggerPlusService(@NotNull ProjectManager projectManager,
                                         @NotNull BuildAgentManager buildAgentManager,
                                         @NotNull PluginDescriptor pluginDescriptor) {
        this.myProjectManager   = projectManager;
        this.myBuildAgentManager = buildAgentManager;
        this.myPluginDescriptor  = pluginDescriptor;
        LOG.info("[FinishBuildTriggerPlus] Service instantiated.");
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @NotNull
    @Override
    public String getName() {
        return FinishBuildTriggerPlusConstants.TRIGGER_NAME;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Finish Build Trigger (Plus)";
    }

    // ── Human-readable description shown in the build-type trigger list ───────

    @NotNull
    @Override
    public String describeTrigger(@NotNull BuildTriggerDescriptor trigger) {
        Map<String, String> props = trigger.getProperties();

        StringBuilder sb = new StringBuilder();

        // Watched build config(s) — may be comma-separated
        String btIdRaw = props.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        List<String> validIds = new ArrayList<>();
        if (btIdRaw != null && !btIdRaw.isEmpty()) {
            for (String id : btIdRaw.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) validIds.add(trimmed);
            }
        }

        boolean successfulOnly = "true".equals(
                props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY));
        String qualifier = successfulOnly ? "successful " : "";

        if (validIds.isEmpty()) {
            sb.append("Wait for a ").append(qualifier).append("build (no build configured)");
        } else if (validIds.size() == 1) {
            // Single-build — mirror standard "Finish Build Trigger" phrasing
            SBuildType bt = findBuildType(validIds.get(0));
            String name = bt != null ? bt.getFullName() : validIds.get(0);
            sb.append("Wait for a ").append(qualifier).append("build in: ").append(name);
        } else {
            // Multi-build — one full-path build per line
            sb.append("Wait for all ").append(qualifier).append("builds below to finish:");
            for (String id : validIds) {
                SBuildType bt = findBuildType(id);
                String name = bt != null ? bt.getFullName() : id;
                sb.append("\n  → ").append(name);
            }

            // AND time window (only relevant for multi-build)
            String windowHours = props.get(FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS);
            String windowStr = (windowHours != null && !windowHours.trim().isEmpty())
                    ? windowHours.trim() : "3";
            sb.append("\n(all must finish within ").append(windowStr).append("h)");
        }

        // Wait delay
        String waitMins = props.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES);
        if (waitMins != null && !waitMins.trim().isEmpty() && !"0".equals(waitMins.trim())) {
            sb.append("\nWait ").append(waitMins.trim()).append(" min before triggering");
        }

        // All-agents flag
        if ("true".equals(props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS))) {
            sb.append("\nTrigger on all compatible agents");
        }

        // Same-agent flag
        if ("true".equals(props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_SAME_AGENT))) {
            sb.append("\nTrigger on same agent as watched build");
        }

        return sb.toString();
    }

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

    // ── Core policy ──────────────────────────────────────────────────────────

    @NotNull
    @Override
    public BuildTriggeringPolicy getBuildTriggeringPolicy() {
        return new FinishBuildTriggerPlusTriggeringPolicy(myProjectManager, myBuildAgentManager);
    }

    // ── Settings validation ───────────────────────────────────────────────────

    @Override
    public PropertiesProcessor getTriggerPropertiesProcessor() {
        return properties -> {
            List<InvalidProperty> errors = new ArrayList<>();

            String btIdRaw = properties.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
            if (btIdRaw == null || btIdRaw.trim().isEmpty()) {
                errors.add(new InvalidProperty(
                        FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                        "Build configuration must be specified"));
            } else {
                String[] ids = btIdRaw.split(",");
                Set<String> seen = new HashSet<>();
                List<String> resolvedIds = new ArrayList<>();
                for (String id : ids) {
                    String trimmed = id.trim();
                    if (trimmed.isEmpty()) continue;

                    // Resolve to current external ID (handles renamed builds)
                    SBuildType bt = findBuildType(trimmed);
                    if (bt == null) {
                        errors.add(new InvalidProperty(
                                FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                                "Build configuration not found: " + trimmed));
                        continue;
                    }

                    String currentId = bt.getExternalId();
                    if (!seen.add(currentId)) {
                        errors.add(new InvalidProperty(
                                FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                                "Duplicate build configuration: " + currentId));
                    } else {
                        resolvedIds.add(currentId);
                    }
                }

                // Refresh stored IDs to current external IDs
                if (errors.isEmpty() && !resolvedIds.isEmpty()) {
                    properties.put(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                            String.join(",", resolvedIds));
                }
            }

            String waitMins = properties.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES);
            if (waitMins != null && !waitMins.trim().isEmpty()) {
                try {
                    int val = Integer.parseInt(waitMins.trim());
                    if (val < 0) {
                        errors.add(new InvalidProperty(
                                FinishBuildTriggerPlusConstants.WAIT_MINUTES,
                                "Wait time must be 0 or greater"));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new InvalidProperty(
                            FinishBuildTriggerPlusConstants.WAIT_MINUTES,
                            "Wait time must be a valid integer"));
                }
            }

            String windowHours = properties.get(FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS);
            if (windowHours != null && !windowHours.trim().isEmpty()) {
                try {
                    int val = Integer.parseInt(windowHours.trim());
                    if (val <= 0) {
                        errors.add(new InvalidProperty(
                                FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS,
                                "AND time window must be a positive integer"));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new InvalidProperty(
                            FinishBuildTriggerPlusConstants.AND_TIME_WINDOW_HOURS,
                            "AND time window must be a valid integer"));
                }
            }

            return errors;
        };
    }

    // ── Edit URL ─────────────────────────────────────────────────────────────

    @Override
    public String getEditParametersUrl() {
        return myPluginDescriptor.getPluginResourcesPath(
                FinishBuildTriggerPlusConstants.EDIT_PARAMS_HTML);
    }

    @Override
    public boolean isMultipleTriggersPerBuildTypeAllowed() {
        return true;
    }

    @Override
    public boolean supportsBuildCustomization() {
        return true;
    }
}
