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
import { addClass, closest, hasClass, removeClass, outerHeight, isDetached } from "../../helpers/dom/element.mjs";
import { arrayEach } from "../../helpers/array.mjs";
import { rangeEach } from "../../helpers/number.mjs";
import { PhysicalIndexToValueMap as IndexToValueMap } from "../../translations/index.mjs"; // Developer note! Whenever you make a change in this file, make an analogous change in manualRowResize.js
export const PLUGIN_KEY = 'manualColumnResize';
export const PLUGIN_PRIORITY = 130;
const PERSISTENT_STATE_KEY = 'manualColumnWidths';

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin ManualColumnResize
 * @class ManualColumnResize
 *
 * @description
 * This plugin allows to change columns width. To make columns width persistent the {@link Options#persistentState}
 * plugin should be enabled.
 *
 * The plugin creates additional components to make resizing possibly using user interface:
 * - handle - the draggable element that sets the desired width of the column.
 * - guide - the helper guide that shows the desired width as a vertical guide.
 */
var _currentTH = /*#__PURE__*/new WeakMap();
var _currentCol = /*#__PURE__*/new WeakMap();
var _selectedCols = /*#__PURE__*/new WeakMap();
var _currentWidth = /*#__PURE__*/new WeakMap();
var _newSize = /*#__PURE__*/new WeakMap();
var _startY = /*#__PURE__*/new WeakMap();
var _startWidth = /*#__PURE__*/new WeakMap();
var _startOffset = /*#__PURE__*/new WeakMap();
var _handle = /*#__PURE__*/new WeakMap();
var _guide = /*#__PURE__*/new WeakMap();
var _pressed = /*#__PURE__*/new WeakMap();
var _isTriggeredByRMB = /*#__PURE__*/new WeakMap();
var _dblclick = /*#__PURE__*/new WeakMap();
var _autoresizeTimeout = /*#__PURE__*/new WeakMap();
var _columnWidthsMap = /*#__PURE__*/new WeakMap();
var _config = /*#__PURE__*/new WeakMap();
var _ManualColumnResize_brand = /*#__PURE__*/new WeakSet();
export class ManualColumnResize extends BasePlugin {
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }

  /**
   * @type {HTMLTableHeaderCellElement}
   */

  constructor(hotInstance) {
    super(hotInstance);
    /**
     * Callback to call on map's `init` local hook.
     *
     * @private
     */
    _classPrivateMethodInitSpec(this, _ManualColumnResize_brand);
    _classPrivateFieldInitSpec(this, _currentTH, null);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _currentCol, null);
    /**
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _selectedCols, []);
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _currentWidth, null);
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
    _classPrivateFieldInitSpec(this, _startWidth, null);
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
    _classPrivateFieldInitSpec(this, _pressed, null);
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
     * PhysicalIndexToValueMap to keep and track widths for physical column indexes.
     *
     * @type {PhysicalIndexToValueMap}
     */
    _classPrivateFieldInitSpec(this, _columnWidthsMap, void 0);
    /**
     * Private pool to save configuration from updateSettings.
     *
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _config, void 0);
    addClass(_classPrivateFieldGet(_handle, this), 'manualColumnResizer');
    addClass(_classPrivateFieldGet(_guide, this), 'manualColumnResizerGuide');
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
   * hook and if it returns `true` then the {@link ManualColumnResize#enablePlugin} method is called.
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
    var _this = this;
    if (this.enabled) {
      return;
    }
    _classPrivateFieldSet(_columnWidthsMap, this, new IndexToValueMap());
    _classPrivateFieldGet(_columnWidthsMap, this).addLocalHook('init', () => _assertClassBrand(_ManualColumnResize_brand, this, _onMapInit).call(this));
    this.hot.columnIndexMapper.registerMap(this.pluginName, _classPrivateFieldGet(_columnWidthsMap, this));
    this.addHook('modifyColWidth', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_ManualColumnResize_brand, _this, _onModifyColWidth).call(_this, ...args);
    }, 1);
    this.addHook('beforeStretchingColumnWidth', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_ManualColumnResize_brand, _this, _onBeforeStretchingColumnWidth).call(_this, ...args);
    }, 1);
    this.addHook('beforeColumnResize', function () {
      for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
        args[_key3] = arguments[_key3];
      }
      return _assertClassBrand(_ManualColumnResize_brand, _this, _onBeforeColumnResize).call(_this, ...args);
    });
    this.bindEvents();
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`manualColumnResize`](@/api/options.md#manualcolumnresize)
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
    _classPrivateFieldSet(_config, this, _classPrivateFieldGet(_columnWidthsMap, this).getValues());
    this.hot.columnIndexMapper.unregisterMap(this.pluginName);
    super.disablePlugin();
  }

  /**
   * Saves the current sizes using the persistentState plugin (the {@link Options#persistentState} option has to be enabled).
   *
   * @fires Hooks#persistentStateSave
   */
  saveManualColumnWidths() {
    this.hot.runHooks('persistentStateSave', PERSISTENT_STATE_KEY, _classPrivateFieldGet(_columnWidthsMap, this).getValues());
  }

  /**
   * Loads the previously saved sizes using the persistentState plugin (the {@link Options#persistentState} option has to be enabled).
   *
   * @returns {Array}
   * @fires Hooks#persistentStateLoad
   */
  loadManualColumnWidths() {
    const storedState = {};
    this.hot.runHooks('persistentStateLoad', PERSISTENT_STATE_KEY, storedState);
    return storedState.value;
  }

  /**
   * Sets the new width for specified column index.
   *
   * @param {number} column Visual column index.
   * @param {number} width Column width (no less than 20px).
   * @returns {number} Returns new width.
   */
  setManualSize(column, width) {
    const newWidth = Math.max(width, 20);
    const physicalColumn = this.hot.toPhysicalColumn(column);
    _classPrivateFieldGet(_columnWidthsMap, this).setValueAtIndex(physicalColumn, newWidth);
    return newWidth;
  }

  /**
   * Clears the cache for the specified column index.
   *
   * @param {number} column Visual column index.
   */
  clearManualSize(column) {
    const physicalColumn = this.hot.toPhysicalColumn(column);
    _classPrivateFieldGet(_columnWidthsMap, this).setValueAtIndex(physicalColumn, null);
  }
  /**
   * Set the resize handle position.
   *
   * @private
   * @param {HTMLCellElement} TH TH HTML element.
   */
  setupHandlePosition(TH) {
    if (!TH.parentNode) {
      return;
    }
    _classPrivateFieldSet(_currentTH, this, TH);
    const {
      _wt: wt
    } = this.hot.view;
    const cellCoords = wt.wtTable.getCoords(_classPrivateFieldGet(_currentTH, this));
    const col = cellCoords.col;

    // Ignore column headers.
    if (col < 0) {
      return;
    }
    const headerHeight = outerHeight(_classPrivateFieldGet(_currentTH, this));
    const box = _classPrivateFieldGet(_currentTH, this).getBoundingClientRect();
    // Read "fixedColumnsStart" through the Walkontable as in that context, the fixed columns
    // are modified (reduced by the number of hidden columns) by TableView module.
    const fixedColumn = col < wt.getSetting('fixedColumnsStart');
    let relativeHeaderPosition;
    if (fixedColumn) {
      relativeHeaderPosition = wt.wtOverlays.topInlineStartCornerOverlay.getRelativeCellPosition(_classPrivateFieldGet(_currentTH, this), cellCoords.row, cellCoords.col);
    }

    // If the TH is not a child of the top-left overlay, recalculate using
    // the top overlay - as this overlay contains the rest of the headers.
    if (!relativeHeaderPosition) {
      relativeHeaderPosition = wt.wtOverlays.topOverlay.getRelativeCellPosition(_classPrivateFieldGet(_currentTH, this), cellCoords.row, cellCoords.col);
    }
    _classPrivateFieldSet(_currentCol, this, this.hot.columnIndexMapper.getVisualFromRenderableIndex(col));
    _classPrivateFieldSet(_selectedCols, this, []);
    const isFullColumnSelected = this.hot.selection.isSelectedByCorner() || this.hot.selection.isSelectedByColumnHeader();
    if (this.hot.selection.isSelected() && isFullColumnSelected) {
      const selectionRanges = this.hot.getSelectedRange();
      arrayEach(selectionRanges, selectionRange => {
        const fromColumn = selectionRange.getTopStartCorner().col;
        const toColumn = selectionRange.getBottomEndCorner().col;

        // Add every selected column for resize action.
        rangeEach(fromColumn, toColumn, columnIndex => {
          if (!_classPrivateFieldGet(_selectedCols, this).includes(columnIndex)) {
            _classPrivateFieldGet(_selectedCols, this).push(columnIndex);
          }
        });
      });
    }

    // Resizing element beyond the current selection (also when there is no selection).
    if (!_classPrivateFieldGet(_selectedCols, this).includes(_classPrivateFieldGet(_currentCol, this))) {
      _classPrivateFieldSet(_selectedCols, this, [_classPrivateFieldGet(_currentCol, this)]);
    }
    _classPrivateFieldSet(_startOffset, this, relativeHeaderPosition.start - 6);
    _classPrivateFieldSet(_startWidth, this, parseInt(box.width, 10));
    _classPrivateFieldGet(_handle, this).style.top = `${relativeHeaderPosition.top}px`;
    _classPrivateFieldGet(_handle, this).style[this.inlineDir] = `${_classPrivateFieldGet(_startOffset, this) + _classPrivateFieldGet(_startWidth, this)}px`;
    _classPrivateFieldGet(_handle, this).style.height = `${headerHeight}px`;
    this.hot.rootElement.appendChild(_classPrivateFieldGet(_handle, this));
  }

  /**
   * Refresh the resize handle position.
   *
   * @private
   */
  refreshHandlePosition() {
    _classPrivateFieldGet(_handle, this).style[this.inlineDir] = `${_classPrivateFieldGet(_startOffset, this) + _classPrivateFieldGet(_currentWidth, this)}px`;
  }

  /**
   * Sets the resize guide position.
   *
   * @private
   */
  setupGuidePosition() {
    const handleHeight = parseInt(outerHeight(_classPrivateFieldGet(_handle, this)), 10);
    const handleBottomPosition = parseInt(_classPrivateFieldGet(_handle, this).style.top, 10) + handleHeight;
    const maximumVisibleElementHeight = parseInt(this.hot.view.maximumVisibleElementHeight(0), 10);
    addClass(_classPrivateFieldGet(_handle, this), 'active');
    addClass(_classPrivateFieldGet(_guide, this), 'active');
    _classPrivateFieldGet(_guide, this).style.top = `${handleBottomPosition}px`;
    this.refreshGuidePosition();
    _classPrivateFieldGet(_guide, this).style.height = `${maximumVisibleElementHeight - handleHeight}px`;
    this.hot.rootElement.appendChild(_classPrivateFieldGet(_guide, this));
  }

  /**
   * Refresh the resize guide position.
   *
   * @private
   */
  refreshGuidePosition() {
    _classPrivateFieldGet(_guide, this).style[this.inlineDir] = _classPrivateFieldGet(_handle, this).style[this.inlineDir];
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
   * Checks if provided element is considered a column header.
   *
   * @private
   * @param {HTMLElement} element HTML element.
   * @returns {boolean}
   */
  checkIfColumnHeader(element) {
    const thead = closest(element, ['THEAD'], this.hot.rootElement);
    const {
      topOverlay,
      topInlineStartCornerOverlay
    } = this.hot.view._wt.wtOverlays;
    return [topOverlay.clone.wtTable.THEAD, topInlineStartCornerOverlay.clone.wtTable.THEAD].includes(thead);
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
   * 'mouseover' event callback - set the handle position.
   *
   * @param {MouseEvent} event The mouse event.
   */

  /**
   * Auto-size row after doubleclick - callback.
   *
   * @private
   * @fires Hooks#beforeColumnResize
   * @fires Hooks#afterColumnResize
   */
  afterMouseDownTimeout() {
    const render = () => {
      this.hot.forceFullRender = true;
      this.hot.view.render(); // updates all
      this.hot.view.adjustElementsSize();
    };
    const resize = (column, forceRender) => {
      const hookNewSize = this.hot.runHooks('beforeColumnResize', _classPrivateFieldGet(_newSize, this), column, true);
      if (hookNewSize !== undefined) {
        _classPrivateFieldSet(_newSize, this, hookNewSize);
      }
      this.setManualSize(column, _classPrivateFieldGet(_newSize, this)); // double click sets by auto row size plugin
      this.saveManualColumnWidths();
      this.hot.runHooks('afterColumnResize', _classPrivateFieldGet(_newSize, this), column, true);
      if (forceRender) {
        render();
      }
    };
    if (_classPrivateFieldGet(_dblclick, this) >= 2) {
      const selectedColsLength = _classPrivateFieldGet(_selectedCols, this).length;
      if (selectedColsLength > 1) {
        arrayEach(_classPrivateFieldGet(_selectedCols, this), selectedCol => {
          resize(selectedCol);
        });
        render();
      } else {
        arrayEach(_classPrivateFieldGet(_selectedCols, this), selectedCol => {
          resize(selectedCol, true);
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
      rootWindow,
      rootElement
    } = this.hot;
    this.eventManager.addEventListener(rootElement, 'mouseover', e => _assertClassBrand(_ManualColumnResize_brand, this, _onMouseOver).call(this, e));
    this.eventManager.addEventListener(rootElement, 'mousedown', e => _assertClassBrand(_ManualColumnResize_brand, this, _onMouseDown).call(this, e));
    this.eventManager.addEventListener(rootWindow, 'mousemove', e => _assertClassBrand(_ManualColumnResize_brand, this, _onMouseMove).call(this, e));
    this.eventManager.addEventListener(rootWindow, 'mouseup', () => _assertClassBrand(_ManualColumnResize_brand, this, _onMouseUp).call(this));
    this.eventManager.addEventListener(_classPrivateFieldGet(_handle, this), 'contextmenu', () => _assertClassBrand(_ManualColumnResize_brand, this, _onContextMenu).call(this));
  }

  /**
   * Modifies the provided column width, based on the plugin settings.
   *
   * @param {number} width Column width.
   * @param {number} column Visual column index.
   * @returns {number}
   */

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
function _onMapInit() {
  const initialSetting = this.hot.getSettings()[PLUGIN_KEY];
  const loadedManualColumnWidths = this.loadManualColumnWidths();
  if (typeof loadedManualColumnWidths !== 'undefined') {
    this.hot.batchExecution(() => {
      loadedManualColumnWidths.forEach((width, physicalIndex) => {
        _classPrivateFieldGet(_columnWidthsMap, this).setValueAtIndex(physicalIndex, width);
      });
    }, true);
  } else if (Array.isArray(initialSetting)) {
    this.hot.batchExecution(() => {
      initialSetting.forEach((width, physicalIndex) => {
        _classPrivateFieldGet(_columnWidthsMap, this).setValueAtIndex(physicalIndex, width);
      });
    }, true);
    _classPrivateFieldSet(_config, this, initialSetting);
  } else if (initialSetting === true && Array.isArray(_classPrivateFieldGet(_config, this))) {
    this.hot.batchExecution(() => {
      _classPrivateFieldGet(_config, this).forEach((width, physicalIndex) => {
        _classPrivateFieldGet(_columnWidthsMap, this).setValueAtIndex(physicalIndex, width);
      });
    }, true);
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
  if (this.checkIfColumnHeader(event.target)) {
    const th = this.getClosestTHParent(event.target);
    if (!th) {
      return;
    }
    const colspan = th.getAttribute('colspan');
    if (th && (colspan === null || colspan === '1')) {
      if (!_classPrivateFieldGet(_pressed, this)) {
        this.setupHandlePosition(th);
      }
    }
  }
}
function _onMouseDown(event) {
  if (event.target.parentNode !== this.hot.rootElement) {
    return;
  }
  if (hasClass(event.target, 'manualColumnResizer')) {
    this.setupHandlePosition(_classPrivateFieldGet(_currentTH, this));
    this.setupGuidePosition();
    _classPrivateFieldSet(_pressed, this, true);
    if (_classPrivateFieldGet(_autoresizeTimeout, this) === null) {
      _classPrivateFieldSet(_autoresizeTimeout, this, setTimeout(() => this.afterMouseDownTimeout(), 500));
      this.hot._registerTimeout(_classPrivateFieldGet(_autoresizeTimeout, this));
    }
    _classPrivateFieldSet(_dblclick, this, _classPrivateFieldGet(_dblclick, this) + 1);
    this.startX = event.pageX;
    _classPrivateFieldSet(_newSize, this, _classPrivateFieldGet(_startWidth, this));
  }
}
/**
 * 'mousemove' event callback - refresh the handle and guide positions, cache the new column width.
 *
 * @param {MouseEvent} event The mouse event.
 */
function _onMouseMove(event) {
  if (_classPrivateFieldGet(_pressed, this)) {
    const change = (event.pageX - this.startX) * this.hot.getDirectionFactor();
    _classPrivateFieldSet(_currentWidth, this, _classPrivateFieldGet(_startWidth, this) + change);
    arrayEach(_classPrivateFieldGet(_selectedCols, this), selectedCol => {
      _classPrivateFieldSet(_newSize, this, this.setManualSize(selectedCol, _classPrivateFieldGet(_currentWidth, this)));
    });
    this.refreshHandlePosition();
    this.refreshGuidePosition();
  }
}
/**
 * 'mouseup' event callback - apply the column resizing.
 *
 * @fires Hooks#beforeColumnResize
 * @fires Hooks#afterColumnResize
 */
function _onMouseUp() {
  const render = () => {
    this.hot.forceFullRender = true;
    this.hot.view.render(); // updates all
    this.hot.view.adjustElementsSize();
  };
  const resize = (column, forceRender) => {
    this.hot.runHooks('beforeColumnResize', _classPrivateFieldGet(_newSize, this), column, false);
    if (forceRender) {
      render();
    }
    this.saveManualColumnWidths();
    this.hot.runHooks('afterColumnResize', _classPrivateFieldGet(_newSize, this), column, false);
  };
  if (_classPrivateFieldGet(_pressed, this)) {
    this.hideHandleAndGuide();
    _classPrivateFieldSet(_pressed, this, false);
    if (_classPrivateFieldGet(_newSize, this) !== _classPrivateFieldGet(_startWidth, this)) {
      const selectedColsLength = _classPrivateFieldGet(_selectedCols, this).length;
      if (selectedColsLength > 1) {
        arrayEach(_classPrivateFieldGet(_selectedCols, this), selectedCol => {
          resize(selectedCol);
        });
        render();
      } else {
        arrayEach(_classPrivateFieldGet(_selectedCols, this), selectedCol => {
          resize(selectedCol, true);
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
function _onModifyColWidth(width, column) {
  let newWidth = width;
  if (this.enabled) {
    const physicalColumn = this.hot.toPhysicalColumn(column);
    const columnWidth = _classPrivateFieldGet(_columnWidthsMap, this).getValueAtIndex(physicalColumn);
    if (this.hot.getSettings()[PLUGIN_KEY] && columnWidth) {
      newWidth = columnWidth;
    }
  }
  return newWidth;
}
/**
 * Modifies the provided column stretched width. This hook decides if specified column should be stretched or not.
 *
 * @param {number} stretchedWidth Stretched width.
 * @param {number} column Visual column index.
 * @returns {number}
 */
function _onBeforeStretchingColumnWidth(stretchedWidth, column) {
  const width = _classPrivateFieldGet(_columnWidthsMap, this).getValueAtIndex(this.hot.toPhysicalColumn(column));
  if (typeof width === 'number') {
    return width;
  }
  return stretchedWidth;
}
/**
 * `beforeColumnResize` hook callback.
 */
function _onBeforeColumnResize() {
  // clear the header height cache information
  this.hot.view._wt.wtViewport.resetHasOversizedColumnHeadersMarked();
}