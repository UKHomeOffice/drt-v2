import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.concat";
import "core-js/modules/es.array.filter";
import "core-js/modules/es.array.from";
import "core-js/modules/es.array.includes";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.array.join";
import "core-js/modules/es.array.splice";
import "core-js/modules/es.number.constructor";
import "core-js/modules/es.number.is-integer";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.set";
import "core-js/modules/es.string.includes";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.string.split";
import "core-js/modules/web.dom-collections.iterator";

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

import BasePlugin from '../_base';
import { addClass, removeClass } from '../../helpers/dom/element';
import { rangeEach } from '../../helpers/number';
import { arrayEach, arrayMap } from '../../helpers/array';
import { registerPlugin } from '../../plugins';
import Hooks from '../../pluginHooks';
import hideRowItem from './contextMenuItem/hideRow';
import showRowItem from './contextMenuItem/showRow';
Hooks.getSingleton().register('beforeHideRows');
Hooks.getSingleton().register('afterHideRows');
Hooks.getSingleton().register('beforeUnhideRows');
Hooks.getSingleton().register('afterUnhideRows');
/**
 * @plugin HiddenRows
 *
 * @description
 * Plugin allows to hide certain rows. The hiding is achieved by rendering the rows with height set as 0px.
 * The plugin not modifies the source data and do not participate in data transformation (the shape of data returned
 * by `getData*` methods stays intact).
 *
 * Possible plugin settings:
 *  * `copyPasteEnabled` as `Boolean` (default `true`)
 *  * `rows` as `Array`
 *  * `indicators` as `Boolean` (default `false`)
 *
 * @example
 *
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   hiddenRows: {
 *     copyPasteEnabled: true,
 *     indicators: true,
 *     rows: [1, 2, 5]
 *   }
 * });
 *
 * // access to hiddenRows plugin instance
 * const hiddenRowsPlugin = hot.getPlugin('hiddenRows');
 *
 * // show single row
 * hiddenRowsPlugin.showRow(1);
 *
 * // show multiple rows
 * hiddenRowsPlugin.showRow(1, 2, 9);
 *
 * // or as an array
 * hiddenRowsPlugin.showRows([1, 2, 9]);
 *
 * // hide single row
 * hiddenRowsPlugin.hideRow(1);
 *
 * // hide multiple rows
 * hiddenRowsPlugin.hideRow(1, 2, 9);
 *
 * // or as an array
 * hiddenRowsPlugin.hideRows([1, 2, 9]);
 *
 * // rerender the table to see all changes
 * hot.render();
 * ```
 */

