"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.concat");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _array = require("../../../helpers/array");

var _number = require("../../../helpers/number");

var _element = require("../../../helpers/dom/element");

var _base = _interopRequireDefault(require("./_base"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * Class responsible for the UI in the Nested Rows' row headers.
 *
 * @class HeadersUI
 * @util
 * @extends BaseUI
 */
var HeadersUI =
/*#__PURE__*/
function (_BaseUI) {
  _inherits(HeadersUI, _BaseUI);

  _createClass(HeadersUI, null, [{
    key: "CSS_CLASSES",

    /**
     * CSS classes used in the row headers.
     *
     * @type {Object}
     */
    get: function get() {
      return {
        indicatorContainer: 'ht_nestingLevels',
        parent: 'ht_nestingParent',
        indicator: 'ht_nestingLevel',
        emptyIndicator: 'ht_nestingLevel_empty',
        button: 'ht_nestingButton',
        expandButton: 'ht_nestingExpand',
        collapseButton: 'ht_nestingCollapse'
      };
    }
  }]);

  function HeadersUI(nestedRowsPlugin, hotInstance) {
    var _this;

    _classCallCheck(this, HeadersUI);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(HeadersUI).call(this, nestedRowsPlugin, hotInstance));
    /**
     * Reference to the DataManager instance connected with the Nested Rows plugin.
     *
     * @type {DataManager}
     */

    _this.dataManager = _this.plugin.dataManager; // /**
    //  * Level cache array.
    //  *
    //  * @type {Array}
    //  */
    // this.levelCache = this.dataManager.cache.levels;

    /**
     * Reference to the CollapsingUI instance connected with the Nested Rows plugin.
     *
     * @type {CollapsingUI}
     */

    _this.collapsingUI = _this.plugin.collapsingUI;
    /**
     * Cache for the row headers width.
     *
     * @type {null|Number}
     */

    _this.rowHeaderWidthCache = null;
    /**
     * Reference to the TrimRows instance connected with the Nested Rows plugin.
     *
     * @type {TrimRows}
     */

    _this.trimRowsPlugin = nestedRowsPlugin.trimRowsPlugin;
    return _this;
  }
  /**
   * Append nesting indicators and buttons to the row headers.
   *
   * @private
   * @param {Number} row Row index.
   * @param {HTMLElement} TH TH 3element.
   */


  _createClass(HeadersUI, [{
    key: "appendLevelIndicators",
    value: function appendLevelIndicators(row, TH) {
      var rowIndex = this.trimRowsPlugin.rowsMapper.getValueByIndex(row);
      var rowLevel = this.dataManager.getRowLevel(rowIndex);
      var rowObject = this.dataManager.getDataObject(rowIndex);
      var innerDiv = TH.getElementsByTagName('DIV')[0];
      var innerSpan = innerDiv.querySelector('span.rowHeader');
      var previousIndicators = innerDiv.querySelectorAll('[class^="ht_nesting"]');
      (0, _array.arrayEach)(previousIndicators, function (elem) {
        if (elem) {
          innerDiv.removeChild(elem);
        }
      });
      (0, _element.addClass)(TH, HeadersUI.CSS_CLASSES.indicatorContainer);

      if (rowLevel) {
        var rootDocument = this.hot.rootDocument;
        var initialContent = innerSpan.cloneNode(true);
        innerDiv.innerHTML = '';
        (0, _number.rangeEach)(0, rowLevel - 1, function () {
          var levelIndicator = rootDocument.createElement('SPAN');
          (0, _element.addClass)(levelIndicator, HeadersUI.CSS_CLASSES.emptyIndicator);
          innerDiv.appendChild(levelIndicator);
        });
        innerDiv.appendChild(initialContent);
      }

      if (this.dataManager.hasChildren(rowObject)) {
        var buttonsContainer = this.hot.rootDocument.createElement('DIV');
        (0, _element.addClass)(TH, HeadersUI.CSS_CLASSES.parent);

        if (this.collapsingUI.areChildrenCollapsed(rowIndex)) {
          (0, _element.addClass)(buttonsContainer, "".concat(HeadersUI.CSS_CLASSES.button, " ").concat(HeadersUI.CSS_CLASSES.expandButton));
        } else {
          (0, _element.addClass)(buttonsContainer, "".concat(HeadersUI.CSS_CLASSES.button, " ").concat(HeadersUI.CSS_CLASSES.collapseButton));
        }

        innerDiv.appendChild(buttonsContainer);
      }
    }
    /**
     * Update the row header width according to number of levels in the dataset.
     *
     * @private
     * @param {Number} deepestLevel Cached deepest level of nesting.
     */

  }, {
    key: "updateRowHeaderWidth",
    value: function updateRowHeaderWidth(deepestLevel) {
      var deepestLevelIndex = deepestLevel;

      if (!deepestLevelIndex) {
        deepestLevelIndex = this.dataManager.cache.levelCount;
      }

      this.rowHeaderWidthCache = Math.max(50, 11 + 10 * deepestLevelIndex + 25);
      this.hot.render();
    }
  }]);

  return HeadersUI;
}(_base.default);

var _default = HeadersUI;
exports.default = _default;