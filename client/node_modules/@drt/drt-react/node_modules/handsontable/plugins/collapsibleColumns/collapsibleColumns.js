"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _base = require("../base");
var _array = require("../../helpers/array");
var _number = require("../../helpers/number");
var _console = require("../../helpers/console");
var _element = require("../../helpers/dom/element");
var _event = require("../../helpers/dom/event");
var _shortcutContexts = require("../../shortcutContexts");
var _a11y = require("../../helpers/a11y");
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
const PLUGIN_KEY = exports.PLUGIN_KEY = 'collapsibleColumns';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 290;
const SETTING_KEYS = ['nestedHeaders'];
const COLLAPSIBLE_ELEMENT_CLASS = 'collapsibleIndicator';
const SHORTCUTS_GROUP = PLUGIN_KEY;
const actionDictionary = new Map([['collapse', {
  hideColumn: true,
  beforeHook: 'beforeColumnCollapse',
  afterHook: 'afterColumnCollapse'
}], ['expand', {
  hideColumn: false,
  beforeHook: 'beforeColumnExpand',
  afterHook: 'afterColumnExpand'
}]]);

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin CollapsibleColumns
 * @class CollapsibleColumns
 *
 * @description
 * The _CollapsibleColumns_ plugin allows collapsing of columns, covered by a header with the `colspan` property defined.
 *
 * Clicking the "collapse/expand" button collapses (or expands) all "child" headers except the first one.
 *
 * Setting the {@link Options#collapsiblecolumns} property to `true` will display a "collapse/expand" button in every header
 * with a defined `colspan` property.
 *
 * To limit this functionality to a smaller group of headers, define the `collapsibleColumns` property as an array
 * of objects, as in the example below.
 *
 * @example
 * ::: only-for javascript
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: generateDataObj(),
 *   colHeaders: true,
 *   rowHeaders: true,
 *   nestedHeaders: true,
 *   // enable plugin
 *   collapsibleColumns: true,
 * });
 *
 * // or
 * const hot = new Handsontable(container, {
 *   data: generateDataObj(),
 *   colHeaders: true,
 *   rowHeaders: true,
 *   nestedHeaders: true,
 *   // enable and configure which columns can be collapsed
 *   collapsibleColumns: [
 *     {row: -4, col: 1, collapsible: true},
 *     {row: -3, col: 5, collapsible: true}
 *   ],
 * });
 * ```
 * :::
 *
 * ::: only-for react
 * ```jsx
 * <HotTable
 *   data={generateDataObj()}
 *   colHeaders={true}
 *   rowHeaders={true}
 *   nestedHeaders={true}
 *   // enable plugin
 *   collapsibleColumns={true}
 * />
 *
 * // or
 * <HotTable
 *   data={generateDataObj()}
 *   colHeaders={true}
 *   rowHeaders={true}
 *   nestedHeaders={true}
 *   // enable and configure which columns can be collapsed
 *   collapsibleColumns={[
 *     {row: -4, col: 1, collapsible: true},
 *     {row: -3, col: 5, collapsible: true}
 *   ]}
 * />
 * ```
 * :::
 */
var _collapsedColumnsMap = /*#__PURE__*/new WeakMap();
var _CollapsibleColumns_brand = /*#__PURE__*/new WeakSet();
class CollapsibleColumns extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Adds the indicator to the headers.
     *
     * @param {number} column Column index.
     * @param {HTMLElement} TH TH element.
     * @param {number} headerLevel The index of header level counting from the top (positive
     *                             values counting from 0 to N).
     */
    _classPrivateMethodInitSpec(this, _CollapsibleColumns_brand);
    /**
     * Cached reference to the NestedHeaders plugin.
     *
     * @private
     * @type {NestedHeaders}
     */
    _defineProperty(this, "nestedHeadersPlugin", null);
    /**
     * The NestedHeaders plugin StateManager instance.
     *
     * @private
     * @type {StateManager}
     */
    _defineProperty(this, "headerStateManager", null);
    /**
     * Map of collapsed columns by the plugin.
     *
     * @private
     * @type {HidingMap|null}
     */
    _classPrivateFieldInitSpec(this, _collapsedColumnsMap, null);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get PLUGIN_DEPS() {
    return ['plugin:NestedHeaders'];
  }
  static get SETTING_KEYS() {
    return [PLUGIN_KEY, ...SETTING_KEYS];
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link CollapsibleColumns#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return !!this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    var _this = this;
    if (this.enabled) {
      return;
    }
    const {
      nestedHeaders
    } = this.hot.getSettings();
    if (!nestedHeaders) {
      (0, _console.warn)('You need to configure the Nested Headers plugin in order to use collapsible headers.');
    }
    _classPrivateFieldSet(_collapsedColumnsMap, this, this.hot.columnIndexMapper.createAndRegisterIndexMap(this.pluginName, 'hiding'));
    this.nestedHeadersPlugin = this.hot.getPlugin('nestedHeaders');
    this.headerStateManager = this.nestedHeadersPlugin.getStateManager();
    this.addHook('init', () => _assertClassBrand(_CollapsibleColumns_brand, this, _onInit).call(this));
    this.addHook('afterLoadData', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_CollapsibleColumns_brand, _this, _onAfterLoadData).call(_this, ...args);
    });
    this.addHook('afterGetColHeader', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_CollapsibleColumns_brand, _this, _onAfterGetColHeader).call(_this, ...args);
    });
    this.addHook('beforeOnCellMouseDown', (event, coords, TD) => _assertClassBrand(_CollapsibleColumns_brand, this, _onBeforeOnCellMouseDown).call(this, event, coords, TD));
    this.registerShortcuts();
    super.enablePlugin();
    // @TODO: Workaround for broken plugin initialization abstraction (#6806).
    this.updatePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *   - [`collapsibleColumns`](@/api/options.md#collapsiblecolumns)
   *   - [`nestedHeaders`](@/api/options.md#nestedheaders)
   */
  updatePlugin() {
    // @TODO: Workaround for broken plugin initialization abstraction (#6806).
    if (!this.hot.view) {
      return;
    }
    if (!this.nestedHeadersPlugin.detectedOverlappedHeaders) {
      const {
        collapsibleColumns
      } = this.hot.getSettings();
      if (typeof collapsibleColumns === 'boolean') {
        // Add `collapsible: true` attribute to all headers with colspan higher than 1.
        this.headerStateManager.mapState(headerSettings => {
          return {
            collapsible: headerSettings.origColspan > 1
          };
        });
      } else if (Array.isArray(collapsibleColumns)) {
        this.headerStateManager.mapState(() => {
          return {
            collapsible: false
          };
        });
        this.headerStateManager.mergeStateWith(collapsibleColumns);
      }
    }
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    this.hot.columnIndexMapper.unregisterMap(this.pluginName);
    _classPrivateFieldSet(_collapsedColumnsMap, this, null);
    this.nestedHeadersPlugin = null;
    this.unregisterShortcuts();
    this.clearButtons();
    super.disablePlugin();
  }

  /**
   * Register shortcuts responsible for toggling collapsible columns.
   *
   * @private
   */
  registerShortcuts() {
    this.hot.getShortcutManager().getContext('grid').addShortcut({
      keys: [['Enter']],
      callback: () => {
        var _this$headerStateMana;
        const {
          row,
          col
        } = this.hot.getSelectedRangeLast().highlight;
        const {
          collapsible,
          isCollapsed,
          columnIndex
        } = (_this$headerStateMana = this.headerStateManager.getHeaderTreeNodeData(row, col)) !== null && _this$headerStateMana !== void 0 ? _this$headerStateMana : {};
        if (!collapsible) {
          return;
        }
        if (isCollapsed) {
          this.expandSection({
            row,
            col: columnIndex
          });
        } else {
          this.collapseSection({
            row,
            col: columnIndex
          });
        }

        // prevent default Enter behavior (move to the next row within a selection range)
        return false;
      },
      runOnlyIf: () => {
        var _this$hot$getSelected, _this$hot$getSelected2;
        return ((_this$hot$getSelected = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected === void 0 ? void 0 : _this$hot$getSelected.isSingle()) && ((_this$hot$getSelected2 = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected2 === void 0 ? void 0 : _this$hot$getSelected2.highlight.isHeader());
      },
      group: SHORTCUTS_GROUP,
      relativeToGroup: _shortcutContexts.EDITOR_EDIT_GROUP,
      position: 'before'
    });
  }

  /**
   * Unregister shortcuts responsible for toggling collapsible columns.
   *
   * @private
   */
  unregisterShortcuts() {
    this.hot.getShortcutManager().getContext('grid').removeShortcutsByGroup(SHORTCUTS_GROUP);
  }

  /**
   * Clears the expand/collapse buttons.
   *
   * @private
   */
  clearButtons() {
    if (!this.hot.view) {
      return;
    }
    const headerLevels = this.hot.view._wt.getSetting('columnHeaders').length;
    const mainHeaders = this.hot.view._wt.wtTable.THEAD;
    const topHeaders = this.hot.view._wt.wtOverlays.topOverlay.clone.wtTable.THEAD;
    const topLeftCornerHeaders = this.hot.view._wt.wtOverlays.topInlineStartCornerOverlay ? this.hot.view._wt.wtOverlays.topInlineStartCornerOverlay.clone.wtTable.THEAD : null;
    const removeButton = function (button) {
      if (button) {
        button.parentNode.removeChild(button);
      }
    };
    (0, _number.rangeEach)(0, headerLevels - 1, i => {
      const masterLevel = mainHeaders.childNodes[i];
      const topLevel = topHeaders.childNodes[i];
      const topLeftCornerLevel = topLeftCornerHeaders ? topLeftCornerHeaders.childNodes[i] : null;
      (0, _number.rangeEach)(0, masterLevel.childNodes.length - 1, j => {
        let button = masterLevel.childNodes[j].querySelector(`.${COLLAPSIBLE_ELEMENT_CLASS}`);
        removeButton(button);
        if (topLevel && topLevel.childNodes[j]) {
          button = topLevel.childNodes[j].querySelector(`.${COLLAPSIBLE_ELEMENT_CLASS}`);
          removeButton(button);
        }
        if (topLeftCornerHeaders && topLeftCornerLevel && topLeftCornerLevel.childNodes[j]) {
          button = topLeftCornerLevel.childNodes[j].querySelector(`.${COLLAPSIBLE_ELEMENT_CLASS}`);
          removeButton(button);
        }
      });
    }, true);
  }

  /**
   * Expands section at the provided coords.
   *
   * @param {object} coords Contains coordinates information. (`coords.row`, `coords.col`).
   */
  expandSection(coords) {
    this.toggleCollapsibleSection([coords], 'expand');
  }

  /**
   * Collapses section at the provided coords.
   *
   * @param {object} coords Contains coordinates information. (`coords.row`, `coords.col`).
   */
  collapseSection(coords) {
    this.toggleCollapsibleSection([coords], 'collapse');
  }

  /**
   * Collapses or expand all collapsible sections, depending on the action parameter.
   *
   * @param {string} action 'collapse' or 'expand'.
   */
  toggleAllCollapsibleSections(action) {
    const coords = this.headerStateManager.mapNodes(headerSettings => {
      const {
        collapsible,
        origColspan,
        headerLevel,
        columnIndex,
        isCollapsed
      } = headerSettings;
      if (collapsible === true && origColspan > 1 && (isCollapsed && action === 'expand' || !isCollapsed && action === 'collapse')) {
        return {
          row: this.headerStateManager.levelToRowCoords(headerLevel),
          col: columnIndex
        };
      }
    });
    this.toggleCollapsibleSection(coords, action);
  }

  /**
   * Collapses all collapsible sections.
   */
  collapseAll() {
    this.toggleAllCollapsibleSections('collapse');
  }

  /**
   * Expands all collapsible sections.
   */
  expandAll() {
    this.toggleAllCollapsibleSections('expand');
  }

  /**
   * Collapses/Expands a section.
   *
   * @param {Array} coords Array of coords - section coordinates.
   * @param {string} [action] Action definition ('collapse' or 'expand').
   * @fires Hooks#beforeColumnCollapse
   * @fires Hooks#beforeColumnExpand
   * @fires Hooks#afterColumnCollapse
   * @fires Hooks#afterColumnExpand
   */
  toggleCollapsibleSection(coords, action) {
    if (!actionDictionary.has(action)) {
      throw new Error(`Unsupported action is passed (${action}).`);
    }
    if (!Array.isArray(coords)) {
      return;
    }

    // Ignore coordinates which points to the cells range.
    const filteredCoords = (0, _array.arrayFilter)(coords, _ref => {
      let {
        row
      } = _ref;
      return row < 0;
    });
    let isActionPossible = filteredCoords.length > 0;
    (0, _array.arrayEach)(filteredCoords, _ref2 => {
      var _this$headerStateMana2;
      let {
        row,
        col: column
      } = _ref2;
      const {
        collapsible,
        isCollapsed
      } = (_this$headerStateMana2 = this.headerStateManager.getHeaderSettings(row, column)) !== null && _this$headerStateMana2 !== void 0 ? _this$headerStateMana2 : {};
      if (!collapsible || isCollapsed && action === 'collapse' || !isCollapsed && action === 'expand') {
        isActionPossible = false;
        return false;
      }
    });
    const nodeModRollbacks = [];
    const affectedColumnsIndexes = [];
    if (isActionPossible) {
      (0, _array.arrayEach)(filteredCoords, _ref3 => {
        let {
          row,
          col: column
        } = _ref3;
        const {
          colspanCompensation,
          affectedColumns,
          rollbackModification
        } = this.headerStateManager.triggerNodeModification(action, row, column);
        if (colspanCompensation > 0) {
          affectedColumnsIndexes.push(...affectedColumns);
          nodeModRollbacks.push(rollbackModification);
        }
      });
    }
    const currentCollapsedColumns = this.getCollapsedColumns();
    let destinationCollapsedColumns = [];
    if (action === 'collapse') {
      destinationCollapsedColumns = (0, _array.arrayUnique)([...currentCollapsedColumns, ...affectedColumnsIndexes]);
    } else if (action === 'expand') {
      destinationCollapsedColumns = (0, _array.arrayFilter)(currentCollapsedColumns, index => !affectedColumnsIndexes.includes(index));
    }
    const actionTranslator = actionDictionary.get(action);
    const isActionAllowed = this.hot.runHooks(actionTranslator.beforeHook, currentCollapsedColumns, destinationCollapsedColumns, isActionPossible);
    if (isActionAllowed === false) {
      // Rollback all header nodes modification (collapse or expand).
      (0, _array.arrayEach)(nodeModRollbacks, nodeModRollback => {
        nodeModRollback();
      });
      return;
    }
    this.hot.batchExecution(() => {
      (0, _array.arrayEach)(affectedColumnsIndexes, visualColumn => {
        _classPrivateFieldGet(_collapsedColumnsMap, this).setValueAtIndex(this.hot.toPhysicalColumn(visualColumn), actionTranslator.hideColumn);
      });
    }, true);
    const isActionPerformed = this.getCollapsedColumns().length !== currentCollapsedColumns.length;
    const selectionRange = this.hot.getSelectedRangeLast();
    if (action === 'collapse' && isActionPerformed && selectionRange) {
      const {
        row,
        col
      } = selectionRange.highlight;
      const isHidden = this.hot.rowIndexMapper.isHidden(row) || this.hot.columnIndexMapper.isHidden(col);
      if (isHidden && affectedColumnsIndexes.includes(col)) {
        const nextRow = row >= 0 ? this.hot.rowIndexMapper.getNearestNotHiddenIndex(row, 1, true) : row;
        const nextColumn = col >= 0 ? this.hot.columnIndexMapper.getNearestNotHiddenIndex(col, 1, true) : col;
        if (nextRow !== null && nextColumn !== null) {
          this.hot.selectCell(nextRow, nextColumn);
        }
      }
    }
    this.hot.runHooks(actionTranslator.afterHook, currentCollapsedColumns, destinationCollapsedColumns, isActionPossible, isActionPerformed);
    this.hot.render();
    this.hot.view.adjustElementsSize();
  }

  /**
   * Gets an array of physical indexes of collapsed columns.
   *
   * @private
   * @returns {number[]}
   */
  getCollapsedColumns() {
    return _classPrivateFieldGet(_collapsedColumnsMap, this).getHiddenIndexes();
  }
  /**
   * Destroys the plugin instance.
   */
  destroy() {
    _classPrivateFieldSet(_collapsedColumnsMap, this, null);
    super.destroy();
  }
}
exports.CollapsibleColumns = CollapsibleColumns;
function _onAfterGetColHeader(column, TH, headerLevel) {
  var _this$headerStateMana3;
  const {
    collapsible,
    origColspan,
    isCollapsed
  } = (_this$headerStateMana3 = this.headerStateManager.getHeaderSettings(headerLevel, column)) !== null && _this$headerStateMana3 !== void 0 ? _this$headerStateMana3 : {};
  const isNodeCollapsible = collapsible && origColspan > 1 && column >= this.hot.getSettings().fixedColumnsStart;
  const isAriaTagsEnabled = this.hot.getSettings().ariaTags;
  let collapsibleElement = TH.querySelector(`.${COLLAPSIBLE_ELEMENT_CLASS}`);
  (0, _element.removeAttribute)(TH, [(0, _a11y.A11Y_EXPANDED)('')[0]]);
  if (isNodeCollapsible) {
    if (!collapsibleElement) {
      collapsibleElement = this.hot.rootDocument.createElement('div');
      (0, _element.addClass)(collapsibleElement, COLLAPSIBLE_ELEMENT_CLASS);
      TH.querySelector('div:first-child').appendChild(collapsibleElement);
    }
    (0, _element.removeClass)(collapsibleElement, ['collapsed', 'expanded']);
    if (isCollapsed) {
      (0, _element.addClass)(collapsibleElement, 'collapsed');
      (0, _element.fastInnerText)(collapsibleElement, '+');

      // Add ARIA tags
      if (isAriaTagsEnabled) {
        (0, _element.setAttribute)(TH, ...(0, _a11y.A11Y_EXPANDED)(false));
      }
    } else {
      (0, _element.addClass)(collapsibleElement, 'expanded');
      (0, _element.fastInnerText)(collapsibleElement, '-');

      // Add ARIA tags
      if (isAriaTagsEnabled) {
        (0, _element.setAttribute)(TH, ...(0, _a11y.A11Y_EXPANDED)(true));
      }
    }
    if (isAriaTagsEnabled) {
      (0, _element.setAttribute)(collapsibleElement, ...(0, _a11y.A11Y_HIDDEN)());
    }
  } else {
    var _collapsibleElement;
    (_collapsibleElement = collapsibleElement) === null || _collapsibleElement === void 0 || _collapsibleElement.remove();
  }
}
/**
 * Indicator mouse event callback.
 *
 * @param {object} event Mouse event.
 * @param {object} coords Event coordinates.
 */
function _onBeforeOnCellMouseDown(event, coords) {
  if ((0, _element.hasClass)(event.target, COLLAPSIBLE_ELEMENT_CLASS)) {
    if ((0, _element.hasClass)(event.target, 'expanded')) {
      this.eventManager.fireEvent(event.target, 'mouseup');
      this.toggleCollapsibleSection([coords], 'collapse');
    } else if ((0, _element.hasClass)(event.target, 'collapsed')) {
      this.eventManager.fireEvent(event.target, 'mouseup');
      this.toggleCollapsibleSection([coords], 'expand');
    }
    (0, _event.stopImmediatePropagation)(event);
  }
}
/**
 * Updates the plugin state after HoT initialization.
 */
function _onInit() {
  // @TODO: Workaround for broken plugin initialization abstraction (#6806).
  this.updatePlugin();
}
/**
 * Updates the plugin state after new dataset load.
 *
 * @param {Array[]} sourceData Array of arrays or array of objects containing data.
 * @param {boolean} initialLoad Flag that determines whether the data has been loaded
 *                              during the initialization.
 */
function _onAfterLoadData(sourceData, initialLoad) {
  if (!initialLoad) {
    this.updatePlugin();
  }
}