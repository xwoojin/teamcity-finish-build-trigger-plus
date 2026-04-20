package xwoojin.teamcity.trigger;

import jetbrains.buildServer.buildTriggers.BuildTriggerException;
import jetbrains.buildServer.buildTriggers.PolledBuildTrigger;
import jetbrains.buildServer.buildTriggers.PolledTriggerContext;
import org.jetbrains.annotations.NotNull;

/**
 * No-op {@link PolledBuildTrigger}.
 *
 * <p>TeamCity's {@link jetbrains.buildServer.buildTriggers.BuildTriggerService}
 * requires a non-null triggering policy. The real work — reacting to watched
 * builds finishing and queuing the downstream build — now lives in
 * {@link FinishBuildTriggerPlusBuildListener}, which subscribes directly to
 * {@code buildFinished} events for ~0-second response time (matching the
 * built-in Finish Build Trigger).
 *
 * <p>This class is kept purely as the registered policy object. The poll
 * interval is set to 1 hour to minimize any scheduler overhead; the callback
 * itself does nothing.
 */
public class FinishBuildTriggerPlusTriggeringPolicy extends PolledBuildTrigger {

    @Override
    public void triggerBuild(@NotNull PolledTriggerContext context)
            throws BuildTriggerException {
        // No-op: all triggering is handled by FinishBuildTriggerPlusBuildListener.
    }

    @Override
    public int getPollInterval(@NotNull PolledTriggerContext context) {
        return 3600; // 1 hour — the scheduler still ticks but does nothing.
    }
}
