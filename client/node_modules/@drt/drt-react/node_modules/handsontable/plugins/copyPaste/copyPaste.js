"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
require("core-js/modules/esnext.iterator.map.js");
var _base = require("../base");
var _hooks = require("../../core/hooks");
var _SheetClip = require("../../3rdparty/SheetClip");
var _array = require("../../helpers/array");
var _string = require("../../helpers/string");
var _element = require("../../helpers/dom/element");
var _browser = require("../../helpers/browser");
var _copy = _interopRequireDefault(require("./contextMenuItem/copy"));
var _copyColumnHeadersOnly = _interopRequireDefault(require("./contextMenuItem/copyColumnHeadersOnly"));
var _copyWithColumnGroupHeaders = _interopRequireDefault(require("./contextMenuItem/copyWithColumnGroupHeaders"));
var _copyWithColumnHeaders = _interopRequireDefault(require("./contextMenuItem/copyWithColumnHeaders"));
var _cut = _interopRequireDefault(require("./contextMenuItem/cut"));
var _pasteEvent = _interopRequireDefault(require("./pasteEvent"));
var _copyableRanges = require("./copyableRanges");
var _parseTable = require("../../utils/parseTable");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
_hooks.Hooks.getSingleton().register('afterCopyLimit');
_hooks.Hooks.getSingleton().register('modifyCopyableRange');
_hooks.Hooks.getSingleton().register('beforeCut');
_hooks.Hooks.getSingleton().register('afterCut');
_hooks.Hooks.getSingleton().register('beforePaste');
_hooks.Hooks.getSingleton().register('afterPaste');
_hooks.Hooks.getSingleton().register('beforeCopy');
_hooks.Hooks.getSingleton().register('afterCopy');
const PLUGIN_KEY = exports.PLUGIN_KEY = 'copyPaste';
const PLUGIN_PRIORITY = exports.PLUGIN_PRIORITY = 80;
const SETTING_KEYS = ['fragmentSelection'];
const META_HEAD = ['<meta name="generator" content="Handsontable"/>', '<style type="text/css">td{white-space:normal}br{mso-data-placement:same-cell}</style>'].join('');

