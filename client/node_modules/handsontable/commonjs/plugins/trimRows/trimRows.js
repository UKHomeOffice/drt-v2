"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.filter");

require("core-js/modules/es.array.from");

require("core-js/modules/es.array.includes");

require("core-js/modules/es.array.iterator");

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

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _base = _interopRequireDefault(require("../_base"));

var _number = require("../../helpers/number");

var _plugins = require("../../plugins");

var _rowsMapper = _interopRequireDefault(require("./rowsMapper"));

var _array = require("../../helpers/array");

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

/**
 * @plugin TrimRows
 *
 * @description
 * The plugin allows to trim certain rows. The trimming is achieved by applying the transformation algorithm to the data
 * transformation. In this case, when the row is trimmed it is not accessible using `getData*` methods thus the trimmed
 * data is not visible to other plugins.
 *
 * @example
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   // hide selected rows on table initialization
 *   trimRows: [1, 2, 5]
 * });
 *
 * // access the trimRows plugin instance
 * const trimRowsPlugin = hot.getPlugin('trimRows');
 *
 * // hide single row
 * trimRowsPlugin.trimRow(1);
 *
 * // hide multiple rows
 * trimRowsPlugin.trimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.trimRows([1, 2, 9]);
 *
 * // show single row
 * trimRowsPlugin.untrimRow(1);
 *
 * // show multiple rows
 * trimRowsPlugin.untrimRow(1, 2, 9);
 *
 * // or as an array
 * trimRowsPlugin.untrimRows([1, 2, 9]);
 *
 * // rerender table to see the changes
 * hot.render();
 * ```
 */
