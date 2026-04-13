<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l"     tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="c"     uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="propertiesBean" scope="request"
             type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<%-- ================================================================== --%>
<%-- 1. Build configuration(s) to watch                                  --%>
<%-- ================================================================== --%>
<l:settingsGroup title="Finish Build Trigger (Plus) Settings">
  <tr>
    <td style="vertical-align: baseline; width: 160px;">
      <label>Build configuration:<l:star/></label>
    </td>
    <td style="vertical-align: baseline;">
      <input type="hidden"
             id="watchedBuildTypeId"
             name="prop:watchedBuildTypeId"
             value="${propertiesBean.properties['watchedBuildTypeId']}"/>

      <div id="buildSelectorsContainer"></div>

      <a href="#" id="addBuildConfigLink"
         style="margin-top: 4px; display: inline-block; font-size: 12px;"
         onclick="window._fbtp_addSelector(); return false;">+ Add build configuration</a>

      <div id="multiBuildNote" style="display: none; margin-top: 4px;">
        <span class="smallNote">Trigger fires when <b>all</b> selected builds have completed.</span>
      </div>

      <span class="error" id="error_watchedBuildTypeId"></span>

      <script type="text/javascript">
        (function() {
          var selectorIndex = 0;
          var selections = {};    // idx -> externalId
          var errorDivs   = {};   // idx -> error div element

          // ── Current build type external ID (from server) ──
          var currentBtId = '<c:out value="${currentBuildTypeExternalId}" default="" />';
          if (!currentBtId) {
            // Fallback: try URL parsing
            try {
              var m = window.location.search.match(/[?&]id=([^&#]+)/);
              if (m) currentBtId = decodeURIComponent(m[1]);
            } catch(e) {}
            if (!currentBtId) {
              try {
                var hm = window.location.hash.match(/\/buildConfiguration\/([^\/]+)/);
                if (hm) currentBtId = decodeURIComponent(hm[1]);
              } catch(e) {}
            }
          }
          // Strip "buildType:" prefix used in TeamCity URLs
          if (currentBtId && currentBtId.indexOf('buildType:') === 0) {
            currentBtId = currentBtId.substring('buildType:'.length);
          }

          function updateHiddenInput() {
            var ids = [];
            for (var key in selections) {
              if (selections.hasOwnProperty(key) && selections[key]) {
                ids.push(selections[key]);
              }
            }
            $j('#watchedBuildTypeId').val(ids.join(','));

            var note = document.getElementById('multiBuildNote');
            if (note) note.style.display = ids.length > 1 ? '' : 'none';

            updateRemoveButtons();
          }

          function updateRemoveButtons() {
            var container = document.getElementById('buildSelectorsContainer');
            var rows = container.querySelectorAll('.fbtpSelectorRow');
            var activeCount = 0;
            for (var i = 0; i < rows.length; i++) {
              if (rows[i].style.display !== 'none') activeCount++;
            }
            for (var j = 0; j < rows.length; j++) {
              if (rows[j].style.display === 'none') continue;
              var btn = rows[j].querySelector('.fbtpRemoveBtn');
              if (btn) btn.style.display = activeCount > 1 ? '' : 'none';
            }
          }

          function showError(idx, msg) {
            if (!errorDivs[idx]) return;
            errorDivs[idx].textContent = msg;
            errorDivs[idx].style.display = '';
          }

          function clearError(idx) {
            if (!errorDivs[idx]) return;
            errorDivs[idx].textContent = '';
            errorDivs[idx].style.display = 'none';
          }

          function isDuplicate(selectedId, currentIdx) {
            if (!selectedId) return false;
            for (var key in selections) {
              if (selections.hasOwnProperty(key)
                  && String(key) !== String(currentIdx)
                  && selections[key] === selectedId) {
                return true;
              }
            }
            return false;
          }

          function addSelector(initialId) {
            var idx = selectorIndex++;
            selections[idx] = initialId || '';

            var row = document.createElement('div');
            row.className = 'fbtpSelectorRow';
            row.id = 'fbtpRow_' + idx;
            row.style.cssText = 'margin-bottom: 4px;';

            var inline = document.createElement('div');
            inline.style.cssText = 'display: flex; align-items: center;';

            var selectorDiv = document.createElement('div');
            selectorDiv.id = 'fbtpSel_' + idx;
            selectorDiv.style.cssText = 'width: 330px;';

            var removeBtn = document.createElement('a');
            removeBtn.href = '#';
            removeBtn.className = 'fbtpRemoveBtn';
            removeBtn.innerHTML = '&times;';
            removeBtn.title = 'Remove';
            removeBtn.style.cssText = 'margin-left: 8px; color: #999; text-decoration: none; font-size: 18px; line-height: 1; display: none;';
            removeBtn.onclick = (function(capturedIdx, capturedRow) {
              return function(e) {
                e.preventDefault();
                capturedRow.parentNode.removeChild(capturedRow);
                delete selections[capturedIdx];
                delete errorDivs[capturedIdx];
                updateHiddenInput();
              };
            })(idx, row);

            var errDiv = document.createElement('div');
            errDiv.className = 'error';
            errDiv.style.cssText = 'display: none; margin-top: 2px;';
            errorDivs[idx] = errDiv;

            inline.appendChild(selectorDiv);
            inline.appendChild(removeBtn);
            row.appendChild(inline);
            row.appendChild(errDiv);
            document.getElementById('buildSelectorsContainer').appendChild(row);

            // Render function — can be recalled to reset the selector
            var renderReactSelector = function(selectedId) {
              ReactUIPromise.then(function(ReactUI) {
                var props = {
                  onSelect: function(item) {
                    var newId = item ? item.id : '';
                    clearError(idx);

                    // Self-reference check
                    if (newId && currentBtId && newId === currentBtId) {
                      showError(idx,
                        'Cannot watch the current build configuration itself.');
                      selections[idx] = '';
                      updateHiddenInput();
                      // Re-render to clear the visual selection
                      renderReactSelector(null);
                      return;
                    }

                    // Duplicate check (by external ID)
                    if (isDuplicate(newId, idx)) {
                      showError(idx,
                        'This build configuration is already selected.');
                      selections[idx] = '';
                      updateHiddenInput();
                      // Re-render to clear the visual selection
                      renderReactSelector(null);
                      return;
                    }

                    selections[idx] = newId;
                    updateHiddenInput();
                  }
                };
                if (selectedId) {
                  props.selected = { nodeType: 'bt', id: selectedId };
                }
                ReactUI.renderConnected(selectorDiv, ReactUI.ProjectBuildTypeSelect, props);
              });
            };

            renderReactSelector(initialId);
            updateRemoveButtons();
          }

          window._fbtp_addSelector = function() {
            addSelector('');
          };

          var initialValue = $j('#watchedBuildTypeId').val() || '';
          if (initialValue) {
            var ids = initialValue.split(',');
            for (var i = 0; i < ids.length; i++) {
              var trimmed = ids[i].replace(/^\s+|\s+$/g, '');
              if (trimmed) addSelector(trimmed);
            }
          } else {
            addSelector('');
          }
        })();
      </script>
    </td>
  </tr>
</l:settingsGroup>

<%-- ================================================================== --%>
<%-- 2. Additional Options                                                --%>
<%-- ================================================================== --%>
<l:settingsGroup title="Additional Options">
  <tr>
    <td class="noBorder">&nbsp;</td>
    <td class="noBorder">
      <props:checkboxProperty name="afterSuccessfulBuildOnly"/>
      <label for="afterSuccessfulBuildOnly">Trigger after successful build only</label>
    </td>
  </tr>
  <tr>
    <td class="noBorder">&nbsp;</td>
    <td class="noBorder">
      <props:checkboxProperty name="triggerBuildOnAllCompatibleAgents"/>
      <label for="triggerBuildOnAllCompatibleAgents">Trigger build on all enabled and compatible agents</label>
    </td>
  </tr>
</l:settingsGroup>

<%-- ================================================================== --%>
<%-- 3. Delay Trigger Options                                             --%>
<%-- ================================================================== --%>
<l:settingsGroup title="Delay Trigger Options">
  <tr>
    <td style="width: 160px;">
      <label for="waitMinutes">Time to wait (minutes):</label>
    </td>
    <td>
      <props:textProperty name="waitMinutes" style="width: 5em;" maxlength="5"/>
      <span class="smallNote">Leave empty or set to 0 for immediate trigger.</span>
      <span class="error" id="error_waitMinutes"></span>
    </td>
  </tr>
</l:settingsGroup>