/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @description
 * Copy, cut, and paste data by using the `CopyPaste` plugin.
 *
 * Control the `CopyPaste` plugin programmatically through its [API methods](#methods).
 *
 * The user can access the copy-paste features through:
 * - The [context menu](@/guides/cell-features/clipboard/clipboard.md#context-menu).
 * - The [keyboard shortcuts](@/guides/cell-features/clipboard/clipboard.md#related-keyboard-shortcuts).
 * - The browser's menu bar.
 *
 * Read more:
 * - [Guides: Clipboard](@/guides/cell-features/clipboard/clipboard.md)
 * - [Configuration options: `copyPaste`](@/api/options.md#copypaste)
 *
 * @example
 * ```js
 * // enable the plugin with the default configuration
 * copyPaste: true,
 *
 * // or, enable the plugin with a custom configuration
 * copyPaste: {
 *   columnsLimit: 25,
 *   rowsLimit: 50,
 *   pasteMode: 'shift_down',
 *   copyColumnHeaders: true,
 *   copyColumnGroupHeaders: true,
 *   copyColumnHeadersOnly: true,
 *   uiContainer: document.body,
 * },
 * ```
 * @class CopyPaste
 * @plugin CopyPaste
 */
var _enableCopyColumnHeaders = /*#__PURE__*/new WeakMap();
var _enableCopyColumnGroupHeaders = /*#__PURE__*/new WeakMap();
var _enableCopyColumnHeadersOnly = /*#__PURE__*/new WeakMap();
var _copyMode = /*#__PURE__*/new WeakMap();
var _isTriggeredByCopy = /*#__PURE__*/new WeakMap();
var _isTriggeredByCut = /*#__PURE__*/new WeakMap();
var _copyableRangesFactory = /*#__PURE__*/new WeakMap();
var _preventViewportScrollOnPaste = /*#__PURE__*/new WeakMap();
var _CopyPaste_brand = /*#__PURE__*/new WeakSet();
class CopyPaste extends _base.BasePlugin {
  constructor() {
    super(...arguments);
    /**
     * Ensure that the `copy`/`cut` events get triggered properly in Safari.
     *
     * @param {string} eventName Name of the event to get triggered.
     */
    _classPrivateMethodInitSpec(this, _CopyPaste_brand);
    /**
     * The maximum number of columns than can be copied to the clipboard.
     *
     * @type {number}
     * @default Infinity
     */
    _defineProperty(this, "columnsLimit", Infinity);
    /**
     * The maximum number of rows than can be copied to the clipboard.
     *
     * @type {number}
     * @default Infinity
     */
    _defineProperty(this, "rowsLimit", Infinity);
    /**
     * When pasting:
     * - `'overwrite'` - overwrite the currently-selected cells
     * - `'shift_down'` - move currently-selected cells down
     * - `'shift_right'` - move currently-selected cells to the right
     *
     * @type {string}
     * @default 'overwrite'
     */
    _defineProperty(this, "pasteMode", 'overwrite');
    /**
     * The UI container for the secondary focusable element.
     *
     * @type {HTMLElement}
     */
    _defineProperty(this, "uiContainer", this.hot.rootDocument.body);
    /**
     * Shows the "Copy with headers" item in the context menu and extends the context menu with the
     * `'copy_with_column_headers'` option that can be used for creating custom menus arrangements.
     *
     * @type {boolean}
     * @default false
     */
    _classPrivateFieldInitSpec(this, _enableCopyColumnHeaders, false);
    /**
     * Shows the "Copy with group headers" item in the context menu and extends the context menu with the
     * `'copy_with_column_group headers'` option that can be used for creating custom menus arrangements.
     *
     * @type {boolean}
     * @default false
     */
    _classPrivateFieldInitSpec(this, _enableCopyColumnGroupHeaders, false);
    /**
     * Shows the "Copy headers only" item in the context menu and extends the context menu with the
     * `'copy_column_headers_only'` option that can be used for creating custom menus arrangements.
     *
     * @type {boolean}
     * @default false
     */
    _classPrivateFieldInitSpec(this, _enableCopyColumnHeadersOnly, false);
    /**
     * Defines the data range to copy. Possible values:
     *  * `'cells-only'` Copy selected cells only;
     *  * `'column-headers-only'` Copy column headers only;
     *  * `'with-column-group-headers'` Copy cells with all column headers;
     *  * `'with-column-headers'` Copy cells with column headers;
     *
     * @type {'cells-only' | 'column-headers-only' | 'with-column-group-headers' | 'with-column-headers'}
     */
    _classPrivateFieldInitSpec(this, _copyMode, 'cells-only');
    /**
     * Flag that is used to prevent copying when the native shortcut was not pressed.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isTriggeredByCopy, false);
    /**
     * Flag that is used to prevent cutting when the native shortcut was not pressed.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isTriggeredByCut, false);
    /**
     * Class that helps generate copyable ranges based on the current selection for different copy mode
     * types.
     *
     * @type {CopyableRangesFactory}
     */
    _classPrivateFieldInitSpec(this, _copyableRangesFactory, new _copyableRanges.CopyableRangesFactory({
      countRows: () => this.hot.countRows(),
      countColumns: () => this.hot.countCols(),
      rowsLimit: () => this.rowsLimit,
      columnsLimit: () => this.columnsLimit,
      countColumnHeaders: () => this.hot.view.getColumnHeadersCount()
    }));
    /**
     * Flag that indicates if the viewport scroll should be prevented after pasting the data.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _preventViewportScrollOnPaste, false);
    /**
     * Ranges of the cells coordinates, which should be used to copy/cut/paste actions.
     *
     * @private
     * @type {Array<{startRow: number, startCol: number, endRow: number, endCol: number}>}
     */
    _defineProperty(this, "copyableRanges", []);
  }
  static get PLUGIN_KEY() {
    return PLUGIN_KEY;
  }
  static get SETTING_KEYS() {
    return [PLUGIN_KEY, ...SETTING_KEYS];
  }
  static get PLUGIN_PRIORITY() {
    return PLUGIN_PRIORITY;
  }
  static get DEFAULT_SETTINGS() {
    return {
      pasteMode: 'overwrite',
      rowsLimit: Infinity,
      columnsLimit: Infinity,
      copyColumnHeaders: false,
      copyColumnGroupHeaders: false,
      copyColumnHeadersOnly: false
    };
  }
  /**
   * Checks if the [`CopyPaste`](#copypaste) plugin is enabled.
   *
   * This method gets called by Handsontable's [`beforeInit`](@/api/hooks.md#beforeinit) hook.
   * If it returns `true`, the [`enablePlugin()`](#enableplugin) method gets called.
   *
   * @returns {boolean}
   */
  isEnabled() {
    return !!this.hot.getSettings()[PLUGIN_KEY];
  }

  /**
   * Enables the [`CopyPaste`](#copypaste) plugin for your Handsontable instance.
   */
  enablePlugin() {
    var _this$getSetting,
      _this$getSetting2,
      _this = this;
    if (this.enabled) {
      return;
    }
    this.pasteMode = (_this$getSetting = this.getSetting('pasteMode')) !== null && _this$getSetting !== void 0 ? _this$getSetting : this.pasteMode;
    this.rowsLimit = isNaN(this.getSetting('rowsLimit')) ? this.rowsLimit : this.getSetting('rowsLimit');
    this.columnsLimit = isNaN(this.getSetting('columnsLimit')) ? this.columnsLimit : this.getSetting('columnsLimit');
    _classPrivateFieldSet(_enableCopyColumnHeaders, this, this.getSetting('copyColumnHeaders'));
    _classPrivateFieldSet(_enableCopyColumnGroupHeaders, this, this.getSetting('copyColumnGroupHeaders'));
    _classPrivateFieldSet(_enableCopyColumnHeadersOnly, this, this.getSetting('copyColumnHeadersOnly'));
    this.uiContainer = (_this$getSetting2 = this.getSetting('uiContainer')) !== null && _this$getSetting2 !== void 0 ? _this$getSetting2 : this.uiContainer;
    this.addHook('afterContextMenuDefaultOptions', options => _assertClassBrand(_CopyPaste_brand, this, _onAfterContextMenuDefaultOptions).call(this, options));
    this.addHook('afterSelection', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _assertClassBrand(_CopyPaste_brand, _this, _onAfterSelection).call(_this, ...args);
    });
    this.addHook('afterSelectionEnd', () => _assertClassBrand(_CopyPaste_brand, this, _onAfterSelectionEnd).call(this));
    this.eventManager.addEventListener(this.hot.rootDocument, 'copy', function () {
      return _this.onCopy(...arguments);
    });
    this.eventManager.addEventListener(this.hot.rootDocument, 'cut', function () {
      return _this.onCut(...arguments);
    });
    this.eventManager.addEventListener(this.hot.rootDocument, 'paste', function () {
      return _this.onPaste(...arguments);
    });

    // Without this workaround Safari (tested on Safari@16.5.2) does allow copying/cutting from the browser menu.
    if ((0, _browser.isSafari)()) {
      this.eventManager.addEventListener(this.hot.rootDocument.body, 'mouseenter', function () {
        for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
          args[_key2] = arguments[_key2];
        }
        return _assertClassBrand(_CopyPaste_brand, _this, _onSafariMouseEnter).call(_this, ...args);
      });
      this.eventManager.addEventListener(this.hot.rootDocument.body, 'mouseleave', function () {
        for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
          args[_key3] = arguments[_key3];
        }
        return _assertClassBrand(_CopyPaste_brand, _this, _onSafariMouseLeave).call(_this, ...args);
      });
      this.addHook('afterSelection', () => _assertClassBrand(_CopyPaste_brand, this, _onSafariAfterSelection).call(this));
    }
    super.enablePlugin();
  }

  /**
   * Updates the state of the [`CopyPaste`](#copypaste) plugin.
   *
   * Gets called when [`updateSettings()`](@/api/core.md#updatesettings)
   * is invoked with any of the following configuration options:
   *  - [`copyPaste`](@/api/options.md#copypaste)
   *  - [`fragmentSelection`](@/api/options.md#fragmentselection)
   */
  updatePlugin() {
    this.disablePlugin();
    this.enablePlugin();
    super.updatePlugin();
  }

  /**
   * Disables the [`CopyPaste`](#copypaste) plugin for your Handsontable instance.
   */
  disablePlugin() {
    super.disablePlugin();
  }

  /**
   * Copies the contents of the selected cells (and/or their related column headers) to the system clipboard.
   *
   * Takes an optional parameter (`copyMode`) that defines the scope of copying:
   *
   * | `copyMode` value              | Description                                                     |
   * | ----------------------------- | --------------------------------------------------------------- |
   * | `'cells-only'` (default)      | Copy the selected cells                                         |
   * | `'with-column-headers'`       | - Copy the selected cells<br>- Copy the nearest column headers  |
   * | `'with-column-group-headers'` | - Copy the selected cells<br>- Copy all related columns headers |
   * | `'column-headers-only'`       | Copy the nearest column headers (without copying cells)         |
   *
   * @param {string} [copyMode='cells-only'] Copy mode.
   */
  copy() {
    let copyMode = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : 'cells-only';
    _classPrivateFieldSet(_copyMode, this, copyMode);
    _classPrivateFieldSet(_isTriggeredByCopy, this, true);
    _assertClassBrand(_CopyPaste_brand, this, _ensureClipboardEventsGetTriggered).call(this, 'copy');
  }

  /**
   * Copies the contents of the selected cells.
   */
  copyCellsOnly() {
    this.copy('cells-only');
  }
  /**
   * Copies the contents of column headers that are nearest to the selected cells.
   */
  copyColumnHeadersOnly() {
    this.copy('column-headers-only');
  }
  /**
   * Copies the contents of the selected cells and all their related column headers.
   */
  copyWithAllColumnHeaders() {
    this.copy('with-column-group-headers');
  }
  /**
   * Copies the contents of the selected cells and their nearest column headers.
   */
  copyWithColumnHeaders() {
    this.copy('with-column-headers');
  }

  /**
   * Cuts the contents of the selected cells to the system clipboard.
   */
  cut() {
    _classPrivateFieldSet(_isTriggeredByCut, this, true);
    _assertClassBrand(_CopyPaste_brand, this, _ensureClipboardEventsGetTriggered).call(this, 'cut');
  }

  /**
   * Converts the contents of multiple ranges (`ranges`) into a single string.
   *
   * @param {Array<{startRow: number, startCol: number, endRow: number, endCol: number}>} ranges Array of objects with properties `startRow`, `endRow`, `startCol` and `endCol`.
   * @returns {string} A string that will be copied to the clipboard.
   */
  getRangedCopyableData(ranges) {
    return (0, _SheetClip.stringify)(this.getRangedData(ranges));
  }

  /**
   * Converts the contents of multiple ranges (`ranges`) into an array of arrays.
   *
   * @param {Array<{startRow: number, startCol: number, endRow: number, endCol: number}>} ranges Array of objects with properties `startRow`, `startCol`, `endRow` and `endCol`.
   * @returns {Array[]} An array of arrays that will be copied to the clipboard.
   */
  getRangedData(ranges) {
    const data = [];
    const {
      rows,
      columns
    } = (0, _copyableRanges.normalizeRanges)(ranges);

    // concatenate all rows and columns data defined in ranges into one copyable string
    (0, _array.arrayEach)(rows, row => {
      const rowSet = [];
      (0, _array.arrayEach)(columns, column => {
        if (row < 0) {
          // `row` as the second argument acts here as the `headerLevel` argument
          rowSet.push(this.hot.getColHeader(column, row));
        } else {
          rowSet.push(this.hot.getCopyableData(row, column));
        }
      });
      data.push(rowSet);
    });
    return data;
  }

  /**
   * Simulates the paste action.
   *
   * For security reasons, modern browsers don't allow reading from the system clipboard.
   *
   * @param {string} pastableText The value to paste, as a raw string.
   * @param {string} [pastableHtml=''] The value to paste, as HTML.
   */
  paste() {
    let pastableText = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : '';
    let pastableHtml = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : pastableText;
    if (!pastableText && !pastableHtml) {
      return;
    }
    const pasteData = new _pasteEvent.default();
    if (pastableText) {
      pasteData.clipboardData.setData('text/plain', pastableText);
    }
    if (pastableHtml) {
      pasteData.clipboardData.setData('text/html', pastableHtml);
    }
    this.onPaste(pasteData);
  }

  /**
   * Prepares copyable text from the cells selection in the invisible textarea.
   */
  setCopyableText() {
    const selectionRange = this.hot.getSelectedRangeLast();
    if (!selectionRange) {
      return;
    }
    if (selectionRange.isSingleHeader()) {
      this.copyableRanges = [];
      return;
    }
    _classPrivateFieldGet(_copyableRangesFactory, this).setSelectedRange(selectionRange);
    const groupedRanges = new Map([['headers', null], ['cells', null]]);
    if (_classPrivateFieldGet(_copyMode, this) === 'column-headers-only') {
      groupedRanges.set('headers', _classPrivateFieldGet(_copyableRangesFactory, this).getMostBottomColumnHeadersRange());
    } else {
      if (_classPrivateFieldGet(_copyMode, this) === 'with-column-headers') {
        groupedRanges.set('headers', _classPrivateFieldGet(_copyableRangesFactory, this).getMostBottomColumnHeadersRange());
      } else if (_classPrivateFieldGet(_copyMode, this) === 'with-column-group-headers') {
        groupedRanges.set('headers', _classPrivateFieldGet(_copyableRangesFactory, this).getAllColumnHeadersRange());
      }
      groupedRanges.set('cells', _classPrivateFieldGet(_copyableRangesFactory, this).getCellsRange());
    }
    this.copyableRanges = Array.from(groupedRanges.values()).filter(range => range !== null).map(_ref => {
      let {
        startRow,
        startCol,
        endRow,
        endCol
      } = _ref;
      return {
        startRow,
        startCol,
        endRow,
        endCol
      };
    });
    this.copyableRanges = this.hot.runHooks('modifyCopyableRange', this.copyableRanges);
    const cellsRange = groupedRanges.get('cells');
    if (cellsRange !== null && cellsRange.isRangeTrimmed) {
      const {
        startRow,
        startCol,
        endRow,
        endCol
      } = cellsRange;
      this.hot.runHooks('afterCopyLimit', endRow - startRow + 1, endCol - startCol + 1, this.rowsLimit, this.columnsLimit);
    }
  }

  /**
   * Verifies if editor exists and is open.
   *
   * @private
   * @returns {boolean}
   */
  isEditorOpened() {
    var _this$hot$getActiveEd;
    return (_this$hot$getActiveEd = this.hot.getActiveEditor()) === null || _this$hot$getActiveEd === void 0 ? void 0 : _this$hot$getActiveEd.isOpened();
  }
  /**
   * Prepares new values to populate them into datasource.
   *
   * @private
   * @param {Array} inputArray An array of the data to populate.
   * @param {Array} [selection] The selection which indicates from what position the data will be populated.
   * @returns {Array} Range coordinates after populate data.
   */
  populateValues(inputArray) {
    let selection = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : this.hot.getSelectedRangeLast();
    if (!inputArray.length) {
      return;
    }
    const populatedRowsLength = inputArray.length;
    const populatedColumnsLength = inputArray[0].length;
    const newRows = [];
    const {
      row: startRow,
      col: startColumn
    } = selection.getTopStartCorner();
    const {
      row: endRowFromSelection,
      col: endColumnFromSelection
    } = selection.getBottomEndCorner();
    let visualRowForPopulatedData = startRow;
    let visualColumnForPopulatedData = startColumn;
    let lastVisualRow = startRow;
    let lastVisualColumn = startColumn;

    // We try to populate just all copied data or repeat copied data within a selection. Please keep in mind that we
    // don't know whether populated data is bigger than selection on start as there are some cells for which values
    // should be not inserted (it's known right after getting cell meta).
    while (newRows.length < populatedRowsLength || visualRowForPopulatedData <= endRowFromSelection) {
      const {
        skipRowOnPaste,
        visualRow
      } = this.hot.getCellMeta(visualRowForPopulatedData, startColumn);
      visualRowForPopulatedData = visualRow + 1;
      if (skipRowOnPaste === true) {
        /* eslint-disable no-continue */
        continue;
      }
      lastVisualRow = visualRow;
      visualColumnForPopulatedData = startColumn;
      const newRow = [];
      const insertedRow = newRows.length % populatedRowsLength;
      while (newRow.length < populatedColumnsLength || visualColumnForPopulatedData <= endColumnFromSelection) {
        const {
          skipColumnOnPaste,
          visualCol
        } = this.hot.getCellMeta(startRow, visualColumnForPopulatedData);
        visualColumnForPopulatedData = visualCol + 1;
        if (skipColumnOnPaste === true) {
          /* eslint-disable no-continue */
          continue;
        }
        lastVisualColumn = visualCol;
        const insertedColumn = newRow.length % populatedColumnsLength;
        newRow.push(inputArray[insertedRow][insertedColumn]);
      }
      newRows.push(newRow);
    }
    _classPrivateFieldSet(_preventViewportScrollOnPaste, this, true);
    this.hot.populateFromArray(startRow, startColumn, newRows, undefined, undefined, 'CopyPaste.paste', this.pasteMode);
    return [startRow, startColumn, lastVisualRow, lastVisualColumn];
  }

  /**
   * Add the `contenteditable` attribute to the highlighted cell and select its content.
   */

  /**
   * `copy` event callback on textarea element.
   *
   * @param {Event} event ClipboardEvent.
   * @private
   */
  onCopy(event) {
    var _event$target, _this$hot$getSelected;
    const focusedElement = this.hot.getFocusManager().getRefocusElement();
    const isHotInput = (_event$target = event.target) === null || _event$target === void 0 ? void 0 : _event$target.hasAttribute('data-hot-input');
    const selectedCell = (_this$hot$getSelected = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected === void 0 ? void 0 : _this$hot$getSelected.highlight;
    const TD = selectedCell ? this.hot.getCell(selectedCell.row, selectedCell.col, true) : null;
    if (!this.hot.isListening() && !_classPrivateFieldGet(_isTriggeredByCopy, this) || this.isEditorOpened() || event.target instanceof HTMLElement && (isHotInput && event.target !== focusedElement || !isHotInput && event.target !== this.hot.rootDocument.body && TD !== event.target)) {
      return;
    }
    event.preventDefault();
    this.setCopyableText();
    _classPrivateFieldSet(_isTriggeredByCopy, this, false);
    const data = this.getRangedData(this.copyableRanges);
    const copiedHeadersCount = _assertClassBrand(_CopyPaste_brand, this, _countCopiedHeaders).call(this, this.copyableRanges);
    const allowCopying = !!this.hot.runHooks('beforeCopy', data, this.copyableRanges, copiedHeadersCount);
    if (allowCopying) {
      const textPlain = (0, _SheetClip.stringify)(data);
      if (event && event.clipboardData) {
        const textHTML = (0, _parseTable._dataToHTML)(data, this.hot.rootDocument);
        event.clipboardData.setData('text/plain', textPlain);
        event.clipboardData.setData('text/html', [META_HEAD, textHTML].join(''));
      } else if (typeof ClipboardEvent === 'undefined') {
        this.hot.rootWindow.clipboardData.setData('Text', textPlain);
      }
      this.hot.runHooks('afterCopy', data, this.copyableRanges, copiedHeadersCount);
    }
    _classPrivateFieldSet(_copyMode, this, 'cells-only');
  }

  /**
   * `cut` event callback on textarea element.
   *
   * @param {Event} event ClipboardEvent.
   * @private
   */
  onCut(event) {
    var _event$target2, _this$hot$getSelected2;
    const focusedElement = this.hot.getFocusManager().getRefocusElement();
    const isHotInput = (_event$target2 = event.target) === null || _event$target2 === void 0 ? void 0 : _event$target2.hasAttribute('data-hot-input');
    const selectedCell = (_this$hot$getSelected2 = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected2 === void 0 ? void 0 : _this$hot$getSelected2.highlight;
    const TD = selectedCell ? this.hot.getCell(selectedCell.row, selectedCell.col, true) : null;
    if (!this.hot.isListening() && !_classPrivateFieldGet(_isTriggeredByCut, this) || this.isEditorOpened() || event.target instanceof HTMLElement && (isHotInput && event.target !== focusedElement || !isHotInput && event.target !== this.hot.rootDocument.body && TD !== event.target)) {
      return;
    }
    event.preventDefault();
    this.setCopyableText();
    _classPrivateFieldSet(_isTriggeredByCut, this, false);
    const rangedData = this.getRangedData(this.copyableRanges);
    const allowCuttingOut = !!this.hot.runHooks('beforeCut', rangedData, this.copyableRanges);
    if (allowCuttingOut) {
      const textPlain = (0, _SheetClip.stringify)(rangedData);
      if (event && event.clipboardData) {
        const textHTML = (0, _parseTable._dataToHTML)(rangedData, this.hot.rootDocument);
        event.clipboardData.setData('text/plain', textPlain);
        event.clipboardData.setData('text/html', [META_HEAD, textHTML].join(''));
      } else if (typeof ClipboardEvent === 'undefined') {
        this.hot.rootWindow.clipboardData.setData('Text', textPlain);
      }
      this.hot.emptySelectedCells('CopyPaste.cut');
      this.hot.runHooks('afterCut', rangedData, this.copyableRanges);
    }
  }

  /**
   * `paste` event callback on textarea element.
   *
   * @param {Event} event ClipboardEvent or pseudo ClipboardEvent, if paste was called manually.
   * @private
   */
  onPaste(event) {
    var _event$target3, _this$hot$getSelected3;
    const focusedElement = this.hot.getFocusManager().getRefocusElement();
    const isHotInput = (_event$target3 = event.target) === null || _event$target3 === void 0 ? void 0 : _event$target3.hasAttribute('data-hot-input');
    const selectedCell = (_this$hot$getSelected3 = this.hot.getSelectedRangeLast()) === null || _this$hot$getSelected3 === void 0 ? void 0 : _this$hot$getSelected3.highlight;
    const TD = selectedCell ? this.hot.getCell(selectedCell.row, selectedCell.col, true) : null;
    if (!this.hot.isListening() || this.isEditorOpened() || !this.hot.getSelected() || event.target instanceof HTMLElement && (isHotInput && event.target !== focusedElement || !isHotInput && event.target !== this.hot.rootDocument.body && TD !== event.target)) {
      return;
    }
    event.preventDefault();
    let pastedData;
    if (event && typeof event.clipboardData !== 'undefined') {
      const textHTML = (0, _string.sanitize)(event.clipboardData.getData('text/html'), {
        ADD_TAGS: ['meta'],
        ADD_ATTR: ['content'],
        FORCE_BODY: true
      });
      if (textHTML && /(<table)|(<TABLE)/g.test(textHTML)) {
        const parsedConfig = (0, _parseTable.htmlToGridSettings)(textHTML, this.hot.rootDocument);
        pastedData = parsedConfig.data;
      } else {
        pastedData = event.clipboardData.getData('text/plain');
      }
    } else if (typeof ClipboardEvent === 'undefined' && typeof this.hot.rootWindow.clipboardData !== 'undefined') {
      pastedData = this.hot.rootWindow.clipboardData.getData('Text');
    }
    if (typeof pastedData === 'string') {
      pastedData = (0, _SheetClip.parse)(pastedData);
    }
    if (pastedData === void 0 || pastedData && pastedData.length === 0) {
      return;
    }
    if (this.hot.runHooks('beforePaste', pastedData, this.copyableRanges) === false) {
      return;
    }
    const [startRow, startColumn, endRow, endColumn] = this.populateValues(pastedData);
    this.hot.selectCell(startRow, startColumn, Math.min(this.hot.countRows() - 1, endRow), Math.min(this.hot.countCols() - 1, endColumn));
    this.hot.runHooks('afterPaste', pastedData, this.copyableRanges);
  }

  /**
   * Add copy and cut options to the Context Menu.
   *
   * @param {object} options Contains default added options of the Context Menu.
   */

  /**
   * Destroys the `CopyPaste` plugin instance.
   */
  destroy() {
    super.destroy();
  }
}
exports.CopyPaste = CopyPaste;
function _ensureClipboardEventsGetTriggered(eventName) {
  // Without this workaround Safari (tested on Safari@16.5.2) does not trigger the 'copy' event.
  if ((0, _browser.isSafari)()) {
    const lastSelectedRange = this.hot.getSelectedRangeLast();
    if (lastSelectedRange) {
      const {
        row: highlightRow,
        col: highlightColumn
      } = lastSelectedRange.highlight;
      const currentlySelectedCell = this.hot.getCell(highlightRow, highlightColumn, true);
      if (currentlySelectedCell) {
        (0, _element.runWithSelectedContendEditableElement)(currentlySelectedCell, () => {
          this.hot.rootDocument.execCommand(eventName);
        });
      }
    }
  } else {
    this.hot.rootDocument.execCommand(eventName);
  }
}
/**
 * Counts how many column headers will be copied based on the passed range.
 *
 * @private
 * @param {Array<{startRow: number, startCol: number, endRow: number, endCol: number}>} ranges Array of objects with properties `startRow`, `startCol`, `endRow` and `endCol`.
 * @returns {{ columnHeadersCount: number }} Returns an object with keys that holds
 *                                           information with the number of copied headers.
 */
function _countCopiedHeaders(ranges) {
  const {
    rows
  } = (0, _copyableRanges.normalizeRanges)(ranges);
  let columnHeadersCount = 0;
  for (let row = 0; row < rows.length; row++) {
    if (rows[row] >= 0) {
      break;
    }
    columnHeadersCount += 1;
  }
  return {
    columnHeadersCount
  };
}
function _addContentEditableToHighlightedCell() {
  if (this.hot.isListening()) {
    const lastSelectedRange = this.hot.getSelectedRangeLast();
    if (lastSelectedRange) {
      const {
        row: highlightRow,
        col: highlightColumn
      } = lastSelectedRange.highlight;
      const currentlySelectedCell = this.hot.getCell(highlightRow, highlightColumn, true);
      if (currentlySelectedCell) {
        (0, _element.makeElementContentEditableAndSelectItsContent)(currentlySelectedCell);
      }
    }
  }
}
/**
 * Remove the `contenteditable` attribute from the highlighted cell and deselect its content.
 */
function _removeContentEditableFromHighlightedCell() {
  // If the instance is not listening, the workaround is not needed.
  if (this.hot.isListening()) {
    const lastSelectedRange = this.hot.getSelectedRangeLast();
    if (lastSelectedRange) {
      const {
        row: highlightRow,
        col: highlightColumn
      } = lastSelectedRange.highlight;
      const currentlySelectedCell = this.hot.getCell(highlightRow, highlightColumn, true);
      if (currentlySelectedCell !== null && currentlySelectedCell !== void 0 && currentlySelectedCell.hasAttribute('contenteditable')) {
        (0, _element.removeContentEditableFromElementAndDeselect)(currentlySelectedCell);
      }
    }
  }
}
function _onAfterContextMenuDefaultOptions(options) {
  options.items.push({
    name: '---------'
  }, (0, _copy.default)(this));
  if (_classPrivateFieldGet(_enableCopyColumnHeaders, this)) {
    options.items.push((0, _copyWithColumnHeaders.default)(this));
  }
  if (_classPrivateFieldGet(_enableCopyColumnGroupHeaders, this)) {
    options.items.push((0, _copyWithColumnGroupHeaders.default)(this));
  }
  if (_classPrivateFieldGet(_enableCopyColumnHeadersOnly, this)) {
    options.items.push((0, _copyColumnHeadersOnly.default)(this));
  }
  options.items.push((0, _cut.default)(this));
}
/**
 * Disables the viewport scroll after pasting the data.
 *
 * @param {number} fromRow Selection start row visual index.
 * @param {number} fromColumn Selection start column visual index.
 * @param {number} toRow Selection end row visual index.
 * @param {number} toColumn Selection end column visual index.
 * @param {object} preventScrolling Object with `value` property. If `true`, the viewport scroll will be prevented.
 */
function _onAfterSelection(fromRow, fromColumn, toRow, toColumn, preventScrolling) {
  if (_classPrivateFieldGet(_preventViewportScrollOnPaste, this)) {
    preventScrolling.value = true;
  }
  _classPrivateFieldSet(_preventViewportScrollOnPaste, this, false);
}
/**
 * Force focus on focusableElement after end of the selection.
 */
function _onAfterSelectionEnd() {
  if (this.isEditorOpened()) {
    return;
  }
  if (this.hot.getSettings().fragmentSelection) {
    return;
  }
  this.setCopyableText();
}
/**
 * `document.body` `mouseenter` callback used to work around a Safari's problem with copying/cutting from the
 * browser's menu.
 */
function _onSafariMouseEnter() {
  _assertClassBrand(_CopyPaste_brand, this, _removeContentEditableFromHighlightedCell).call(this);
}
/**
 * `document.body` `mouseleave` callback used to work around a Safari's problem with copying/cutting from the
 * browser's menu.
 */
function _onSafariMouseLeave() {
  _assertClassBrand(_CopyPaste_brand, this, _addContentEditableToHighlightedCell).call(this);
}
/**
 * `afterSelection` hook callback triggered only on Safari.
 */
function _onSafariAfterSelection() {
  _assertClassBrand(_CopyPaste_brand, this, _removeContentEditableFromHighlightedCell).call(this);
}