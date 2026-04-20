<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l"     tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="c"     uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="propertiesBean" scope="request"
             type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<%-- ================================================================== --%>
<%-- 0. Trigger Chain link (requires trigger-chain-viewer plugin)        --%>
<%-- ================================================================== --%>
<c:if test="${not empty currentBuildTypeExternalId}">
  <tr>
    <td colspan="2" class="noBorder" style="padding-bottom: 8px;">
      <span class="smallNote">
        View the
        <a href="<c:url value='/viewType.html?buildTypeId=${currentBuildTypeExternalId}&tab=triggerChainView'/>"
           target="_blank" rel="noopener">trigger chain</a>
        or
        <a href="<c:url value='/viewType.html?buildTypeId=${currentBuildTypeExternalId}&tab=triggerUsageView'/>"
           target="_blank" rel="noopener">trigger usage</a>
        for this build configuration.
        <em>(Requires the Trigger Chain Viewer plugin.)</em>
      </span>
    </td>
  </tr>
</c:if>

<%-- ================================================================== --%>
<%-- 1. Build configuration(s) to watch                                  --%>
<%-- ================================================================== --%>
<l:settingsGroup title="Finish Build Trigger (Plus) Settings">
  <tr>
    <td style="vertical-align: baseline; width: 160px;">
      <label>Build configuration:<l:star/></label>
    </td>
    <td style="vertical-align: baseline;">
      <%-- Use Controller-resolved IDs (handles renames) if available, otherwise raw property --%>
      <c:set var="watchedIds" value="${not empty resolvedWatchedBuildTypeIds ? resolvedWatchedBuildTypeIds : propertiesBean.properties['watchedBuildTypeId']}" />
      <input type="hidden"
             id="watchedBuildTypeId"
             name="prop:watchedBuildTypeId"
             value="${watchedIds}"/>

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
          var renderers   = {};   // idx -> function(selectedId) that re-renders the React selector

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

            var isMulti = ids.length > 1;
            var note = document.getElementById('multiBuildNote');
            if (note) note.style.display = isMulti ? '' : 'none';
            var twRow = document.getElementById('andTimeWindowRow');
            if (twRow) twRow.style.display = isMulti ? '' : 'none';

            updateRemoveButtons();
            refreshAllSelectors();
          }

          // Re-render every selector so its dropdown filter reflects the
          // current selections in other rows (selected builds disappear
          // from other selectors' lists).
          function refreshAllSelectors() {
            for (var key in renderers) {
              if (renderers.hasOwnProperty(key) && typeof renderers[key] === 'function') {
                renderers[key](selections[key] || null);
              }
            }
          }

          // Build the set of build-type IDs to hide from selector #currentIdx.
          // Hides: the current build configuration itself + every other
          // already-selected build configuration.
          function buildExcludeSet(currentIdx) {
            var excl = {};
            if (currentBtId) excl[currentBtId] = true;
            for (var key in selections) {
              if (!selections.hasOwnProperty(key)) continue;
              if (String(key) === String(currentIdx)) continue;
              var v = selections[key];
              if (v) excl[v] = true;
            }
            return excl;
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
                delete renderers[capturedIdx];
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

            // Render function — can be recalled to reset the selector or
            // to refresh its dropdown filter when selections elsewhere change.
            var renderReactSelector = function(selectedId) {
              ReactUIPromise.then(function(ReactUI) {
                var excl = buildExcludeSet(idx);
                var props = {
                  // Hide the current build type and any already-selected builds
                  // from this selector's dropdown list. The predicate form is
                  // the widely-supported filter API on ProjectBuildTypeSelect.
                  filter: function(item) {
                    if (!item || !item.id) return true;
                    if (item.nodeType && item.nodeType !== 'bt') return true; // keep projects visible
                    return !excl[item.id];
                  },
                  onSelect: function(item) {
                    var newId = item ? item.id : '';
                    clearError(idx);

                    // Self-reference check (defensive — should be filtered out)
                    if (newId && currentBtId && newId === currentBtId) {
                      showError(idx,
                        'Cannot watch the current build configuration itself.');
                      selections[idx] = '';
                      updateHiddenInput();
                      return;
                    }

                    // Duplicate check (defensive — should be filtered out)
                    if (isDuplicate(newId, idx)) {
                      showError(idx,
                        'This build configuration is already selected.');
                      selections[idx] = '';
                      updateHiddenInput();
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

            renderers[idx] = renderReactSelector;
            renderReactSelector(initialId);
            updateRemoveButtons();
          }

          window._fbtp_addSelector = function() {
            addSelector('');
          };

          // Resolve a stored ID (possibly a stale external ID or an internal ID)
          // to the current external ID via TC REST API. Returns a Promise.
          function resolveId(storedId) {
            return new Promise(function(resolve) {
              if (!storedId) { resolve(''); return; }
              $j.ajax({
                url: window['base_uri'] ? window['base_uri'] + '/app/rest/buildTypes/id:' + encodeURIComponent(storedId) + '?fields=id'
                                        : '/app/rest/buildTypes/id:' + encodeURIComponent(storedId) + '?fields=id',
                type: 'GET',
                dataType: 'xml',
                headers: { 'Accept': 'application/xml' },
                success: function(data) {
                  try {
                    var bt = data.getElementsByTagName('buildType')[0] || data.documentElement;
                    var resolved = bt && bt.getAttribute ? bt.getAttribute('id') : '';
                    resolve(resolved || storedId);
                  } catch (e) {
                    resolve(storedId);
                  }
                },
                error: function() {
                  // Not found — render with original id; the selector will show empty but won't break
                  resolve(storedId);
                }
              });
            });
          }

          var initialValue = $j('#watchedBuildTypeId').val() || '';
          if (initialValue) {
            var ids = initialValue.split(',');
            var resolvePromises = [];
            for (var i = 0; i < ids.length; i++) {
              var trimmed = ids[i].replace(/^\s+|\s+$/g, '');
              if (trimmed) resolvePromises.push(resolveId(trimmed));
            }
            if (resolvePromises.length === 0) {
              addSelector('');
              updateHiddenInput();
            } else {
              Promise.all(resolvePromises).then(function(resolvedIds) {
                for (var k = 0; k < resolvedIds.length; k++) {
                  addSelector(resolvedIds[k]);
                }
                // Fix: set initial visibility of multi-build UI elements
                updateHiddenInput();
              });
            }
          } else {
            addSelector('');
            // Fix: set initial visibility of multi-build UI elements
            updateHiddenInput();
          }
        })();
      </script>
    </td>
  </tr>
</l:settingsGroup>

<%-- Hidden holder for andTimeWindowHours property (injected into Build Customization tab via JS) --%>
<div style="display:none;">
  <props:textProperty name="andTimeWindowHours" id="andTimeWindowHoursInput" style="width: 5em;" maxlength="5"/>
</div>

<script type="text/javascript">
  /**
   * Injects "Time Settings" section into the Build Customization tab,
   * between "General settings" and "Build parameters".
   */
  (function() {
    var initialVal = $j('#andTimeWindowHoursInput').val() || '';

    function injectTimeSettings() {
      // Find the Build Customization tab content
      var dialog = $j('.modalDialog').length ? $j('.modalDialog') : $j('.dialog');
      if (!dialog.length) return false;

      // Find section headers — look for "Build parameters" text
      var buildParamsHeader = null;
      dialog.find('.groupBox, .subHeader, .title, .group-header, th, td').each(function() {
        var text = $j(this).text().trim();
        if (text === 'Build parameters') {
          buildParamsHeader = $j(this).closest('tr, .group, .settingsBlock, table');
          return false;
        }
      });

      if (!buildParamsHeader || !buildParamsHeader.length) return false;

      // Check if already injected
      if ($j('#fbtpTimeSettingsSection').length) return true;

      // Build the "Time Settings" section
      var section = $j(
        '<tr id="fbtpTimeSettingsSection">' +
          '<td colspan="2" style="padding: 0;">' +
            '<table class="runnerFormTable" style="width:100%; margin: 0;">' +
              '<tr class="groupingTitle">' +
                '<td colspan="2">Time Settings</td>' +
              '</tr>' +
              '<tr id="fbtpTimeFrameRow">' +
                '<td style="width:200px; vertical-align: baseline;">' +
                  '<label for="fbtpTimeFrameInput">Watched Time Frame (Hours):</label>' +
                '</td>' +
                '<td>' +
                  '<input type="text" id="fbtpTimeFrameInput" ' +
                    'style="width:5em;" maxlength="5" ' +
                    'value="' + initialVal.replace(/"/g, '&quot;') + '"/>' +
                  '<span class="smallNote" style="margin-left:4px;">' +
                    'All watched builds must finish within this window. Default: 3 hours.' +
                  '</span>' +
                  '<span class="error" id="error_andTimeWindowHours" style="display:block;"></span>' +
                '</td>' +
              '</tr>' +
            '</table>' +
          '</td>' +
        '</tr>'
      );

      // Insert before "Build parameters" section
      buildParamsHeader.before(section);

      // Sync value back to the hidden prop input
      $j('#fbtpTimeFrameInput').on('input change', function() {
        $j('#andTimeWindowHoursInput').val($j(this).val());
      });

      // Show/hide based on multi-build selection
      function updateTimeFrameVisibility() {
        var val = $j('#watchedBuildTypeId').val() || '';
        var ids = val.split(',').filter(function(s) { return s.trim() !== ''; });
        var isMulti = ids.length > 1;
        $j('#fbtpTimeSettingsSection').toggle(isMulti);
      }
      updateTimeFrameVisibility();

      // Observe hidden input changes
      var observer = new MutationObserver(function() { updateTimeFrameVisibility(); });
      var hiddenInput = document.getElementById('watchedBuildTypeId');
      if (hiddenInput) {
        observer.observe(hiddenInput, { attributes: true, attributeFilter: ['value'] });
      }
      // Also poll for changes (jQuery .val() doesn't trigger MutationObserver)
      setInterval(updateTimeFrameVisibility, 500);

      return true;
    }

    // Retry injection — Build Customization tab may load lazily
    var attempts = 0;
    var timer = setInterval(function() {
      if (injectTimeSettings() || ++attempts > 30) {
        clearInterval(timer);
      }
    }, 300);
  })();
</script>

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
      <props:checkboxProperty name="triggerBuildOnAllCompatibleAgents"
                              onclick="if(this.checked){$j('#triggerOnSameAgent').prop('checked',false);}"/>
      <label for="triggerBuildOnAllCompatibleAgents">Trigger build on all enabled and compatible agents</label>
    </td>
  </tr>
  <tr>
    <td class="noBorder">&nbsp;</td>
    <td class="noBorder">
      <props:checkboxProperty name="triggerOnSameAgent"
                              onclick="if(this.checked){$j('#triggerBuildOnAllCompatibleAgents').prop('checked',false);}"/>
      <label for="triggerOnSameAgent">Run build on the same agent</label>
      <div class="smallNote" style="margin-left: 20px;">
        Queues on the agent that ran the watched build. Falls back to default queue if unavailable.
      </div>
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
