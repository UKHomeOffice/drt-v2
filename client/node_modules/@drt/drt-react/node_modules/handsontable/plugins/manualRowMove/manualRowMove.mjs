import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.some.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { BasePlugin } from "../base/index.mjs";
import { Hooks } from "../../core/hooks/index.mjs";
import { arrayReduce } from "../../helpers/array.mjs";
import { addClass, removeClass, offset, getTrimmingContainer } from "../../helpers/dom/element.mjs";
import { rangeEach } from "../../helpers/number.mjs";
import BacklightUI from "./ui/backlight.mjs";
import GuidelineUI from "./ui/guideline.mjs";
Hooks.getSingleton().register('beforeRowMove');
Hooks.getSingleton().register('afterRowMove');
export const PLUGIN_KEY = 'manualRowMove';
export const PLUGIN_PRIORITY = 140;
const CSS_PLUGIN = 'ht__manualRowMove';
const CSS_SHOW_UI = 'show-ui';
const CSS_ON_MOVING = 'on-moving--rows';
const CSS_AFTER_SELECTION = 'after-selection--rows';

/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * @plugin ManualRowMove
 * @class ManualRowMove
 *
 * @description
 * This plugin allows to change rows order. To make rows order persistent the {@link Options#persistentState}
 * plugin should be enabled.
 *
 * API:
 * - `moveRow` - move single row to the new position.
 * - `moveRows` - move many rows (as an array of indexes) to the new position.
 * - `dragRow` - drag single row to the new position.
 * - `dragRows` - drag many rows (as an array of indexes) to the new position.
 *
 * [Documentation](@/guides/rows/row-moving/row-moving.md) explain differences between drag and move actions. Please keep in mind that if you want apply visual changes,
 * you have to call manually the `render` method on the instance of Handsontable.
 *
 * The plugin creates additional components to make moving possibly using user interface:
 * - backlight - highlight of selected rows.
 * - guideline - line which shows where rows has been moved.
 *
 * @class ManualRowMove
 * @plugin ManualRowMove
 */
var _backlight = /*#__PURE__*/new WeakMap();
var _guideline = /*#__PURE__*/new WeakMap();
var _rowsToMove = /*#__PURE__*/new WeakMap();
var _pressed = /*#__PURE__*/new WeakMap();
var _target = /*#__PURE__*/new WeakMap();
var _cachedDropIndex = /*#__PURE__*/new WeakMap();
var _ManualRowMove_brand = /*#__PURE__*/new WeakSet();
export class ManualRowMove extends BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Change the behavior of selection / dragging.
     *
     * @param {MouseEvent} event `mousedown` event properties.
     * @param {CellCoords} coords Visual cell coordinates where was fired event.
     * @param {HTMLElement} TD Cell represented as HTMLElement.
     * @param {object} controller An object with properties `row`, `column` and `cell`. Each property contains
     *                            a boolean value that allows or disallows changing the selection for that particular area.
     */
    _classPrivateMethodInitSpec(this, _ManualRowMove_brand);
    /**
     * Backlight UI object.
     *
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _backlight, new BacklightUI(this.hot));
    /**
     * Guideline UI object.
     *
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _guideline, new GuidelineUI(this.hot));
    /**
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _rowsToMove, []);
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _pressed, void 0);
    /**
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _target, {});
    /**
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _cachedDropIndex, void 0);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` then the {@link ManualRowMove#enablePlugin} method is called.
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
    this.addHook('beforeOnCellMouseDown', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_ManualRowMove_brand, _this, _onBeforeOnCellMouseDown).call(_this, ...args);
    });
    this.addHook('beforeOnCellMouseOver', function () {
      for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        args[_key2] = arguments[_key2];
      }
      return _assertClassBrand(_ManualRowMove_brand, _this, _onBeforeOnCellMouseOver).call(_this, ...args);
    });
    this.addHook('afterScrollHorizontally', () => _assertClassBrand(_ManualRowMove_brand, this, _onAfterScrollHorizontally).call(this));
    this.addHook('afterLoadData', function () {
      for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
        args[_key3] = arguments[_key3];
      }
      return _assertClassBrand(_ManualRowMove_brand, _this, _onAfterLoadData).call(_this, ...args);
    });
    this.buildPluginUI();
    this.registerEvents();

    // TODO: move adding plugin classname to BasePlugin.
    addClass(this.hot.rootElement, CSS_PLUGIN);
    super.enablePlugin();
  }

  /**
   * Updates the plugin's state.
   *
   * This method is executed when [`updateSettings()`](@/api/core.md#updatesettings) is invoked with any of the following configuration options:
   *  - [`manualRowMove`](@/api/options.md#manualrowmove)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    this.moveBySettingsOrLoad();
    super.updatePlugin();
  }

  /**
   * Disables the plugin functionality for this Handsontable instance.
   */
  disablePlugin() {
    removeClass(this.hot.rootElement, CSS_PLUGIN);
    this.unregisterEvents();
    _classPrivateFieldGet(_backlight, this).destroy();
    _classPrivateFieldGet(_guideline, this).destroy();
    super.disablePlugin();
  }

  /**
   * Moves a single row.
   *
   * To see the outcome, rerender your grid by calling [`render()`](@/api/core.md#render).
   *
   * @param {number} row Visual row index to be moved.
   * @param {number} finalIndex Visual row index, being a start index for the moved rows. Points to where the elements will be placed after the moving action.
   * To check the visualization of the final index, please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @fires Hooks#beforeRowMove
   * @fires Hooks#afterRowMove
   * @returns {boolean}
   */
  moveRow(row, finalIndex) {
    return this.moveRows([row], finalIndex);
  }

  /**
   * Moves multiple rows.
   *
   * To see the outcome, rerender your grid by calling [`render()`](@/api/core.md#render).
   *
   * @param {Array} rows Array of visual row indexes to be moved.
   * @param {number} finalIndex Visual row index, being a start index for the moved rows. Points to where the elements will be placed after the moving action.
   * To check the visualization of the final index, please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @fires Hooks#beforeRowMove
   * @fires Hooks#afterRowMove
   * @returns {boolean}
   */
  moveRows(rows, finalIndex) {
    const dropIndex = _classPrivateFieldGet(_cachedDropIndex, this);
    const movePossible = this.isMovePossible(rows, finalIndex);
    const beforeMoveHook = this.hot.runHooks('beforeRowMove', rows, finalIndex, dropIndex, movePossible);
    _classPrivateFieldSet(_cachedDropIndex, this, undefined);
    if (beforeMoveHook === false) {
      return;
    }
    if (movePossible) {
      this.hot.rowIndexMapper.moveIndexes(rows, finalIndex);
    }
    const movePerformed = movePossible && this.isRowOrderChanged(rows, finalIndex);
    this.hot.runHooks('afterRowMove', rows, finalIndex, dropIndex, movePossible, movePerformed);
    return movePerformed;
  }

  /**
   * Drag a single row to drop index position.
   *
   * @param {number} row Visual row index to be dragged.
   * @param {number} dropIndex Visual row index, being a drop index for the moved rows. Points to where we are going to drop the moved elements.
   * To check visualization of drop index please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @fires Hooks#beforeRowMove
   * @fires Hooks#afterRowMove
   * @returns {boolean}
   */
  dragRow(row, dropIndex) {
    return this.dragRows([row], dropIndex);
  }

  /**
   * Drag multiple rows to drop index position.
   *
   * @param {Array} rows Array of visual row indexes to be dragged.
   * @param {number} dropIndex Visual row index, being a drop index for the moved rows. Points to where we are going to drop the moved elements.
   * To check visualization of drop index please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @fires Hooks#beforeRowMove
   * @fires Hooks#afterRowMove
   * @returns {boolean}
   */
  dragRows(rows, dropIndex) {
    const finalIndex = this.countFinalIndex(rows, dropIndex);
    _classPrivateFieldSet(_cachedDropIndex, this, dropIndex);
    return this.moveRows(rows, finalIndex);
  }

  /**
   * Indicates if it's possible to move rows to the desired position. Some of the actions aren't possible, i.e. You can’t move more than one element to the last position.
   *
   * @param {Array} movedRows Array of visual row indexes to be moved.
   * @param {number} finalIndex Visual row index, being a start index for the moved rows. Points to where the elements will be placed after the moving action.
   * To check the visualization of the final index, please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @returns {boolean}
   */
  isMovePossible(movedRows, finalIndex) {
    const length = this.hot.rowIndexMapper.getNotTrimmedIndexesLength();

    // An attempt to transfer more rows to start destination than is possible (only when moving from the top to the bottom).
    const tooHighDestinationIndex = movedRows.length + finalIndex > length;
    const tooLowDestinationIndex = finalIndex < 0;
    const tooLowMovedRowIndex = movedRows.some(movedRow => movedRow < 0);
    const tooHighMovedRowIndex = movedRows.some(movedRow => movedRow >= length);
    if (tooHighDestinationIndex || tooLowDestinationIndex || tooLowMovedRowIndex || tooHighMovedRowIndex) {
      return false;
    }
    return true;
  }

  /**
   * Indicates if order of rows was changed.
   *
   * @private
   * @param {Array} movedRows Array of visual row indexes to be moved.
   * @param {number} finalIndex Visual row index, being a start index for the moved rows. Points to where the elements will be placed after the moving action.
   * To check the visualization of the final index, please take a look at [documentation](@/guides/rows/row-moving/row-moving.md#drag-and-move-actions-of-manualrowmove-plugin).
   * @returns {boolean}
   */
  isRowOrderChanged(movedRows, finalIndex) {
    return movedRows.some((row, nrOfMovedElement) => row - nrOfMovedElement !== finalIndex);
  }

  /**
   * Count the final row index from the drop index.
   *
   * @private
   * @param {Array} movedRows Array of visual row indexes to be moved.
   * @param {number} dropIndex Visual row index, being a drop index for the moved rows.
   * @returns {number} Visual row index, being a start index for the moved rows.
   */
  countFinalIndex(movedRows, dropIndex) {
    const numberOfRowsLowerThanDropIndex = arrayReduce(movedRows, (numberOfRows, currentRowIndex) => {
      if (currentRowIndex < dropIndex) {
        numberOfRows += 1;
      }
      return numberOfRows;
    }, 0);
    return dropIndex - numberOfRowsLowerThanDropIndex;
  }

  /**
   * Gets the sum of the heights of rows in the provided range.
   *
   * @private
   * @param {number} fromRow Visual row index.
   * @param {number} toRow Visual row index.
   * @returns {number}
   */
  getRowsHeight(fromRow, toRow) {
    const rowMapper = this.hot.rowIndexMapper;
    let rowsHeight = 0;
    for (let visualRowIndex = fromRow; visualRowIndex <= toRow; visualRowIndex++) {
      const renderableIndex = rowMapper.getRenderableFromVisualIndex(visualRowIndex);
      if (renderableIndex !== null) {
        rowsHeight += this.hot.view._wt.wtTable.getRowHeight(renderableIndex) || this.hot.view.getDefaultRowHeight();
      }
    }
    return rowsHeight;
  }

  /**
   * Loads initial settings when persistent state is saved or when plugin was initialized as an array.
   *
   * @private
   */
  moveBySettingsOrLoad() {
    const pluginSettings = this.hot.getSettings()[PLUGIN_KEY];
    if (Array.isArray(pluginSettings)) {
      this.moveRows(pluginSettings, 0);
    } else if (pluginSettings !== undefined) {
      const persistentState = this.persistentStateLoad();
      if (persistentState.length) {
        this.moveRows(persistentState, 0);
      }
    }
  }

  /**
   * Checks if the provided row is in the fixedRowsTop section.
   *
   * @private
   * @param {number} row Visual row index to check.
   * @returns {boolean}
   */
  isFixedRowTop(row) {
    return row < this.hot.getSettings().fixedRowsTop;
  }

  /**
   * Checks if the provided row is in the fixedRowsBottom section.
   *
   * @private
   * @param {number} row Visual row index to check.
   * @returns {boolean}
   */
  isFixedRowBottom(row) {
    return row > this.hot.countRows() - 1 - this.hot.getSettings().fixedRowsBottom;
  }

  /**
   * Saves the manual row positions to the persistent state (the {@link Options#persistentState} option has to be enabled).
   *
   * @private
   * @fires Hooks#persistentStateSave
   */
  persistentStateSave() {
    // The `PersistentState` plugin should be refactored.
    this.hot.runHooks('persistentStateSave', 'manualRowMove', this.hot.rowIndexMapper.getIndexesSequence());
  }

  /**
   * Loads the manual row positions from the persistent state (the {@link Options#persistentState} option has to be enabled).
   *
   * @private
   * @fires Hooks#persistentStateLoad
   * @returns {Array} Stored state.
   */
  persistentStateLoad() {
    const storedState = {};
    this.hot.runHooks('persistentStateLoad', 'manualRowMove', storedState);
    return storedState.value ? storedState.value : [];
  }

  /**
   * Prepares an array of indexes based on actual selection.
   *
   * @private
   * @returns {Array}
   */
  prepareRowsToMoving() {
    const selection = this.hot.getSelectedRangeLast();
    const selectedRows = [];
    if (!selection) {
      return selectedRows;
    }
    const {
      from,
      to
    } = selection;
    const start = Math.min(from.row, to.row);
    const end = Math.max(from.row, to.row);
    rangeEach(start, end, i => {
      selectedRows.push(i);
    });
    return selectedRows;
  }

  /**
   * Update the UI visual position.
   *
   * @private
   */
  refreshPositions() {
    const coords = _classPrivateFieldGet(_target, this).coords;
    const firstVisible = this.hot.getFirstFullyVisibleRow();
    const lastVisible = this.hot.getLastFullyVisibleRow();
    const countRows = this.hot.countRows();
    if (this.isFixedRowTop(coords.row) && firstVisible > 0) {
      this.hot.scrollViewportTo(this.hot.rowIndexMapper.getNearestNotHiddenIndex(firstVisible - 1, -1));
    }
    if (this.isFixedRowBottom(coords.row) && lastVisible < countRows) {
      this.hot.scrollViewportTo(this.hot.rowIndexMapper.getNearestNotHiddenIndex(lastVisible + 1, 1), undefined, true);
    }
    const wtTable = this.hot.view._wt.wtTable;
    const TD = _classPrivateFieldGet(_target, this).TD;
    const rootElement = this.hot.rootElement;
    const rootElementOffset = offset(rootElement);
    const trimmingContainer = getTrimmingContainer(rootElement);
    const tableScroll = wtTable.holder.scrollTop;
    const trimmingContainerScroll = this.hot.rootWindow !== trimmingContainer ? trimmingContainer.scrollTop : 0;
    const pixelsAbove = rootElementOffset.top - trimmingContainerScroll;
    const pixelsRelToTableStart = _classPrivateFieldGet(_target, this).eventPageY - pixelsAbove + tableScroll;
    const hiderHeight = wtTable.hider.offsetHeight;
    const tbodyOffsetTop = wtTable.TBODY.offsetTop;
    const backlightElemMarginTop = _classPrivateFieldGet(_backlight, this).getOffset().top;
    const backlightElemHeight = _classPrivateFieldGet(_backlight, this).getSize().height;
    const tdMiddle = TD.offsetHeight / 2;
    const tdHeight = TD.offsetHeight;
    let tdStartPixel = this.hot.view.THEAD.offsetHeight + this.getRowsHeight(0, coords.row - 1);
    const isBelowTable = pixelsRelToTableStart >= tdStartPixel + tdMiddle;
    if (this.isFixedRowTop(coords.row)) {
      tdStartPixel += this.hot.view._wt.wtOverlays.topOverlay.getOverlayOffset();
    }
    if (coords.row < 0) {
      // if hover on colHeader
      _classPrivateFieldGet(_target, this).row = firstVisible > 0 ? firstVisible - 1 : firstVisible;
    } else if (isBelowTable) {
      // if hover on lower part of TD
      _classPrivateFieldGet(_target, this).row = coords.row + 1;
      // unfortunately first row is bigger than rest
      tdStartPixel += coords.row === 0 ? tdHeight - 1 : tdHeight;
    } else {
      // elsewhere on table
      _classPrivateFieldGet(_target, this).row = coords.row;
    }
    let backlightTop = pixelsRelToTableStart;
    let guidelineTop = tdStartPixel;
    if (pixelsRelToTableStart + backlightElemHeight + backlightElemMarginTop >= hiderHeight) {
      // prevent display backlight below table
      backlightTop = hiderHeight - backlightElemHeight - backlightElemMarginTop;
    } else if (pixelsRelToTableStart + backlightElemMarginTop < tbodyOffsetTop) {
      // prevent display above below table
      backlightTop = tbodyOffsetTop + Math.abs(backlightElemMarginTop);
    }
    if (tdStartPixel >= hiderHeight - 1) {
      // prevent display guideline below table
      guidelineTop = hiderHeight - 1;
    }
    _classPrivateFieldGet(_backlight, this).setPosition(backlightTop);
    _classPrivateFieldGet(_guideline, this).setPosition(guidelineTop);
  }

  /**
   * Binds the events used by the plugin.
   *
   * @private
   */
  registerEvents() {
    const {
      documentElement
    } = this.hot.rootDocument;
    this.eventManager.addEventListener(documentElement, 'mousemove', event => _assertClassBrand(_ManualRowMove_brand, this, _onMouseMove).call(this, event));
    this.eventManager.addEventListener(documentElement, 'mouseup', () => _assertClassBrand(_ManualRowMove_brand, this, _onMouseUp).call(this));
  }

  /**
   * Unbinds the events used by the plugin.
   *
   * @private
   */
  unregisterEvents() {
    this.eventManager.clear();
  }
  /**
   * Builds the plugin's UI.
   *
   * @private
   */
  buildPluginUI() {
    _classPrivateFieldGet(_backlight, this).build();
    _classPrivateFieldGet(_guideline, this).build();
  }

  /**
   * Callback for the `afterLoadData` hook.
   */

  /**
   * Destroys the plugin instance.
   */
  destroy() {
    _classPrivateFieldGet(_backlight, this).destroy();
    _classPrivateFieldGet(_guideline, this).destroy();
    super.destroy();
  }
}
function _onBeforeOnCellMouseDown(event, coords, TD, controller) {
  const {
    wtTable,
    wtViewport
  } = this.hot.view._wt;
  const isHeaderSelection = this.hot.selection.isSelectedByRowHeader();
  const selection = this.hot.getSelectedRangeLast();
  if (!selection || !isHeaderSelection || _classPrivateFieldGet(_pressed, this) || event.button !== 0) {
    _classPrivateFieldSet(_pressed, this, false);
    _classPrivateFieldGet(_rowsToMove, this).length = 0;
    removeClass(this.hot.rootElement, [CSS_ON_MOVING, CSS_SHOW_UI]);
    return;
  }
  const guidelineIsNotReady = _classPrivateFieldGet(_guideline, this).isBuilt() && !_classPrivateFieldGet(_guideline, this).isAppended();
  const backlightIsNotReady = _classPrivateFieldGet(_backlight, this).isBuilt() && !_classPrivateFieldGet(_backlight, this).isAppended();
  if (guidelineIsNotReady && backlightIsNotReady) {
    _classPrivateFieldGet(_guideline, this).appendTo(wtTable.hider);
    _classPrivateFieldGet(_backlight, this).appendTo(wtTable.hider);
  }
  const {
    from,
    to
  } = selection;
  const start = Math.min(from.row, to.row);
  const end = Math.max(from.row, to.row);
  if (coords.col < 0 && coords.row >= start && coords.row <= end) {
    controller.row = true;
    _classPrivateFieldSet(_pressed, this, true);
    _classPrivateFieldGet(_target, this).eventPageY = event.pageY;
    _classPrivateFieldGet(_target, this).coords = coords;
    _classPrivateFieldGet(_target, this).TD = TD;
    _classPrivateFieldSet(_rowsToMove, this, this.prepareRowsToMoving());
    const leftPos = wtTable.holder.scrollLeft + wtViewport.getRowHeaderWidth();
    const topOffset = this.getRowsHeight(start, coords.row - 1) + (event.clientY - TD.getBoundingClientRect().top);
    _classPrivateFieldGet(_backlight, this).setPosition(null, leftPos);
    _classPrivateFieldGet(_backlight, this).setSize(wtTable.hider.offsetWidth - leftPos, this.getRowsHeight(start, end));
    _classPrivateFieldGet(_backlight, this).setOffset(-topOffset, null);
    addClass(this.hot.rootElement, CSS_ON_MOVING);
    this.refreshPositions();
  } else {
    removeClass(this.hot.rootElement, CSS_AFTER_SELECTION);
    _classPrivateFieldSet(_pressed, this, false);
    _classPrivateFieldGet(_rowsToMove, this).length = 0;
  }
}
/**
 * 'mouseMove' event callback. Fired when pointer move on document.documentElement.
 *
 * @param {MouseEvent} event `mousemove` event properties.
 */
