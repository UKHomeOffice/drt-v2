import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.string.iterator";
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

import BasePlugin from '../../plugins/_base';
import { rangeEach } from '../../helpers/number';
import { registerPlugin } from '../../plugins';
import BindStrategy from './bindStrategy';
/**
 * @plugin BindRowsWithHeaders
 *
 * @description
 * Plugin allows binding the table rows with their headers.
 *
 * If the plugin is enabled, the table row headers will "stick" to the rows, when they are hidden/moved. Basically, if
 * at the initialization row 0 has a header titled "A", it will have it no matter what you do with the table.
 *
 * @example
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   // enable plugin
 *   bindRowsWithHeaders: true
 * });
 * ```
 */

var BindRowsWithHeaders =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(BindRowsWithHeaders, _BasePlugin);

  function BindRowsWithHeaders(hotInstance) {
    var _this;

    _classCallCheck(this, BindRowsWithHeaders);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(BindRowsWithHeaders).call(this, hotInstance));
    /**
     * Strategy object for binding rows with headers.
     *
     * @private
     * @type {BindStrategy}
     */

    _this.bindStrategy = new BindStrategy();
    /**
     * List of last removed row indexes.
     *
     * @private
     * @type {Array}
     */

    _this.removedRows = [];
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link BindRowsWithHeaders#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(BindRowsWithHeaders, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().bindRowsWithHeaders;
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

      var bindStrategy = this.hot.getSettings().bindRowsWithHeaders;

      if (typeof bindStrategy !== 'string') {
        bindStrategy = BindStrategy.DEFAULT_STRATEGY;
      }

      this.bindStrategy.setStrategy(bindStrategy);
      this.bindStrategy.createMap(this.hot.countSourceRows());
      this.addHook('modifyRowHeader', function (row) {
        return _this2.onModifyRowHeader(row);
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

      _get(_getPrototypeOf(BindRowsWithHeaders.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      _get(_getPrototypeOf(BindRowsWithHeaders.prototype), "updatePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.removedRows.length = 0;
      this.bindStrategy.clearMap();

      _get(_getPrototypeOf(BindRowsWithHeaders.prototype), "disablePlugin", this).call(this);
    }
    /**
     * On modify row header listener.
     *
     * @private
     * @param {Number} row Row index.
     * @returns {Number}
     *
     * @fires Hooks#modifyRow
     */

  }, {
    key: "onModifyRowHeader",
    value: function onModifyRowHeader(row) {
      return this.bindStrategy.translate(this.hot.runHooks('modifyRow', row));
    }
    /**
     * On after create row listener.
     *
     * @private
     * @param {Number} index Row index.
     * @param {Number} amount Defines how many rows removed.
     */

  }, {
    key: "onAfterCreateRow",
    value: function onAfterCreateRow(index, amount) {
      this.bindStrategy.createRow(index, amount);
    }
    /**
     * On before remove row listener.
     *
     * @private
     * @param {Number} index Row index.
     * @param {Number} amount Defines how many rows removed.
     *
     * @fires Hooks#modifyRow
     */

  }, {
    key: "onBeforeRemoveRow",
    value: function onBeforeRemoveRow(index, amount) {
      var _this3 = this;

      this.removedRows.length = 0;

      if (index !== false) {
        // Collect physical row index.
        rangeEach(index, index + amount - 1, function (removedIndex) {
          _this3.removedRows.push(_this3.hot.runHooks('modifyRow', removedIndex));
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
      this.bindStrategy.removeRow(this.removedRows);
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
        this.bindStrategy.createMap(this.hot.countSourceRows());
      }
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.bindStrategy.destroy();

      _get(_getPrototypeOf(BindRowsWithHeaders.prototype), "destroy", this).call(this);
    }
  }]);

  return BindRowsWithHeaders;
}(BasePlugin);

registerPlugin('bindRowsWithHeaders', BindRowsWithHeaders);
export default BindRowsWithHeaders;