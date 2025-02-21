"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.filter");

require("core-js/modules/es.array.from");

require("core-js/modules/es.array.includes");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.join");

require("core-js/modules/es.array.splice");

require("core-js/modules/es.number.constructor");

require("core-js/modules/es.number.is-integer");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.set");

require("core-js/modules/es.string.includes");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.string.split");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _base = _interopRequireDefault(require("../_base"));

var _element = require("../../helpers/dom/element");

var _number = require("../../helpers/number");

var _array = require("../../helpers/array");

var _plugins = require("../../plugins");

var _predefinedItems = require("../contextMenu/predefinedItems");

var _pluginHooks = _interopRequireDefault(require("../../pluginHooks"));

var _hideColumn = _interopRequireDefault(require("./contextMenuItem/hideColumn"));

var _showColumn = _interopRequireDefault(require("./contextMenuItem/showColumn"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

_pluginHooks.default.getSingleton().register('beforeHideColumns');

_pluginHooks.default.getSingleton().register('afterHideColumns');

_pluginHooks.default.getSingleton().register('beforeUnhideColumns');

_pluginHooks.default.getSingleton().register('afterUnhideColumns');
/**
 * @plugin HiddenColumns
 *
 * @description
 * Plugin allows to hide certain columns. The hiding is achieved by rendering the columns with width set as 0px.
 * The plugin not modifies the source data and do not participate in data transformation (the shape of data returned
 * by `getData*` methods stays intact).
 *
 * Possible plugin settings:
 *  * `copyPasteEnabled` as `Boolean` (default `true`)
 *  * `columns` as `Array`
 *  * `indicators` as `Boolean` (default `false`)
 *
 * @example
 *
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   hiddenColumns: {
 *     copyPasteEnabled: true,
 *     indicators: true,
 *     columns: [1, 2, 5]
 *   }
 * });
 *
 * // access to hiddenColumns plugin instance:
 * const hiddenColumnsPlugin = hot.getPlugin('hiddenColumns');
 *
 * // show single row
 * hiddenColumnsPlugin.showColumn(1);
 *
 * // show multiple columns
 * hiddenColumnsPlugin.showColumn(1, 2, 9);
 *
 * // or as an array
 * hiddenColumnsPlugin.showColumns([1, 2, 9]);
 *
 * // hide single row
 * hiddenColumnsPlugin.hideColumn(1);
 *
 * // hide multiple columns
 * hiddenColumnsPlugin.hideColumn(1, 2, 9);
 *
 * // or as an array
 * hiddenColumnsPlugin.hideColumns([1, 2, 9]);
 *
 * // rerender the table to see all changes
 * hot.render();
 * ```
 */


var HiddenColumns =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(HiddenColumns, _BasePlugin);

  function HiddenColumns(hotInstance) {
    var _this;

    _classCallCheck(this, HiddenColumns);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(HiddenColumns).call(this, hotInstance));
    /**
     * Cached plugin settings.
     *
     * @private
     * @type {Object}
     */

    _this.settings = {};
    /**
     * List of currently hidden columns
     *
     * @private
     * @type {Number[]}
     */

    _this.hiddenColumns = [];
    /**
     * Last selected column index.
     *
     * @private
     * @type {Number}
     * @default -1
     */

    _this.lastSelectedColumn = -1;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link HiddenColumns#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(HiddenColumns, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().hiddenColumns;
    }
    /**
     * Enables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "enablePlugin",
    value: function enablePlugin() {
      var _this2 = this;

      if (this.enabled) {
        return;
      }

      if (this.hot.hasColHeaders()) {
        this.addHook('afterGetColHeader', function (col, TH) {
          return _this2.onAfterGetColHeader(col, TH);
        });
      } else {
        this.addHook('afterRenderer', function (TD, row, col) {
          return _this2.onAfterGetColHeader(col, TD);
        });
      }

      this.addHook('afterContextMenuDefaultOptions', function (options) {
        return _this2.onAfterContextMenuDefaultOptions(options);
      });
      this.addHook('afterGetCellMeta', function (row, col, cellProperties) {
        return _this2.onAfterGetCellMeta(row, col, cellProperties);
      });
      this.addHook('modifyColWidth', function (width, col) {
        return _this2.onModifyColWidth(width, col);
      });
      this.addHook('beforeSetRangeStartOnly', function (coords) {
        return _this2.onBeforeSetRangeStart(coords);
      });
      this.addHook('beforeSetRangeEnd', function (coords) {
        return _this2.onBeforeSetRangeEnd(coords);
      });
      this.addHook('hiddenColumn', function (column) {
        return _this2.isHidden(column);
      });
      this.addHook('beforeStretchingColumnWidth', function (width, column) {
        return _this2.onBeforeStretchingColumnWidth(width, column);
      });
      this.addHook('afterCreateCol', function (index, amount) {
        return _this2.onAfterCreateCol(index, amount);
      });
      this.addHook('afterRemoveCol', function (index, amount) {
        return _this2.onAfterRemoveCol(index, amount);
      });
      this.addHook('init', function () {
        return _this2.onInit();
      }); // Dirty workaround - the section below runs only if the HOT instance is already prepared.

      if (this.hot.view) {
        this.onInit();
      }

      _get(_getPrototypeOf(HiddenColumns.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      this.disablePlugin();
      this.enablePlugin();

      _get(_getPrototypeOf(HiddenColumns.prototype), "updatePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.settings = {};
      this.hiddenColumns = [];
      this.lastSelectedColumn = -1;
      this.hot.render();

      _get(_getPrototypeOf(HiddenColumns.prototype), "disablePlugin", this).call(this);

      this.resetCellsMeta();
    }
    /**
     * Shows the provided columns.
     *
     * @param {Number[]} columns Array of column indexes.
     */

  }, {
    key: "showColumns",
    value: function showColumns(columns) {
      var currentHideConfig = this.hiddenColumns;
      var validColumns = this.isColumnDataValid(columns);
      var destinationHideConfig = currentHideConfig;

      if (validColumns) {
        destinationHideConfig = this.hiddenColumns.filter(function (hiddenColumn) {
          return columns.includes(hiddenColumn) === false;
        });
      }

      var continueHiding = this.hot.runHooks('beforeUnhideColumns', currentHideConfig, destinationHideConfig, validColumns);

      if (continueHiding === false) {
        return;
      }

      if (validColumns) {
        this.hiddenColumns = destinationHideConfig;
      }

      this.hot.runHooks('afterUnhideColumns', currentHideConfig, destinationHideConfig, validColumns, validColumns && destinationHideConfig.length < currentHideConfig.length);
    }
    /**
     * Shows a single column.
     *
     * @param {...Number} column Visual column index.
     */

  }, {
    key: "showColumn",
    value: function showColumn() {
      for (var _len = arguments.length, column = new Array(_len), _key = 0; _key < _len; _key++) {
        column[_key] = arguments[_key];
      }

      this.showColumns(column);
    }
    /**
     * Hides the columns provided in the array.
     *
     * @param {Number[]} columns Array of visual column indexes.
     */

  }, {
    key: "hideColumns",
    value: function hideColumns(columns) {
      var currentHideConfig = this.hiddenColumns;
      var validColumns = this.isColumnDataValid(columns);
      var destinationHideConfig = currentHideConfig;

      if (validColumns) {
        destinationHideConfig = Array.from(new Set(currentHideConfig.concat(columns)));
      }

      var continueHiding = this.hot.runHooks('beforeHideColumns', currentHideConfig, destinationHideConfig, validColumns);

      if (continueHiding === false) {
        return;
      }

      if (validColumns) {
        this.hiddenColumns = destinationHideConfig;
      }

      this.hot.runHooks('afterHideColumns', currentHideConfig, destinationHideConfig, validColumns, validColumns && destinationHideConfig.length > currentHideConfig.length);
    }
    /**
     * Hides a single column.
     *
     * @param {...Number} column Visual column index.
     */

  }, {
    key: "hideColumn",
    value: function hideColumn() {
      for (var _len2 = arguments.length, column = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        column[_key2] = arguments[_key2];
      }

      this.hideColumns(column);
    }
    /**
     * Checks if the provided column is hidden.
     *
     * @param {Number} column Column index.
     * @param {Boolean} isPhysicalIndex flag which determines type of index.
     * @returns {Boolean}
     */

  }, {
    key: "isHidden",
    value: function isHidden(column) {
      var isPhysicalIndex = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
      var physicalColumn = column;

      if (!isPhysicalIndex) {
        physicalColumn = this.hot.toPhysicalColumn(column);
      }

      return this.hiddenColumns.includes(physicalColumn);
    }
    /**
     * Check whether all of the provided column indexes are within the bounds of the table.
     *
     * @param {Array} columns Array of column indexes.
     */

  }, {
    key: "isColumnDataValid",
    value: function isColumnDataValid(columns) {
      var _this3 = this;

      return columns.every(function (column) {
        return Number.isInteger(column) && column >= 0 && column < _this3.hot.countCols();
      });
    }
    /**
     * Reset all rendered cells meta.
     *
     * @private
     */

  }, {
    key: "resetCellsMeta",
    value: function resetCellsMeta() {
      (0, _array.arrayEach)(this.hot.getCellsMeta(), function (meta) {
        if (meta) {
          meta.skipColumnOnPaste = false;

          if (meta.baseRenderer !== null) {
            meta.renderer = meta.baseRenderer;
            meta.baseRenderer = null;
          }
        }
      });
    }
    /**
     * Sets width hidden columns on 0
     *
     * @private
     * @param {Number} width Column width.
     * @param {Number} column Column index.
     * @returns {Number}
     */

  }, {
    key: "onBeforeStretchingColumnWidth",
    value: function onBeforeStretchingColumnWidth(width, column) {
      var stretchedWidth = width;

      if (this.isHidden(column)) {
        stretchedWidth = 0;
      }

      return stretchedWidth;
    }
    /**
     * Adds the additional column width for the hidden column indicators.
     *
     * @private
     * @param {Number} width
     * @param {Number} col
     * @returns {Number}
     */

  }, {
    key: "onModifyColWidth",
    value: function onModifyColWidth(width, col) {
      if (this.isHidden(col)) {
        return 0.1;
      } else if (this.settings.indicators && (this.isHidden(col + 1) || this.isHidden(col - 1))) {
        // add additional space for hidden column indicator
        return width + (this.hot.hasColHeaders() ? 15 : 0);
      }
    }
    /**
     * Sets the copy-related cell meta.
     *
     * @private
     * @param {Number} row
     * @param {Number} col
     * @param {Object} cellProperties
     *
     * @fires Hooks#unmodifyCol
     */

  }, {
    key: "onAfterGetCellMeta",
    value: function onAfterGetCellMeta(row, col, cellProperties) {
      var colIndex = this.hot.runHooks('unmodifyCol', col);

      if (this.settings.copyPasteEnabled === false && this.isHidden(col)) {
        cellProperties.skipColumnOnPaste = true;
      }

      if (this.isHidden(colIndex)) {
        if (cellProperties.renderer !== hiddenRenderer) {
          cellProperties.baseRenderer = cellProperties.renderer;
        }

        cellProperties.renderer = hiddenRenderer;
      } else if (cellProperties.baseRenderer !== null) {
        // We must pass undefined value too (for the purposes of inheritance cell/column settings).
        cellProperties.renderer = cellProperties.baseRenderer;
        cellProperties.baseRenderer = null;
      }

      if (this.isHidden(cellProperties.visualCol - 1)) {
        var firstSectionHidden = true;
        var i = cellProperties.visualCol - 1;
        cellProperties.className = cellProperties.className || '';

        if (cellProperties.className.indexOf('afterHiddenColumn') === -1) {
          cellProperties.className += ' afterHiddenColumn';
        }

        do {
          if (!this.isHidden(i)) {
            firstSectionHidden = false;
            break;
          }

          i -= 1;
        } while (i >= 0);

        if (firstSectionHidden && cellProperties.className.indexOf('firstVisibleColumn') === -1) {
          cellProperties.className += ' firstVisibleColumn';
        }
      } else if (cellProperties.className) {
        var classArr = cellProperties.className.split(' ');

        if (classArr.length) {
          var containAfterHiddenColumn = classArr.indexOf('afterHiddenColumn');

          if (containAfterHiddenColumn > -1) {
            classArr.splice(containAfterHiddenColumn, 1);
          }

          var containFirstVisible = classArr.indexOf('firstVisibleColumn');

          if (containFirstVisible > -1) {
            classArr.splice(containFirstVisible, 1);
          }

          cellProperties.className = classArr.join(' ');
        }
      }
    }
    /**
     * Modifies the copyable range, accordingly to the provided config.
     *
     * @private
     * @param {Array} ranges
     * @returns {Array}
     */

  }, {
    key: "onModifyCopyableRange",
    value: function onModifyCopyableRange(ranges) {
      var _this4 = this;

      var newRanges = [];

      var pushRange = function pushRange(startRow, endRow, startCol, endCol) {
        newRanges.push({
          startRow: startRow,
          endRow: endRow,
          startCol: startCol,
          endCol: endCol
        });
      };

      (0, _array.arrayEach)(ranges, function (range) {
        var isHidden = true;
        var rangeStart = 0;
        (0, _number.rangeEach)(range.startCol, range.endCol, function (col) {
          if (_this4.isHidden(col)) {
            if (!isHidden) {
              pushRange(range.startRow, range.endRow, rangeStart, col - 1);
            }

            isHidden = true;
          } else {
            if (isHidden) {
              rangeStart = col;
            }

            if (col === range.endCol) {
              pushRange(range.startRow, range.endRow, rangeStart, col);
            }

            isHidden = false;
          }
        });
      });
      return newRanges;
    }
    /**
     * Adds the needed classes to the headers.
     *
     * @private
     * @param {Number} column
     * @param {HTMLElement} TH
     */

  }, {
    key: "onAfterGetColHeader",
    value: function onAfterGetColHeader(column, TH) {
      if (this.isHidden(column)) {
        return;
      }

      var firstSectionHidden = true;
      var i = column - 1;

      do {
        if (!this.isHidden(i)) {
          firstSectionHidden = false;
          break;
        }

        i -= 1;
      } while (i >= 0);

      if (firstSectionHidden) {
        (0, _element.addClass)(TH, 'firstVisibleColumn');
      }

      if (!this.settings.indicators) {
        return;
      }

      if (this.isHidden(column - 1)) {
        (0, _element.addClass)(TH, 'afterHiddenColumn');
      }

      if (this.isHidden(column + 1) && column > -1) {
        (0, _element.addClass)(TH, 'beforeHiddenColumn');
      }
    }
    /**
     * On before set range start listener.
     *
     * @private
     * @param {Object} coords Object with `row` and `col` properties.
     */

  }, {
    key: "onBeforeSetRangeStart",
    value: function onBeforeSetRangeStart(coords) {
      var _this5 = this;

      if (coords.col > 0) {
        return;
      }

      coords.col = 0;

      var getNextColumn = function getNextColumn(col) {
        var visualColumn = col;

        var physicalColumn = _this5.hot.toPhysicalColumn(visualColumn);

        if (_this5.isHidden(physicalColumn, true)) {
          visualColumn += 1;
          visualColumn = getNextColumn(visualColumn);
        }

        return visualColumn;
      };

      coords.col = getNextColumn(coords.col);
    }
    /**
     * On before set range end listener.
     *
     * @private
     * @param {Object} coords Object with `row` and `col` properties.
     */

  }, {
    key: "onBeforeSetRangeEnd",
    value: function onBeforeSetRangeEnd(coords) {
      var _this6 = this;

      var columnCount = this.hot.countCols();

      var getNextColumn = function getNextColumn(col) {
        var visualColumn = col;

        var physicalColumn = _this6.hot.toPhysicalColumn(visualColumn);

        if (_this6.isHidden(physicalColumn, true)) {
          if (_this6.lastSelectedColumn > visualColumn || coords.col === columnCount - 1) {
            if (visualColumn > 0) {
              visualColumn -= 1;
              visualColumn = getNextColumn(visualColumn);
            } else {
              (0, _number.rangeEach)(0, _this6.lastSelectedColumn, function (i) {
                if (!_this6.isHidden(i)) {
                  visualColumn = i;
                  return false;
                }
              });
            }
          } else {
            visualColumn += 1;
            visualColumn = getNextColumn(visualColumn);
          }
        }

        return visualColumn;
      };

      coords.col = getNextColumn(coords.col);
      this.lastSelectedColumn = coords.col;
    }
    /**
     * Add Show-hide columns to context menu.
     *
     * @private
     * @param {Object} options
     */

  }, {
    key: "onAfterContextMenuDefaultOptions",
    value: function onAfterContextMenuDefaultOptions(options) {
      options.items.push({
        name: _predefinedItems.SEPARATOR
      }, (0, _hideColumn.default)(this), (0, _showColumn.default)(this));
    }
    /**
     * `onAfterCreateCol` hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterCreateCol",
    value: function onAfterCreateCol(index, amount) {
      var tempHidden = [];
      (0, _array.arrayEach)(this.hiddenColumns, function (col) {
        var visualColumn = col;

        if (visualColumn >= index) {
          visualColumn += amount;
        }

        tempHidden.push(visualColumn);
      });
      this.hiddenColumns = tempHidden;
    }
    /**
     * `onAfterRemoveCol` hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterRemoveCol",
    value: function onAfterRemoveCol(index, amount) {
      var tempHidden = [];
      (0, _array.arrayEach)(this.hiddenColumns, function (col) {
        var visualColumn = col;

        if (visualColumn >= index) {
          visualColumn -= amount;
        }

        tempHidden.push(visualColumn);
      });
      this.hiddenColumns = tempHidden;
    }
    /**
     * `afterPluginsInitialized` hook callback.
     *
     * @private
     */

  }, {
    key: "onInit",
    value: function onInit() {
      var _this7 = this;

      var settings = this.hot.getSettings().hiddenColumns;

      if (_typeof(settings) === 'object') {
        this.settings = settings;

        if (settings.copyPasteEnabled === void 0) {
          settings.copyPasteEnabled = true;
        }

        if (Array.isArray(settings.columns)) {
          this.hideColumns(settings.columns);
        }

        if (!settings.copyPasteEnabled) {
          this.addHook('modifyCopyableRange', function (ranges) {
            return _this7.onModifyCopyableRange(ranges);
          });
        }
      }
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      _get(_getPrototypeOf(HiddenColumns.prototype), "destroy", this).call(this);
    }
  }]);

  return HiddenColumns;
}(_base.default);

function hiddenRenderer(hotInstance, td) {
  td.textContent = '';
}

(0, _plugins.registerPlugin)('hiddenColumns', HiddenColumns);
var _default = HiddenColumns;
exports.default = _default;