function _onMouseMove(event) {
  if (!_classPrivateFieldGet(_pressed, this)) {
    return;
  }
  _classPrivateFieldGet(_target, this).eventPageY = event.pageY;
  this.refreshPositions();
}
/**
 * 'beforeOnCellMouseOver' hook callback. Fired when pointer was over cell.
 *
 * @param {MouseEvent} event `mouseover` event properties.
 * @param {CellCoords} coords Visual cell coordinates where was fired event.
 * @param {HTMLElement} TD Cell represented as HTMLElement.
 * @param {object} controller An object with properties `row`, `column` and `cell`. Each property contains
 *                            a boolean value that allows or disallows changing the selection for that particular area.
 */
function _onBeforeOnCellMouseOver(event, coords, TD, controller) {
  const selectedRange = this.hot.getSelectedRangeLast();
  if (!selectedRange || !_classPrivateFieldGet(_pressed, this)) {
    return;
  }
  if (_classPrivateFieldGet(_rowsToMove, this).indexOf(coords.row) > -1) {
    removeClass(this.hot.rootElement, CSS_SHOW_UI);
  } else {
    addClass(this.hot.rootElement, CSS_SHOW_UI);
  }
  controller.row = true;
  controller.column = true;
  controller.cell = true;
  _classPrivateFieldGet(_target, this).coords = coords;
  _classPrivateFieldGet(_target, this).TD = TD;
}
/**
 * `onMouseUp` hook callback.
 */