var TrimRows =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(TrimRows, _BasePlugin);

  function TrimRows(hotInstance) {
    var _this;

    _classCallCheck(this, TrimRows);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(TrimRows).call(this, hotInstance));
    /**
     * List of trimmed row indexes.
     *
     * @private
     * @type {Array}
     */

    _this.trimmedRows = [];
    /**
     * List of last removed row indexes.
     *
     * @private
     * @type {Array}
     */

    _this.removedRows = [];
    /**
     * Object containing visual row indexes mapped to data source indexes.
     *
     * @private
     * @type {RowsMapper}
     */

    _this.rowsMapper = new _rowsMapper.default(_assertThisInitialized(_this));
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link AutoRowSize#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(TrimRows, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().trimRows;
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

      var settings = this.hot.getSettings().trimRows;

      if (Array.isArray(settings)) {
        this.trimmedRows = settings;
      }

      this.rowsMapper.createMap(this.hot.countSourceRows());
      this.addHook('modifyRow', function (row, source) {
        return _this2.onModifyRow(row, source);
      });
      this.addHook('unmodifyRow', function (row, source) {
        return _this2.onUnmodifyRow(row, source);
      });
      this.addHook('beforeCreateRow', function (index, amount, source) {
        return _this2.onBeforeCreateRow(index, amount, source);
      });
      this.addHook('afterCreateRow', function (index, amount) {
        return _this2.onAfterCreateRow(index, amount);
      });
      this.addHook('beforeRemoveRow', function (index, amount) {
        return _this2.onBeforeRemoveRow(index, amount);
      });
      this.addHook('afterRemoveRow', function () {
        return _this2.onAfterRemoveRow();
      });
      this.addHook('afterLoadData', function (firstRun) {
        return _this2.onAfterLoadData(firstRun);
      });

      _get(_getPrototypeOf(TrimRows.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      var settings = this.hot.getSettings().trimRows;

      if (Array.isArray(settings)) {
        this.disablePlugin();
        this.enablePlugin();
      }

      _get(_getPrototypeOf(TrimRows.prototype), "updatePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.trimmedRows = [];
      this.removedRows.length = 0;
      this.rowsMapper.clearMap();

      _get(_getPrototypeOf(TrimRows.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Trims the rows provided in the array.
     *
     * @param {Number[]} rows Array of physical row indexes.
     * @fires Hooks#skipLengthCache
     * @fires Hooks#beforeTrimRow
     * @fires Hooks#afterTrimRow
     */

  }, {
    key: "trimRows",
    value: function trimRows(rows) {
      var currentTrimConfig = this.trimmedRows;
      var isValidConfig = this.isValidConfig(rows);
      var destinationTrimConfig = currentTrimConfig;

      if (isValidConfig) {
        destinationTrimConfig = Array.from(new Set(currentTrimConfig.concat(rows)));
      }

      var allowTrimRow = this.hot.runHooks('beforeTrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig);

      if (allowTrimRow === false) {
        return;
      }

      if (isValidConfig) {
        this.trimmedRows = destinationTrimConfig;
        this.hot.runHooks('skipLengthCache', 100);
        this.rowsMapper.createMap(this.hot.countSourceRows());
      }

      this.hot.runHooks('afterTrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig, isValidConfig && destinationTrimConfig.length > currentTrimConfig.length);
    }
    /**
     * Trims the row provided as physical row index (counting from 0).
     *
     * @param {...Number} row Physical row index.
     */

  }, {
    key: "trimRow",
    value: function trimRow() {
      for (var _len = arguments.length, row = new Array(_len), _key = 0; _key < _len; _key++) {
        row[_key] = arguments[_key];
      }

      this.trimRows(row);
    }
    /**
     * Untrims the rows provided in the array.
     *
     * @param {Number[]} rows Array of physical row indexes.
     * @fires Hooks#skipLengthCache
     * @fires Hooks#beforeUntrimRow
     * @fires Hooks#afterUntrimRow
     */

  }, {
    key: "untrimRows",
    value: function untrimRows(rows) {
      var currentTrimConfig = this.trimmedRows;
      var isValidConfig = this.isValidConfig(rows);
      var destinationTrimConfig = currentTrimConfig;

      if (isValidConfig) {
        destinationTrimConfig = this.trimmedRows.filter(function (trimmedRow) {
          return rows.includes(trimmedRow) === false;
        });
      }

      var allowUntrimRow = this.hot.runHooks('beforeUntrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig);

      if (allowUntrimRow === false) {
        return;
      }

      if (isValidConfig) {
        this.trimmedRows = destinationTrimConfig;
        this.hot.runHooks('skipLengthCache', 100);
        this.rowsMapper.createMap(this.hot.countSourceRows());
      }

      this.hot.runHooks('afterUntrimRow', currentTrimConfig, destinationTrimConfig, isValidConfig, isValidConfig && destinationTrimConfig.length < currentTrimConfig.length);
    }
    /**
     * Untrims the row provided as row index (counting from 0).
     *
     * @param {...Number} row Physical row index.
     */

  }, {
    key: "untrimRow",
    value: function untrimRow() {
      for (var _len2 = arguments.length, row = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
        row[_key2] = arguments[_key2];
      }

      this.untrimRows(row);
    }
    /**
     * Checks if given physical row is hidden.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isTrimmed",
    value: function isTrimmed(row) {
      return this.trimmedRows.includes(row);
    }
    /**
     * Untrims all trimmed rows.
     */

  }, {
    key: "untrimAll",
    value: function untrimAll() {
      this.untrimRows([].concat(this.trimmedRows));
    }
    /**
     * Get if trim config is valid.
     *
     * @param {Array} trimmedRows List of physical row indexes.
     * @returns {Boolean}
     */

  }, {
    key: "isValidConfig",
    value: function isValidConfig(trimmedRows) {
      var _this3 = this;

      return trimmedRows.every(function (trimmedRow) {
        return Number.isInteger(trimmedRow) && trimmedRow >= 0 && trimmedRow < _this3.hot.countSourceRows();
      });
    }
    /**
     * On modify row listener.
     *
     * @private
     * @param {Number} row Visual row index.
     * @param {String} source Source name.
     * @returns {Number|null}
     */

  }, {
    key: "onModifyRow",
    value: function onModifyRow(row, source) {
      var physicalRow = row;

      if (source !== this.pluginName) {
        physicalRow = this.rowsMapper.getValueByIndex(physicalRow);
      }

      return physicalRow;
    }
    /**
     * On unmodifyRow listener.
     *
     * @private
     * @param {Number} row Physical row index.
     * @param {String} source Source name.
     * @returns {Number|null}
     */

  }, {
    key: "onUnmodifyRow",
    value: function onUnmodifyRow(row, source) {
      var visualRow = row;

      if (source !== this.pluginName) {
        visualRow = this.rowsMapper.getIndexByValue(visualRow);
      }

      return visualRow;
    }
    /**
     * `beforeCreateRow` hook callback.
     *
     * @private
     * @param {Number} index Index of the newly created row.
     * @param {Number} amount Amount of created rows.
     * @param {String} source Source of the change.
     */

  }, {
    key: "onBeforeCreateRow",
    value: function onBeforeCreateRow(index, amount, source) {
      if (this.isEnabled() && this.trimmedRows.length > 0 && source === 'auto') {
        return false;
      }
    }
    /**
     * On after create row listener.
     *
     * @private
     * @param {Number} index Visual row index.
     * @param {Number} amount Defines how many rows removed.
     */

  }, {
    key: "onAfterCreateRow",
    value: function onAfterCreateRow(index, amount) {
      var _this4 = this;

      this.rowsMapper.shiftItems(index, amount);
      this.trimmedRows = (0, _array.arrayMap)(this.trimmedRows, function (trimmedRow) {
        if (trimmedRow >= _this4.rowsMapper.getValueByIndex(index)) {
          return trimmedRow + amount;
        }

        return trimmedRow;
      });
    }
    /**
     * On before remove row listener.
     *
     * @private
     * @param {Number} index Visual row index.
     * @param {Number} amount Defines how many rows removed.
     *
     * @fires Hooks#modifyRow
     */

  }, {
    key: "onBeforeRemoveRow",
    value: function onBeforeRemoveRow(index, amount) {
      var _this5 = this;

      this.removedRows.length = 0;

      if (index !== false) {
        // Collect physical row index.
        (0, _number.rangeEach)(index, index + amount - 1, function (removedIndex) {
          _this5.removedRows.push(_this5.hot.runHooks('modifyRow', removedIndex, _this5.pluginName));
        });
      }
    }
    /**
     * On after remove row listener.
     *
     * @private
     */

  }, {
    key: "onAfterRemoveRow",
    value: function onAfterRemoveRow() {
      var _this6 = this;

      this.rowsMapper.unshiftItems(this.removedRows); // TODO: Maybe it can be optimized? N x M checks, where N is number of already trimmed rows and M is number of removed rows.
      // Decreasing physical indexes (some of them should be updated, because few indexes are missing in new list of indexes after removal).

      this.trimmedRows = (0, _array.arrayMap)(this.trimmedRows, function (trimmedRow) {
        return trimmedRow - _this6.removedRows.filter(function (removedRow) {
          return removedRow < trimmedRow;
        }).length;
      });
    }
    /**
     * On after load data listener.
     *
     * @private
     * @param {Boolean} firstRun Indicates if hook was fired while Handsontable initialization.
     */

  }, {
    key: "onAfterLoadData",
    value: function onAfterLoadData(firstRun) {
      if (!firstRun) {
        this.rowsMapper.createMap(this.hot.countSourceRows());
      }
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.rowsMapper.destroy();

      _get(_getPrototypeOf(TrimRows.prototype), "destroy", this).call(this);
    }
  }]);

  return TrimRows;
}(_base.default);

(0, _plugins.registerPlugin)('trimRows', TrimRows);
var _default = TrimRows;
exports.default = _default;