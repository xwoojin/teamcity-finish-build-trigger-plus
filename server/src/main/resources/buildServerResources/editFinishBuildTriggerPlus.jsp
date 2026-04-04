<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l"     tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="c"     uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="propertiesBean" scope="request"
             type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<l:settingsGroup title="Finish Build Trigger (Plus) Settings">

  <%-- ------------------------------------------------------------------ --%>
  <%-- 1. Build configuration to watch                                     --%>
  <%-- ------------------------------------------------------------------ --%>
  <tr>
    <td style="vertical-align: baseline; width: 160px;">
      <label for="watchedBuildTypeId">Build configuration:<l:star/></label>
    </td>
    <td style="vertical-align: baseline;">
      <input type="hidden"
             id="watchedBuildTypeId"
             name="prop:watchedBuildTypeId"
             value="${propertiesBean.properties['watchedBuildTypeId']}"/>

      <div id="watchedBuildTypeSelectorWrapper" style="width: 330px;"></div>

      <script type="text/javascript">
        ReactUIPromise.then(function(ReactUI) {
          ReactUI.renderConnected(
            document.getElementById('watchedBuildTypeSelectorWrapper'),
            ReactUI.ProjectBuildTypeSelect,
            {
              <c:if test="${not empty propertiesBean.properties['watchedBuildTypeId']}">
              selected: {
                nodeType: 'bt',
                id: '${propertiesBean.properties["watchedBuildTypeId"]}'
              },
              </c:if>
              onSelect: function(item) {
                $j('#watchedBuildTypeId').val(item ? item.id : '');
              }
            }
          );
        });
      </script>

      <span class="error" id="error_watchedBuildTypeId"></span>
    </td>
  </tr>

  <%-- ------------------------------------------------------------------ --%>
  <%-- 2. Trigger after successful build only                              --%>
  <%-- ------------------------------------------------------------------ --%>
  <tr>
    <td class="noBorder">&nbsp;</td>
    <td class="noBorder">
      <props:checkboxProperty name="afterSuccessfulBuildOnly"/>
      <label for="afterSuccessfulBuildOnly">Trigger after successful build only</label>
    </td>
  </tr>

  <%-- ------------------------------------------------------------------ --%>
  <%-- 3. Trigger on all enabled and compatible agents                     --%>
  <%--    noBorder = no separator above (same group as "successful only")  --%>
  <%-- ------------------------------------------------------------------ --%>
  <tr>
    <td class="noBorder">&nbsp;</td>
    <td class="noBorder">
      <props:checkboxProperty name="triggerBuildOnAllCompatibleAgents"/>
      <label for="triggerBuildOnAllCompatibleAgents">Trigger build on all enabled and compatible agents</label>
    </td>
  </tr>

  <%-- ------------------------------------------------------------------ --%>
  <%-- 4. Time to wait (in minutes) — separator above via default border   --%>
  <%-- ------------------------------------------------------------------ --%>
  <tr>
    <td>
      <label for="waitMinutes">Time to wait (minutes):</label>
    </td>
    <td>
      <props:textProperty name="waitMinutes" style="width: 5em;" maxlength="5"/>
      <span class="smallNote">Leave empty or set to 0 for immediate trigger.</span>
      <span class="error" id="error_waitMinutes"></span>
    </td>
  </tr>

</l:settingsGroup>
