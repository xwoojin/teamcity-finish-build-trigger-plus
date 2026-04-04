package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Serves the JSP settings page for Finish Build Trigger (Plus).
 *
 * <p>Registered at {@code /plugins/finish-build-trigger-plus/editFinishBuildTriggerPlus.html}
 * and forwards to the bundled JSP.
 */
public class FinishBuildTriggerPlusController extends BaseController {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusController.class.getName());

    /** Resolved path to the JSP inside the plugin's resources directory. */
    private final String myJspPath;

    public FinishBuildTriggerPlusController(@NotNull SBuildServer server,
                                            @NotNull WebControllerManager controllerManager,
                                            @NotNull PluginDescriptor pluginDescriptor) {
        super(server);

        // The JSP is extracted to: <webapps>/ROOT/plugins/<pluginName>/editFinishBuildTriggerPlus.jsp
        myJspPath = pluginDescriptor.getPluginResourcesPath("editFinishBuildTriggerPlus.jsp");

        // Register the .html endpoint that TeamCity calls when opening the trigger settings form
        String controllerPath = pluginDescriptor.getPluginResourcesPath(
                FinishBuildTriggerPlusConstants.EDIT_PARAMS_HTML);
        controllerManager.registerController(controllerPath, this);

        LOG.info("[FinishBuildTriggerPlus] Controller registered at: " + controllerPath
                + "  JSP path: " + myJspPath);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response) throws Exception {
        return new ModelAndView(myJspPath);
    }
}
