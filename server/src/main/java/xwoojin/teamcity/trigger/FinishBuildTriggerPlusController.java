package xwoojin.teamcity.trigger;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the JSP settings page for Finish Build Trigger (Plus).
 *
 * <p>Resolves the current build type's external ID from request context
 * and passes it to the JSP so the UI can prevent self-referencing triggers.
 */
public class FinishBuildTriggerPlusController extends BaseController {

    private static final Logger LOG = Logger.getInstance(
            FinishBuildTriggerPlusController.class.getName());

    private static final Pattern URL_ID_PATTERN = Pattern.compile("[?&]id=([^&#]+)");

    private final String myJspPath;
    private final ProjectManager myProjectManager;

    public FinishBuildTriggerPlusController(@NotNull SBuildServer server,
                                            @NotNull WebControllerManager controllerManager,
                                            @NotNull PluginDescriptor pluginDescriptor,
                                            @NotNull ProjectManager projectManager) {
        super(server);
        myProjectManager = projectManager;
        myJspPath = pluginDescriptor.getPluginResourcesPath("editFinishBuildTriggerPlus.jsp");

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
        ModelAndView mv = new ModelAndView(myJspPath);

        String externalId = resolveBuildTypeExternalId(request);
        if (externalId != null) {
            mv.getModel().put("currentBuildTypeExternalId", externalId);
            LOG.debug("[FinishBuildTriggerPlus] Resolved current build type: " + externalId);
        } else {
            LOG.debug("[FinishBuildTriggerPlus] Could not resolve current build type from request");
        }

        return mv;
    }

    // ── Resolve current build type external ID from request context ──────────

    @Nullable
    private String resolveBuildTypeExternalId(@NotNull HttpServletRequest request) {
        // 1) Direct request parameter: ?id=...
        String externalId = tryResolve(request.getParameter("id"));
        if (externalId != null) return externalId;

        // 2) buildTypeId parameter
        externalId = tryResolve(request.getParameter("buildTypeId"));
        if (externalId != null) return externalId;

        // 3) Referer header (parent page URL contains ?id=ExternalId)
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                Matcher m = URL_ID_PATTERN.matcher(referer);
                if (m.find()) {
                    externalId = tryResolve(URLDecoder.decode(m.group(1), "UTF-8"));
                    if (externalId != null) return externalId;
                }
            } catch (Exception e) {
                LOG.debug("[FinishBuildTriggerPlus] Failed to parse Referer: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Tries the given string as external ID first, then as internal ID.
     * Returns the external ID if found, null otherwise.
     */
    @Nullable
    private String tryResolve(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) return null;
        id = id.trim();

        // TeamCity URLs use "buildType:ExternalId" format — strip the prefix
        if (id.startsWith("buildType:")) {
            id = id.substring("buildType:".length());
        }

        SBuildType bt = myProjectManager.findBuildTypeByExternalId(id);
        if (bt != null) return bt.getExternalId();

        bt = myProjectManager.findBuildTypeById(id);
        if (bt != null) return bt.getExternalId();

        return null;
    }
}