var HiddenRows =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(HiddenRows, _BasePlugin);

  function HiddenRows(hotInstance) {
    var _this;

    _classCallCheck(this, HiddenRows);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(HiddenRows).call(this, hotInstance));
    /**
     * Cached settings from Handsontable settings.
     *
     * @private
     * @type {Object}
     */

    _this.settings = {};
    /**
     * List of hidden rows indexes.
     *
     * @private
     * @type {Number[]}
     */

    _this.hiddenRows = [];
    /**
     * Last selected row index.
     *
     * @private
     * @type {Number}
     * @default -1
     */

    _this.lastSelectedRow = -1;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link HiddenRows#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(HiddenRows, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().hiddenRows;
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

      if (this.hot.hasRowHeaders()) {
        this.addHook('afterGetRowHeader', function (row, TH) {
          return _this2.onAfterGetRowHeader(row, TH);
        });
      } else {
        this.addHook('afterRenderer', function (TD, row) {
          return _this2.onAfterGetRowHeader(row, TD);
        });
      }

      this.addHook('afterContextMenuDefaultOptions', function (options) {
        return _this2.onAfterContextMenuDefaultOptions(options);
      });
      this.addHook('afterGetCellMeta', function (row, col, cellProperties) {
        return _this2.onAfterGetCellMeta(row, col, cellProperties);
      });
      this.addHook('modifyRowHeight', function (height, row) {
        return _this2.onModifyRowHeight(height, row);
      });
      this.addHook('beforeSetRangeStartOnly', function (coords) {
        return _this2.onBeforeSetRangeStartOnly(coords);
      });
      this.addHook('beforeSetRangeStart', function (coords) {
        return _this2.onBeforeSetRangeStart(coords);
      });
      this.addHook('beforeSetRangeEnd', function (coords) {
        return _this2.onBeforeSetRangeEnd(coords);
      });
      this.addHook('hiddenRow', function (row) {
        return _this2.isHidden(row);
      });
      this.addHook('afterCreateRow', function (index, amount) {
        return _this2.onAfterCreateRow(index, amount);
      });
      this.addHook('afterRemoveRow', function (index, amount) {
        return _this2.onAfterRemoveRow(index, amount);
      });
      this.addHook('init', function () {
        return _this2.onInit();
      }); // Dirty workaround - the section below runs only if the HOT instance is already prepared.

      if (this.hot.view) {
        this.onInit();
      }

      _get(_getPrototypeOf(HiddenRows.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      this.disablePlugin();
      this.enablePlugin();
      this.onInit();

      _get(_getPrototypeOf(HiddenRows.prototype), "updatePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.settings = {};
      this.hiddenRows = [];
      this.lastSelectedRow = -1;

      _get(_getPrototypeOf(HiddenRows.prototype), "disablePlugin", this).call(this);

      this.resetCellsMeta();
    }
    /**
     * Shows the rows provided in the array.
     *
     * @param {Number[]} rows Array of visual row indexes.
     */

  }, {
    key: "showRows",
    value: function showRows(rows) {
      var _this3 = this;

      var currentHideConfig = this.hiddenRows;
      var validRows = this.isRowDataValid(rows);
      var physicalRows = arrayMap(rows, function (visualRowIndex) {
        return _this3.hot.toPhysicalRow(visualRowIndex);
      });
      var destinationHideConfig = currentHideConfig;

      if (validRows) {
        destinationHideConfig = this.hiddenRows.filter(function (hiddenRow) {
          return physicalRows.includes(hiddenRow) === false;
        });
      }

      var continueHiding = this.hot.runHooks('beforeUnhideRows', currentHideConfig, destinationHideConfig, validRows);

      if (continueHiding === false) {
        return;
      }

      if (validRows) {
        this.hiddenRows = destinationHideConfig;
      }

      this.hot.runHooks('afterUnhideRows', currentHideConfig, destinationHideConfig, validRows, validRows && destinationHideConfig.length < currentHideConfig.length);
    }
    /**
     * Shows the row provided as row index (counting from 0).
     *
     * @param {...Number} row Visual row index.
     */

  }, {
    key: "showRow",
    value: function showRow() {
      for (var _len = arguments.length, row = new Array(_len), _key = 0; _key < _len; _key++) {
        row[_key] = arguments[_key];
      }

      this.showRows(row);
    }
    /**
     * Hides the rows provided in the array.
     *
     * @param {Number[]} rows Array of visual row indexes.
     */

  }, {
    key: "hideRows",
    value: function hideRows(rows) {
      var _this4 = this;

      var currentHideConfig = this.hiddenRows;
      var validRows = this.isRowDataValid(rows);
      var physicalRows = arrayMap(rows, function (visualRowIndex) {
        return _this4.hot.toPhysicalRow(visualRowIndex);
      });
      var destinationHideConfig = currentHideConfig;

      if (validRows) {
        // Creating unique list of indexes.
        destinationHideConfig = Array.from(new Set(currentHideConfig.concat(physicalRows)));
      }

      var continueHiding = this.hot.runHooks('beforeHideRows', currentHideConfig, destinationHideConfig, validRows);

      if (continueHiding === false) {
        return;
      }

      if (validRows) {
        this.hiddenRows = destinationHideConfig;
      }

      this.hot.runHooks('afterHideRows', currentHideConfig, destinationHideConfig, validRows, validRows && destinationHideConfig.length > currentHideConfig.length);
    }
    /**
     * Hides the row provided as row index (counting from 0).
     *
     * @param {...Number} row Visual row index.
     */

  }, {
    key: "hideRow",
    value: function hideRow() {
      for (var _len2 = arguments.length, row = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        row[_key2] = arguments[_key2];
      }

      this.hideRows(row);
    }
    /**
     * Checks if given row is hidden.
     *
     * @param {Number} row Row index.
     * @param {Boolean} isPhysicalIndex flag which determines type of index.
     * @returns {Boolean}
     */

  }, {
    key: "isHidden",
    value: function isHidden(row) {
      var isPhysicalIndex = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
      var physicalRow = row;

      if (!isPhysicalIndex) {
        physicalRow = this.hot.toPhysicalRow(row);
      }

      return this.hiddenRows.includes(physicalRow);
    }
    /**
     * Check whether all of the provided row indexes are within the bounds of the table.
     *
     * @param {Array} rows Array of visual row indexes.
     */

  }, {
    key: "isRowDataValid",
    value: function isRowDataValid(rows) {
      var _this5 = this;

      return rows.every(function (row) {
        return Number.isInteger(row) && row >= 0 && row < _this5.hot.countRows();
      });
    }
    /**
     * Resets all rendered cells meta.
     *
     * @private
     */

  }, {
    key: "resetCellsMeta",
    value: function resetCellsMeta() {
      arrayEach(this.hot.getCellsMeta(), function (meta) {
        if (meta) {
          meta.skipRowOnPaste = false;
        }
      });
    }
    /**
     * Sets the copy-related cell meta.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} col Column index.
     * @param {Object} cellProperties Cell meta object properties.
     *
     * @fires Hooks#unmodifyRow
     */

  }, {
    key: "onAfterGetCellMeta",
    value: function onAfterGetCellMeta(row, col, cellProperties) {
      var visualRow = this.hot.runHooks('unmodifyRow', row);

      if (this.settings.copyPasteEnabled === false && this.isHidden(visualRow)) {
        cellProperties.skipRowOnPaste = true;
      } else {
        cellProperties.skipRowOnPaste = false;
      }

      if (this.isHidden(visualRow - 1)) {
        var firstSectionHidden = true;
        var i = visualRow - 1;
        cellProperties.className = cellProperties.className || '';

        if (cellProperties.className.indexOf('afterHiddenRow') === -1) {
          cellProperties.className += ' afterHiddenRow';
        }

        do {
          if (!this.isHidden(i)) {
            firstSectionHidden = false;
            break;
          }

          i -= 1;
        } while (i >= 0);

        if (firstSectionHidden && cellProperties.className.indexOf('firstVisibleRow') === -1) {
          cellProperties.className += ' firstVisibleRow';
        }
      } else if (cellProperties.className) {
        var classArr = cellProperties.className.split(' ');

        if (classArr.length) {
          var containAfterHiddenColumn = classArr.indexOf('afterHiddenRow');

          if (containAfterHiddenColumn > -1) {
            classArr.splice(containAfterHiddenColumn, 1);
          }

          var containFirstVisible = classArr.indexOf('firstVisibleRow');

          if (containFirstVisible > -1) {
            classArr.splice(containFirstVisible, 1);
          }

          cellProperties.className = classArr.join(' ');
        }
      }
    }
    /**
     * Adds the needed classes to the headers.
     *
     * @private
     * @param {Number} row Row index.
     * @param {HTMLElement} th Table header element.
     */

  }, {
    key: "onAfterGetRowHeader",
    value: function onAfterGetRowHeader(row, th) {
      var tr = th.parentNode;

      if (tr) {
        if (this.isHidden(row)) {
          addClass(tr, 'hide');
        } else {
          removeClass(tr, 'hide');
        }
      }

      var firstSectionHidden = true;
      var i = row - 1;

      do {
        if (!this.isHidden(i)) {
          firstSectionHidden = false;
          break;
        }

        i -= 1;
      } while (i >= 0);

      if (firstSectionHidden) {
        addClass(th, 'firstVisibleRow');
      }

      if (this.settings.indicators && this.hot.hasRowHeaders()) {
        if (this.isHidden(row - 1)) {
          addClass(th, 'afterHiddenRow');
        }

        if (this.isHidden(row + 1)) {
          addClass(th, 'beforeHiddenRow');
        }
      }
    }
    /**
     * Adds the additional row height for the hidden row indicators.
     *
     * @private
     * @param {Number} height Row height.
     * @param {Number} row Row index.
     * @returns {Number}
     */

  }, {
    key: "onModifyRowHeight",
    value: function onModifyRowHeight(height, row) {
      if (this.isHidden(row)) {
        return 0.1;
      }

      return height;
    }
    /**
     * On modify copyable range listener.
     *
     * @private
     * @param {Array} ranges Array of selected copyable text.
     * @returns {Array} Returns modyfied range.
     */

  }, {
    key: "onModifyCopyableRange",
    value: function onModifyCopyableRange(ranges) {
      var _this6 = this;

      var newRanges = [];

      var pushRange = function pushRange(startRow, endRow, startCol, endCol) {
        newRanges.push({
          startRow: startRow,
          endRow: endRow,
          startCol: startCol,
          endCol: endCol
        });
      };

      arrayEach(ranges, function (range) {
        var isHidden = true;
        var rangeStart = 0;
        rangeEach(range.startRow, range.endRow, function (row) {
          if (_this6.isHidden(row)) {
            if (!isHidden) {
              pushRange(rangeStart, row - 1, range.startCol, range.endCol);
            }

            isHidden = true;
          } else {
            if (isHidden) {
              rangeStart = row;
            }

            if (row === range.endRow) {
              pushRange(rangeStart, row, range.startCol, range.endCol);
            }

            isHidden = false;
          }
        });
      });
      return newRanges;
    }
    /**
     * On before set range start listener, when selection was triggered by the cell.
     *
     * @private
     * @param {Object} coords Object with `row` and `col` properties.
     */

  }, {
    key: "onBeforeSetRangeStart",
    value: function onBeforeSetRangeStart(coords) {
      var _this7 = this;

      var actualSelection = this.hot.getSelectedLast() || false;
      var lastPossibleIndex = this.hot.countRows() - 1;

      var getNextRow = function getNextRow(row) {
        var direction = 0;
        var visualRow = row;

        if (actualSelection) {
          direction = visualRow > actualSelection[0] ? 1 : -1;
          _this7.lastSelectedRow = actualSelection[0];
        }

        if (lastPossibleIndex < visualRow || visualRow < 0) {
          return _this7.lastSelectedRow;
        }

        if (_this7.isHidden(visualRow)) {
          visualRow = getNextRow(visualRow + direction);
        }

        return visualRow;
      };

      coords.row = getNextRow(coords.row);
    }
    /**
     * On before set range start listener, when selection was triggered by the headers.
     *
     * @private
     * @param {Object} coords Object with `row` and `col` properties.
     */

  }, {
    key: "onBeforeSetRangeStartOnly",
    value: function onBeforeSetRangeStartOnly(coords) {
      var _this8 = this;

      if (coords.row > 0) {
        return;
      }

      coords.row = 0;

      var getNextRow = function getNextRow(row) {
        var visualRow = row;

        if (_this8.isHidden(visualRow)) {
          visualRow += 1;
          visualRow = getNextRow(visualRow);
        }

        return visualRow;
      };

      coords.row = getNextRow(coords.row);
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
      var _this9 = this;

      var rowCount = this.hot.countRows();

      var getNextRow = function getNextRow(row) {
        var visualRow = row;

        if (_this9.isHidden(visualRow)) {
          if (_this9.lastSelectedRow > visualRow || coords.row === rowCount - 1) {
            if (visualRow > 0) {
              visualRow -= 1;
              visualRow = getNextRow(visualRow);
            } else {
              rangeEach(0, _this9.lastSelectedRow, function (i) {
                if (!_this9.isHidden(i)) {
                  visualRow = i;
                  return false;
                }
              });
            }
          } else {
            visualRow += 1;
            visualRow = getNextRow(visualRow);
          }
        }

        return visualRow;
      };

      coords.row = getNextRow(coords.row);
      this.lastSelectedRow = coords.row;
    }
    /**
     * Adds Show-hide columns to context menu.
     *
     * @private
     * @param {Object} options
     */

  }, {
    key: "onAfterContextMenuDefaultOptions",
    value: function onAfterContextMenuDefaultOptions(options) {
      options.items.push({
        name: '---------'
      }, hideRowItem(this), showRowItem(this));
    }
    /**
     * Recalculates index of hidden rows after add row action
     *
     * @private
     * @param {Number} index
     * @param {Number} amount
     */

  }, {
    key: "onAfterCreateRow",
    value: function onAfterCreateRow(index, amount) {
      var tempHidden = [];
      arrayEach(this.hiddenRows, function (row) {
        var visualRow = row;

        if (visualRow >= index) {
          visualRow += amount;
        }

        tempHidden.push(visualRow);
      });
      this.hiddenRows = tempHidden;
    }
    /**
     * Recalculates index of hidden rows after remove row action
     *
     * @private
     * @param {Number} index
     * @param {Number} amount
     */

  }, {
    key: "onAfterRemoveRow",
    value: function onAfterRemoveRow(index, amount) {
      var tempHidden = [];
      arrayEach(this.hiddenRows, function (row) {
        var visualRow = row;

        if (visualRow >= index) {
          visualRow -= amount;
        }

        tempHidden.push(visualRow);
      });
      this.hiddenRows = tempHidden;
    }
    /**
     * `afterPluginsInitialized` hook callback.
     *
     * @private
     */

  }, {
    key: "onInit",
    value: function onInit() {
      var _this10 = this;

      var settings = this.hot.getSettings().hiddenRows;

      if (_typeof(settings) === 'object') {
        this.settings = settings;

        if (settings.copyPasteEnabled === void 0) {
          settings.copyPasteEnabled = true;
        }

        if (Array.isArray(settings.rows)) {
          this.hideRows(settings.rows);
        }

        if (!settings.copyPasteEnabled) {
          this.addHook('modifyCopyableRange', function (ranges) {
            return _this10.onModifyCopyableRange(ranges);
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
      _get(_getPrototypeOf(HiddenRows.prototype), "destroy", this).call(this);
    }
  }]);

  return HiddenRows;
}(BasePlugin);

registerPlugin('hiddenRows', HiddenRows);
export default HiddenRows;