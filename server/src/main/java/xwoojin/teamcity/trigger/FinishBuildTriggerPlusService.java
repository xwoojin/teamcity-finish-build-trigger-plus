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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        // Watched build config
        String btId = props.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
        if (btId != null && !btId.isEmpty()) {
            SBuildType bt = myProjectManager.findBuildTypeByExternalId(btId);
            sb.append(bt != null ? bt.getFullName() : btId);
        } else {
            sb.append("(no build configured)");
        }
        sb.append(" finishes");

        // Successful-only flag
        if ("true".equals(props.get(FinishBuildTriggerPlusConstants.AFTER_SUCCESSFUL_BUILD_ONLY))) {
            sb.append(" successfully");
        }

        // Wait delay
        String waitMins = props.get(FinishBuildTriggerPlusConstants.WAIT_MINUTES);
        if (waitMins != null && !waitMins.trim().isEmpty() && !"0".equals(waitMins.trim())) {
            sb.append(", wait ").append(waitMins.trim()).append(" min");
        }

        // All-agents flag
        if ("true".equals(props.get(FinishBuildTriggerPlusConstants.TRIGGER_ON_ALL_AGENTS))) {
            sb.append(", on all compatible agents");
        }

        return sb.toString();
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

            String btId = properties.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
            if (btId == null || btId.trim().isEmpty()) {
                errors.add(new InvalidProperty(
                        FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                        "Build configuration must be specified"));
            } else if (myProjectManager.findBuildTypeByExternalId(btId.trim()) == null) {
                errors.add(new InvalidProperty(
                        FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                        "Build configuration not found: " + btId));
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
