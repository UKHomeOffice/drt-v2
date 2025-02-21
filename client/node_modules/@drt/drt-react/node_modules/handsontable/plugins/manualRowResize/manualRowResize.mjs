import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { BasePlugin } from "../base/index.mjs";
import { addClass, closest, hasClass, removeClass, outerWidth, isDetached } from "../../helpers/dom/element.mjs";
import { arrayEach } from "../../helpers/array.mjs";
import { rangeEach } from "../../helpers/number.mjs";
import { PhysicalIndexToValueMap as IndexToValueMap } from "../../translations/index.mjs"; // Developer note! Whenever you make a change in this file, make an analogous change in manualColumnResize.js
export const PLUGIN_KEY = 'manualRowResize';
export const PLUGIN_PRIORITY = 30;
const PERSISTENT_STATE_KEY = 'manualRowHeights';

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin ManualRowResize
 * @class ManualRowResize
 *
 * @description
 * This plugin allows to change rows height. To make rows height persistent the {@link Options#persistentState}
 * plugin should be enabled.
 *
 * The plugin creates additional components to make resizing possibly using user interface:
 * - handle - the draggable element that sets the desired height of the row.
 * - guide - the helper guide that shows the desired height as a horizontal guide.
 */
var _currentTH = /*#__PURE__*/new WeakMap();
var _currentRow = /*#__PURE__*/new WeakMap();
var _selectedRows = /*#__PURE__*/new WeakMap();
var _currentHeight = /*#__PURE__*/new WeakMap();
var _newSize = /*#__PURE__*/new WeakMap();
var _startY = /*#__PURE__*/new WeakMap();
var _startHeight = /*#__PURE__*/new WeakMap();
var _startOffset = /*#__PURE__*/new WeakMap();
var _handle = /*#__PURE__*/new WeakMap();
var _guide = /*#__PURE__*/new WeakMap();
var _pressed = /*#__PURE__*/new WeakMap();
var _isTriggeredByRMB = /*#__PURE__*/new WeakMap();
var _dblclick = /*#__PURE__*/new WeakMap();
var _autoresizeTimeout = /*#__PURE__*/new WeakMap();
var _rowHeightsMap = /*#__PURE__*/new WeakMap();
var _config = /*#__PURE__*/new WeakMap();
var _ManualRowResize_brand = /*#__PURE__*/new WeakSet();
export class ManualRowResize extends BasePlugin {
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }

  /**
   * @type {HTMLTableCellElement}
   */

  constructor(hotInstance) {
    super(hotInstance);
    /**
     * 'mouseover' event callback - set the handle position.
     *
     * @param {MouseEvent} event The mouse event.
     */
    _classPrivateMethodInitSpec(this, _ManualRowResize_brand);
    _classPrivateFieldInitSpec(this, _currentTH, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _currentRow, null);
    /**
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _selectedRows, []);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _currentHeight, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _newSize, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _startY, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _startHeight, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _startOffset, null);
    /**
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _handle, this.hot.rootDocument.createElement('DIV'));
    /**
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _guide, this.hot.rootDocument.createElement('DIV'));
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _pressed, false);
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isTriggeredByRMB, false);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _dblclick, 0);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _autoresizeTimeout, null);
    /**
     * PhysicalIndexToValueMap to keep and track widths for physical row indexes.
     *
     * @type {PhysicalIndexToValueMap}
     */
    _classPrivateFieldInitSpec(this, _rowHeightsMap, void 0);
    /**
     * Private pool to save configuration from updateSettings.
     *
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _config, void 0);
    addClass(_classPrivateFieldGet(_handle, this), 'manualRowResizer');
    addClass(_classPrivateFieldGet(_guide, this), 'manualRowResizerGuide');
  }

  /**
   * @private
   * @returns {string}
   */
  get inlineDir() {
    return this.hot.isRtl() ? 'right' : 'left';
  }

  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link ManualRowResize#enablePlugin} method is called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the plugin functionality for this Handsontable instance.
   */
  enablePlugin() {
    if (this.enabled) {
      return;
    }
    _classPrivateFieldSet(_rowHeightsMap, this, new IndexToValueMap());
    _classPrivateFieldGet(_rowHeightsMap, this).addLocalHook('init', () => _assertClassBrand(_ManualRowResize_brand, this, _onMapInit).call(this));
    this.hot.rowIndexMapper.registerMap(this.pluginName, _classPrivateFieldGet(_rowHeightsMap, this));
    this.addHook('modifyRowHeight', (height, row) => _assertClassBrand(_ManualRowResize_brand, this, _onModifyRowHeight).call(this, height, row));
    this.bindEvents();
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`manualRowResize`](@/api/options.md#manualrowresize)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    _classPrivateFieldSet(_config, this, _classPrivateFieldGet(_rowHeightsMap, this).getValues());
    this.hot.rowIndexMapper.unregisterMap(this.pluginName);
    super.disablePlugin();
  }

  /**
   * Saves the current sizes using the persistentState plugin (the {@link Options#persistentState} option has to be
   * enabled).
   *
   * @fires Hooks#persistentStateSave
   */
  saveManualRowHeights() {
    this.hot.runHooks('persistentStateSave', PERSISTENT_STATE_KEY, _classPrivateFieldGet(_rowHeightsMap, this).getValues());
  }

  /**
   * Loads the previously saved sizes using the persistentState plugin (the {@link Options#persistentState} option
   * has be enabled).
   *
   * @returns {Array}
   * @fires Hooks#persistentStateLoad
   */
  loadManualRowHeights() {
    const storedState = {};
    this.hot.runHooks('persistentStateLoad', PERSISTENT_STATE_KEY, storedState);
    return storedState.value;
  }

  /**
   * Sets the new height for specified row index.
   *
   * @param {number} row Visual row index.
   * @param {number} height Row height.
   * @returns {number} Returns new height.
   */
  setManualSize(row, height) {
    const physicalRow = this.hot.toPhysicalRow(row);
    const newHeight = Math.max(height, this.hot.view.getDefaultRowHeight());
    _classPrivateFieldGet(_rowHeightsMap, this).setValueAtIndex(physicalRow, newHeight);
    return newHeight;
  }

  /**
   * Returns the last desired row height set manually with the resize handle.
   *
   * @returns {number} The last desired row height.
   */
  getLastDesiredRowHeight() {
    return _classPrivateFieldGet(_currentHeight, this);
  }

  /**
   * Sets the resize handle position.
   *
   * @private
   * @param {HTMLCellElement} TH TH HTML element.
   */
  setupHandlePosition(TH) {
    _classPrivateFieldSet(_currentTH, this, TH);
    const {
      view
    } = this.hot;
    const {
      _wt: wt
    } = view;
    const cellCoords = wt.wtTable.getCoords(_classPrivateFieldGet(_currentTH, this));
    const row = cellCoords.row;

    // Ignore row headers.
    if (row < 0) {
      return;
    }
    const headerWidth = outerWidth(_classPrivateFieldGet(_currentTH, this));
    const box = _classPrivateFieldGet(_currentTH, this).getBoundingClientRect();
    // Read "fixedRowsTop" and "fixedRowsBottom" through the Walkontable as in that context, the fixed
    // rows are modified (reduced by the number of hidden rows) by TableView module.
    const fixedRowTop = row < wt.getSetting('fixedRowsTop');
    const fixedRowBottom = row >= view.countNotHiddenRowIndexes(0, 1) - wt.getSetting('fixedRowsBottom');
    let relativeHeaderPosition;
    if (fixedRowTop) {
      relativeHeaderPosition = wt.wtOverlays.topInlineStartCornerOverlay.getRelativeCellPosition(_classPrivateFieldGet(_currentTH, this), cellCoords.row, cellCoords.col);
    } else if (fixedRowBottom) {
      relativeHeaderPosition = wt.wtOverlays.bottomInlineStartCornerOverlay.getRelativeCellPosition(_classPrivateFieldGet(_currentTH, this), cellCoords.row, cellCoords.col);
    }

    // If the TH is not a child of the top-left/bottom-left overlay, recalculate using
    // the left overlay - as this overlay contains the rest of the headers.
    if (!relativeHeaderPosition) {
      relativeHeaderPosition = wt.wtOverlays.inlineStartOverlay.getRelativeCellPosition(_classPrivateFieldGet(_currentTH, this), cellCoords.row, cellCoords.col);
    }
    _classPrivateFieldSet(_currentRow, this, this.hot.rowIndexMapper.getVisualFromRenderableIndex(row));
    _classPrivateFieldSet(_selectedRows, this, []);
    const isFullRowSelected = this.hot.selection.isSelectedByCorner() || this.hot.selection.isSelectedByRowHeader();
    if (this.hot.selection.isSelected() && isFullRowSelected) {
      const selectionRanges = this.hot.getSelectedRange();
      arrayEach(selectionRanges, selectionRange => {
        const fromRow = selectionRange.getTopStartCorner().row;
        const toRow = selectionRange.getBottomStartCorner().row;

        // Add every selected row for resize action.
        rangeEach(fromRow, toRow, rowIndex => {
          if (!_classPrivateFieldGet(_selectedRows, this).includes(rowIndex)) {
            _classPrivateFieldGet(_selectedRows, this).push(rowIndex);
          }
        });
      });
    }

    // Resizing element beyond the current selection (also when there is no selection).
    if (!_classPrivateFieldGet(_selectedRows, this).includes(_classPrivateFieldGet(_currentRow, this))) {
      _classPrivateFieldSet(_selectedRows, this, [_classPrivateFieldGet(_currentRow, this)]);
    }
    _classPrivateFieldSet(_startOffset, this, relativeHeaderPosition.top - 6);
    _classPrivateFieldSet(_startHeight, this, parseInt(box.height, 10));
    _classPrivateFieldGet(_handle, this).style.top = `${_classPrivateFieldGet(_startOffset, this) + _classPrivateFieldGet(_startHeight, this)}px`;
    _classPrivateFieldGet(_handle, this).style[this.inlineDir] = `${relativeHeaderPosition.start}px`;
    _classPrivateFieldGet(_handle, this).style.width = `${headerWidth}px`;
    this.hot.rootElement.appendChild(_classPrivateFieldGet(_handle, this));
  }

  /**
   * Refresh the resize handle position.
   *
   * @private
   */
  refreshHandlePosition() {
    _classPrivateFieldGet(_handle, this).style.top = `${_classPrivateFieldGet(_startOffset, this) + _classPrivateFieldGet(_currentHeight, this)}px`;
  }

  /**
   * Sets the resize guide position.
   *
   * @private
   */
  setupGuidePosition() {
    const handleWidth = parseInt(outerWidth(_classPrivateFieldGet(_handle, this)), 10);
    const handleEndPosition = parseInt(_classPrivateFieldGet(_handle, this).style[this.inlineDir], 10) + handleWidth;
    const maximumVisibleElementWidth = parseInt(this.hot.view.maximumVisibleElementWidth(0), 10);
    addClass(_classPrivateFieldGet(_handle, this), 'active');
    addClass(_classPrivateFieldGet(_guide, this), 'active');
    _classPrivateFieldGet(_guide, this).style.top = _classPrivateFieldGet(_handle, this).style.top;
    _classPrivateFieldGet(_guide, this).style[this.inlineDir] = `${handleEndPosition}px`;
    _classPrivateFieldGet(_guide, this).style.width = `${maximumVisibleElementWidth - handleWidth}px`;
    this.hot.rootElement.appendChild(_classPrivateFieldGet(_guide, this));
  }

  /**
   * Refresh the resize guide position.
   *
   * @private
   */
  refreshGuidePosition() {
    _classPrivateFieldGet(_guide, this).style.top = _classPrivateFieldGet(_handle, this).style.top;
  }

  /**
   * Hides both the resize handle and resize guide.
   *
   * @private
   */
  hideHandleAndGuide() {
    removeClass(_classPrivateFieldGet(_handle, this), 'active');
    removeClass(_classPrivateFieldGet(_guide, this), 'active');
  }

  /**
   * Checks if provided element is considered as a row header.
   *
   * @private
   * @param {HTMLElement} element HTML element.
   * @returns {boolean}
   */
  checkIfRowHeader(element) {
    const tbody = closest(element, ['TBODY'], this.hot.rootElement);
    const {
      inlineStartOverlay,
      topInlineStartCornerOverlay,
      bottomInlineStartCornerOverlay
    } = this.hot.view._wt.wtOverlays;
    return [inlineStartOverlay.clone.wtTable.TBODY, topInlineStartCornerOverlay.clone.wtTable.TBODY, bottomInlineStartCornerOverlay.clone.wtTable.TBODY].includes(tbody);
  }

  /**
   * Gets the TH element from the provided element.
   *
   * @private
   * @param {HTMLElement} element HTML element.
   * @returns {HTMLElement}
   */
  getClosestTHParent(element) {
    if (element.tagName !== 'TABLE') {
      if (element.tagName === 'TH') {
        return element;
      }
      return this.getClosestTHParent(element.parentNode);
    }
    return null;
  }

  /**
   * Returns the actual height for the provided row index.
   *
   * @private
   * @param {number} row Visual row index.
   * @returns {number} Actual row height.
   */
  getActualRowHeight(row) {
    // TODO: this should utilize `this.hot.getRowHeight` after it's fixed and working properly.
    const walkontableHeight = this.hot.view._wt.wtTable.getRowHeight(row);
    if (walkontableHeight !== undefined && _classPrivateFieldGet(_newSize, this) < walkontableHeight) {
      return walkontableHeight;
    }
    return _classPrivateFieldGet(_newSize, this);
  }
  /**
   * Auto-size row after doubleclick - callback.
   *
   * @private
   * @fires Hooks#beforeRowResize
   * @fires Hooks#afterRowResize
   */
  afterMouseDownTimeout() {
    const render = () => {
      this.hot.forceFullRender = true;
      this.hot.view.render(); // updates all
      this.hot.view.adjustElementsSize();
    };
    const resize = (row, forceRender) => {
      const hookNewSize = this.hot.runHooks('beforeRowResize', this.getActualRowHeight(row), row, true);
      if (hookNewSize !== undefined) {
        _classPrivateFieldSet(_newSize, this, hookNewSize);
      }
      this.setManualSize(row, _classPrivateFieldGet(_newSize, this)); // double click sets auto row size

      this.hot.runHooks('afterRowResize', this.getActualRowHeight(row), row, true);
      if (forceRender) {
        render();
      }
    };
    if (_classPrivateFieldGet(_dblclick, this) >= 2) {
      const selectedRowsLength = _classPrivateFieldGet(_selectedRows, this).length;
      if (selectedRowsLength > 1) {
        arrayEach(_classPrivateFieldGet(_selectedRows, this), selectedRow => {
          resize(selectedRow);
        });
        render();
      } else {
        arrayEach(_classPrivateFieldGet(_selectedRows, this), selectedRow => {
          resize(selectedRow, true);
        });
      }
    }
    _classPrivateFieldSet(_dblclick, this, 0);
    _classPrivateFieldSet(_autoresizeTimeout, this, null);
  }

  /**
   * 'mousedown' event callback.
   *
   * @param {MouseEvent} event The mouse event.
   */

  /**
   * Binds the mouse events.
   *
   * @private
   */
  bindEvents() {
    const {
      rootElement,
      rootWindow
    } = this.hot;
    this.eventManager.addEventListener(rootElement, 'mouseover', e => _assertClassBrand(_ManualRowResize_brand, this, _onMouseOver).call(this, e));
    this.eventManager.addEventListener(rootElement, 'mousedown', e => _assertClassBrand(_ManualRowResize_brand, this, _onMouseDown).call(this, e));
    this.eventManager.addEventListener(rootWindow, 'mousemove', e => _assertClassBrand(_ManualRowResize_brand, this, _onMouseMove).call(this, e));
    this.eventManager.addEventListener(rootWindow, 'mouseup', () => _assertClassBrand(_ManualRowResize_brand, this, _onMouseUp).call(this));
    this.eventManager.addEventListener(_classPrivateFieldGet(_handle, this), 'contextmenu', () => _assertClassBrand(_ManualRowResize_brand, this, _onContextMenu).call(this));
  }

  /**
   * Modifies the provided row height, based on the plugin settings.
   *
   * @param {number} height Row height.
   * @param {number} row Visual row index.
   * @returns {number}
   */

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
function _onMouseOver(event) {
  // Workaround for #6926 - if the `event.target` is temporarily detached, we can skip this callback and wait for
  // the next `onmouseover`.
  if (isDetached(event.target)) {
    return;
  }

  // A "mouseover" action is triggered right after executing "contextmenu" event. It should be ignored.
  if (_classPrivateFieldGet(_isTriggeredByRMB, this) === true) {
    return;
  }
  if (this.checkIfRowHeader(event.target)) {
    const th = this.getClosestTHParent(event.target);
    if (th) {
      if (!_classPrivateFieldGet(_pressed, this)) {
        this.setupHandlePosition(th);
      }
    }
  }
}
function _onMouseDown(event) {
  if (hasClass(event.target, 'manualRowResizer')) {
    this.setupHandlePosition(_classPrivateFieldGet(_currentTH, this));
    this.setupGuidePosition();
    _classPrivateFieldSet(_pressed, this, true);
    if (_classPrivateFieldGet(_autoresizeTimeout, this) === null) {
      _classPrivateFieldSet(_autoresizeTimeout, this, setTimeout(() => this.afterMouseDownTimeout(), 500));
      this.hot._registerTimeout(_classPrivateFieldGet(_autoresizeTimeout, this));
    }
    _classPrivateFieldSet(_dblclick, this, _classPrivateFieldGet(_dblclick, this) + 1);
    _classPrivateFieldSet(_startY, this, event.pageY);
    _classPrivateFieldSet(_newSize, this, _classPrivateFieldGet(_startHeight, this));
  }
}
/**
 * 'mousemove' event callback - refresh the handle and guide positions, cache the new row height.
 *
 * @param {MouseEvent} event The mouse event.
 */
function _onMouseMove(event) {
  if (_classPrivateFieldGet(_pressed, this)) {
    _classPrivateFieldSet(_currentHeight, this, _classPrivateFieldGet(_startHeight, this) + (event.pageY - _classPrivateFieldGet(_startY, this)));
    arrayEach(_classPrivateFieldGet(_selectedRows, this), selectedRow => {
      _classPrivateFieldSet(_newSize, this, this.setManualSize(selectedRow, _classPrivateFieldGet(_currentHeight, this)));
    });
    this.refreshHandlePosition();
    this.refreshGuidePosition();
  }
}
/**
 * 'mouseup' event callback - apply the row resizing.
 *
 * @fires Hooks#beforeRowResize
 * @fires Hooks#afterRowResize
 */
function _onMouseUp() {
  const render = () => {
    this.hot.forceFullRender = true;
    this.hot.view.render(); // updates all
    this.hot.view.adjustElementsSize();
  };
  const runHooks = (row, forceRender) => {
    this.hot.runHooks('beforeRowResize', this.getActualRowHeight(row), row, false);
    if (forceRender) {
      render();
    }
    this.saveManualRowHeights();
    this.hot.runHooks('afterRowResize', this.getActualRowHeight(row), row, false);
  };
  if (_classPrivateFieldGet(_pressed, this)) {
    this.hideHandleAndGuide();
    _classPrivateFieldSet(_pressed, this, false);
    if (_classPrivateFieldGet(_newSize, this) !== _classPrivateFieldGet(_startHeight, this)) {
      const selectedRowsLength = _classPrivateFieldGet(_selectedRows, this).length;
      if (selectedRowsLength > 1) {
        arrayEach(_classPrivateFieldGet(_selectedRows, this), selectedRow => {
          runHooks(selectedRow);
        });
        render();
      } else {
        arrayEach(_classPrivateFieldGet(_selectedRows, this), selectedRow => {
          runHooks(selectedRow, true);
        });
      }
    }
    this.setupHandlePosition(_classPrivateFieldGet(_currentTH, this));
  }
}
/**
 * Callback for "contextmenu" event triggered on element showing move handle. It removes handle and guide elements.
 */
function _onContextMenu() {
  this.hideHandleAndGuide();
  this.hot.rootElement.removeChild(_classPrivateFieldGet(_handle, this));
  this.hot.rootElement.removeChild(_classPrivateFieldGet(_guide, this));
  _classPrivateFieldSet(_pressed, this, false);
  _classPrivateFieldSet(_isTriggeredByRMB, this, true);

  // There is thrown "mouseover" event right after opening a context menu. This flag inform that handle
  // shouldn't be drawn just after removing it.
  this.hot._registerImmediate(() => {
    _classPrivateFieldSet(_isTriggeredByRMB, this, false);
  });
}
function _onModifyRowHeight(height, row) {
  let newHeight = height;
  if (this.enabled) {
    const physicalRow = this.hot.toPhysicalRow(row);
    const rowHeight = _classPrivateFieldGet(_rowHeightsMap, this).getValueAtIndex(physicalRow);
    if (this.hot.getSettings()[PLUGIN_KEY] && rowHeight) {
      newHeight = rowHeight;
    }
  }
  return newHeight;
}
/**
 * Callback to call on map's `init` local hook.
 */
function _onMapInit() {
  const initialSetting = this.hot.getSettings()[PLUGIN_KEY];
  const loadedManualRowHeights = this.loadManualRowHeights();
  this.hot.batchExecution(() => {
    if (typeof loadedManualRowHeights !== 'undefined') {
      loadedManualRowHeights.forEach((height, index) => {
        _classPrivateFieldGet(_rowHeightsMap, this).setValueAtIndex(index, height);
      });
    } else if (Array.isArray(initialSetting)) {
      initialSetting.forEach((height, index) => {
        _classPrivateFieldGet(_rowHeightsMap, this).setValueAtIndex(index, height);
      });
      _classPrivateFieldSet(_config, this, initialSetting);
    } else if (initialSetting === true && Array.isArray(_classPrivateFieldGet(_config, this))) {
      _classPrivateFieldGet(_config, this).forEach((height, index) => {
        _classPrivateFieldGet(_rowHeightsMap, this).setValueAtIndex(index, height);
      });
    }
  }, true);
}