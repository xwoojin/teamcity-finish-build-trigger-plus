package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.BuildTriggerDescriptor;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.ProjectsModelListener;
import jetbrains.buildServer.serverSide.ProjectsModelListenerAdapter;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for build configuration deletions and automatically removes the deleted
 * build configuration from any Finish Build Trigger (Plus) watched lists.
 *
 * If a trigger's watched list becomes empty as a result, the trigger is left intact
 * (with an empty watched list) so the user can notice and reconfigure — it will not
 * fire since {@link FinishBuildTriggerPlusService}'s PropertiesProcessor requires a
 * non-empty selection on save.
 */
public class FinishBuildTriggerPlusCleanupListener extends ProjectsModelListenerAdapter {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusCleanupListener.class.getName());

    private final ProjectManager projectManager;

    public FinishBuildTriggerPlusCleanupListener(@NotNull EventDispatcher<ProjectsModelListener> events,
                                                 @NotNull ProjectManager projectManager) {
        this.projectManager = projectManager;
        events.addListener(this);
        LOG.info("[FinishBuildTriggerPlus] Cleanup listener registered.");
    }

    @Override
    public void buildTypeUnregistered(@NotNull SBuildType removed) {
        final String removedExternalId = removed.getExternalId();
        final String removedInternalId = removed.getBuildTypeId();

        int cleanedTriggers = 0;
        for (SBuildType bt : projectManager.getAllBuildTypes()) {
            if (bt.getBuildTypeId().equals(removedInternalId)) continue; // itself

            List<TriggerUpdate> updates = new ArrayList<>();

            for (BuildTriggerDescriptor trigger : bt.getBuildTriggersCollection()) {
                if (!FinishBuildTriggerPlusConstants.TRIGGER_NAME.equals(trigger.getTriggerName())) {
                    continue;
                }

                Map<String, String> props = trigger.getProperties();
                String watchedRaw = props.get(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID);
                if (watchedRaw == null || watchedRaw.isEmpty()) continue;

                List<String> remaining = new ArrayList<>();
                boolean changed = false;
                for (String id : watchedRaw.split(",")) {
                    String trimmed = id.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.equals(removedExternalId) || trimmed.equals(removedInternalId)) {
                        changed = true;
                        continue;
                    }
                    remaining.add(trimmed);
                }

                if (!changed) continue;

                Map<String, String> newProps = new HashMap<>(props);
                newProps.put(FinishBuildTriggerPlusConstants.WATCHED_BUILD_TYPE_ID,
                        String.join(",", remaining));
                updates.add(new TriggerUpdate(trigger, newProps, remaining.isEmpty()));
            }

            if (updates.isEmpty()) continue;

            try {
                for (TriggerUpdate u : updates) {
                    bt.updateBuildTrigger(u.trigger.getId(), u.trigger.getTriggerName(), u.newProps);
                    cleanedTriggers++;
                    LOG.info("[FinishBuildTriggerPlus] Auto-removed deleted watched build '"
                            + removedExternalId + "' from trigger on '" + bt.getExternalId() + "'"
                            + (u.nowEmpty ? " (watched list is now empty)" : ""));
                }
                bt.persist();
            } catch (Exception e) {
                LOG.warnAndDebugDetails("[FinishBuildTriggerPlus] Failed to persist cleanup on '"
                        + bt.getExternalId() + "'", e);
            }
        }

        if (cleanedTriggers > 0) {
            LOG.info("[FinishBuildTriggerPlus] Cleanup complete — updated " + cleanedTriggers
                    + " trigger(s) after deletion of '" + removedExternalId + "'.");
        }
    }

    private static final class TriggerUpdate {
        final BuildTriggerDescriptor trigger;
        final Map<String, String> newProps;
        final boolean nowEmpty;

        TriggerUpdate(BuildTriggerDescriptor t, Map<String, String> p, boolean empty) {
            this.trigger = t;
            this.newProps = p;
            this.nowEmpty = empty;
        }
    }
}
