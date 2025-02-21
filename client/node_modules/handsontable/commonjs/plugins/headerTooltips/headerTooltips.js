"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _element = require("../../helpers/dom/element");

var _plugins = require("../../plugins");

var _number = require("../../helpers/number");

var _base = _interopRequireDefault(require("../_base"));

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
 * @plugin HeaderTooltips
 *
 * @description
 * Allows to add a tooltip to the table headers.
 *
 * Available options:
 * * the `rows` property defines if tooltips should be added to row headers,
 * * the `columns` property defines if tooltips should be added to column headers,
 * * the `onlyTrimmed` property defines if tooltips should be added only to headers, which content is trimmed by the header itself (the content being wider then the header).
 *
 * @example
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   // enable and configure header tooltips
 *   headerTooltips: {
 *     rows: true,
 *     columns: true,
 *     onlyTrimmed: false
 *   }
 * });
 * ```
 */
var HeaderTooltips =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(HeaderTooltips, _BasePlugin);

  function HeaderTooltips(hotInstance) {
    var _this;

    _classCallCheck(this, HeaderTooltips);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(HeaderTooltips).call(this, hotInstance));
    /**
     * Cached plugin settings.
     *
     * @private
     * @type {Boolean|Object}
     */

    _this.settings = null;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link HeaderTooltips#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(HeaderTooltips, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().headerTooltips;
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

      this.settings = this.hot.getSettings().headerTooltips;
      this.parseSettings();
      this.addHook('afterGetColHeader', function (col, TH) {
        return _this2.onAfterGetHeader(col, TH);
      });
      this.addHook('afterGetRowHeader', function (col, TH) {
        return _this2.onAfterGetHeader(col, TH);
      });

      _get(_getPrototypeOf(HeaderTooltips.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.settings = null;
      this.clearTitleAttributes();

      _get(_getPrototypeOf(HeaderTooltips.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Parses the plugin settings.
     *
     * @private
     */

  }, {
    key: "parseSettings",
    value: function parseSettings() {
      if (typeof this.settings === 'boolean') {
        this.settings = {
          rows: true,
          columns: true,
          onlyTrimmed: false
        };
      }
    }
    /**
     * Clears the previously assigned title attributes.
     *
     * @private
     */

  }, {
    key: "clearTitleAttributes",
    value: function clearTitleAttributes() {
      var headerLevels = this.hot.view.wt.getSetting('columnHeaders').length;
      var mainHeaders = this.hot.view.wt.wtTable.THEAD;
      var topHeaders = this.hot.view.wt.wtOverlays.topOverlay.clone.wtTable.THEAD;
      var topLeftCornerOverlay = this.hot.view.wt.wtOverlays.topLeftCornerOverlay;
      var topLeftCornerHeaders = topLeftCornerOverlay ? topLeftCornerOverlay.clone.wtTable.THEAD : null;
      (0, _number.rangeEach)(0, headerLevels - 1, function (i) {
        var masterLevel = mainHeaders.childNodes[i];
        var topLevel = topHeaders.childNodes[i];
        var topLeftCornerLevel = topLeftCornerHeaders ? topLeftCornerHeaders.childNodes[i] : null;
        (0, _number.rangeEach)(0, masterLevel.childNodes.length - 1, function (j) {
          masterLevel.childNodes[j].removeAttribute('title');

          if (topLevel && topLevel.childNodes[j]) {
            topLevel.childNodes[j].removeAttribute('title');
          }

          if (topLeftCornerHeaders && topLeftCornerLevel && topLeftCornerLevel.childNodes[j]) {
            topLeftCornerLevel.childNodes[j].removeAttribute('title');
          }
        });
      }, true);
    }
    /**
     * Adds a tooltip to the headers.
     *
     * @private
     * @param {Number} index
     * @param {HTMLElement} TH
     */

  }, {
    key: "onAfterGetHeader",
    value: function onAfterGetHeader(index, TH) {
      var innerSpan = TH.querySelector('span');
      var isColHeader = TH.parentNode.parentNode.nodeName === 'THEAD';

      if (isColHeader && this.settings.columns || !isColHeader && this.settings.rows) {
        if (this.settings.onlyTrimmed) {
          if ((0, _element.outerWidth)(innerSpan) >= (0, _element.outerWidth)(TH) && (0, _element.outerWidth)(innerSpan) !== 0) {
            TH.setAttribute('title', innerSpan.textContent);
          }
        } else {
          TH.setAttribute('title', innerSpan.textContent);
        }
      }
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.settings = null;

      _get(_getPrototypeOf(HeaderTooltips.prototype), "destroy", this).call(this);
    }
  }]);

  return HeaderTooltips;
}(_base.default);

(0, _plugins.registerPlugin)('headerTooltips', HeaderTooltips);
var _default = HeaderTooltips;
exports.default = _default;