function _onMouseUp() {
  const target = _classPrivateFieldGet(_target, this).row;
  const rowsLen = _classPrivateFieldGet(_rowsToMove, this).length;
  _classPrivateFieldSet(_pressed, this, false);
  removeClass(this.hot.rootElement, [CSS_ON_MOVING, CSS_SHOW_UI, CSS_AFTER_SELECTION]);
  if (this.hot.selection.isSelectedByRowHeader()) {
    addClass(this.hot.rootElement, CSS_AFTER_SELECTION);
  }
  if (rowsLen < 1 || target === undefined) {
    return;
  }
  const firstMovedVisualRow = _classPrivateFieldGet(_rowsToMove, this)[0];
  const firstMovedPhysicalRow = this.hot.toPhysicalRow(firstMovedVisualRow);
  const movePerformed = this.dragRows(_classPrivateFieldGet(_rowsToMove, this), target);
  _classPrivateFieldGet(_rowsToMove, this).length = 0;
  if (movePerformed === true) {
    this.persistentStateSave();
    this.hot.render();
    this.hot.view.adjustElementsSize();
    const selectionStart = this.hot.toVisualRow(firstMovedPhysicalRow);
    const selectionEnd = selectionStart + rowsLen - 1;
    this.hot.selectRows(selectionStart, selectionEnd);
  }
}
/**
 * `afterScrollHorizontally` hook callback. Fired the table was scrolled horizontally.
 */
function _onAfterScrollHorizontally() {
  const wtTable = this.hot.view._wt.wtTable;
  const headerWidth = this.hot.view._wt.wtViewport.getRowHeaderWidth();
  const scrollLeft = wtTable.holder.scrollLeft;
  const posLeft = headerWidth + scrollLeft;
  _classPrivateFieldGet(_backlight, this).setPosition(null, posLeft);
  _classPrivateFieldGet(_backlight, this).setSize(wtTable.hider.offsetWidth - posLeft);
}
function _onAfterLoadData() {
  this.moveBySettingsOrLoad();
}