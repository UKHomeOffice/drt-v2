"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.from");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.slice");

require("core-js/modules/es.array.splice");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _event = require("../../../helpers/dom/event");

var _array = require("../../../helpers/array");

var _number = require("../../../helpers/number");

var _element = require("../../../helpers/dom/element");

var _base = _interopRequireDefault(require("./_base"));

var _headers = _interopRequireDefault(require("./headers"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _toConsumableArray(arr) { return _arrayWithoutHoles(arr) || _iterableToArray(arr) || _nonIterableSpread(); }

function _nonIterableSpread() { throw new TypeError("Invalid attempt to spread non-iterable instance"); }

function _iterableToArray(iter) { if (Symbol.iterator in Object(iter) || Object.prototype.toString.call(iter) === "[object Arguments]") return Array.from(iter); }

function _arrayWithoutHoles(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = new Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * Class responsible for the UI for collapsing and expanding groups.
 *
 * @class
 * @util
 * @extends BaseUI
 */
var CollapsingUI =
/*#__PURE__*/
function (_BaseUI) {
  _inherits(CollapsingUI, _BaseUI);

  function CollapsingUI(nestedRowsPlugin, hotInstance) {
    var _this;

    _classCallCheck(this, CollapsingUI);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(CollapsingUI).call(this, nestedRowsPlugin, hotInstance));
    /**
     * Reference to the Trim Rows plugin.
     */

    _this.trimRowsPlugin = nestedRowsPlugin.trimRowsPlugin;
    _this.dataManager = _this.plugin.dataManager;
    _this.collapsedRows = [];
    _this.collapsedRowsStash = {
      stash: function stash() {
        _this.lastCollapsedRows = _this.collapsedRows.slice(0); // Workaround for wrong indexes being set in the trimRows plugin

        _this.expandMultipleChildren(_this.lastCollapsedRows, false);
      },
      shiftStash: function shiftStash(index) {
        var delta = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 1;

        var elementIndex = _this.translateTrimmedRow(index);

        (0, _array.arrayEach)(_this.lastCollapsedRows, function (elem, i) {
          if (elem > elementIndex - 1) {
            _this.lastCollapsedRows[i] = elem + delta;
          }
        });
      },
      applyStash: function applyStash() {
        // Workaround for wrong indexes being set in the trimRows plugin
        _this.hot.runHooks('skipLengthCache', 100);

        _this.collapseMultipleChildren(_this.lastCollapsedRows, true);

        _this.lastCollapsedRows = void 0;
      },
      trimStash: function trimStash(realElementIndex, amount) {
        (0, _number.rangeEach)(realElementIndex, realElementIndex + amount - 1, function (i) {
          var indexOfElement = _this.lastCollapsedRows.indexOf(i);

          if (indexOfElement > -1) {
            _this.lastCollapsedRows.splice(indexOfElement, 1);
          }
        });
      }
    };
    return _this;
  }
  /**
   * Collapse the children of the row passed as an argument.
   *
   * @param {Number|Object} row The parent row.
   * @param {Boolean} [forceRender=true] Whether to render the table after the function ends.
   */


  _createClass(CollapsingUI, [{
    key: "collapseChildren",
    value: function collapseChildren(row) {
      var _this2 = this;

      var forceRender = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : true;
      var rowsToCollapse = [];
      var rowObject = null;
      var rowIndex = null;
      var rowsToTrim = null;

      if (isNaN(row)) {
        rowObject = row;
        rowIndex = this.dataManager.getRowIndex(rowObject);
      } else {
        rowObject = this.dataManager.getDataObject(row);
        rowIndex = row;
      }

      if (this.dataManager.hasChildren(rowObject)) {
        (0, _array.arrayEach)(rowObject.__children, function (elem) {
          rowsToCollapse.push(_this2.dataManager.getRowIndex(elem));
        });
      }

      rowsToTrim = this.collapseRows(rowsToCollapse, true, false);

      if (doTrimming) {
        this.trimRowsPlugin.trimRows(rowsToTrim);
      }

      if (forceRender) {
        this.renderAndAdjust();
      }

      if (this.collapsedRows.indexOf(rowIndex) === -1) {
        this.collapsedRows.push(rowIndex);
      }

      return rowsToTrim;
    }
    /**
     * Collapse multiple children.
     *
     * @param {Array} rows Rows to collapse (including their children)
     * @param {Boolean} [forceRender = true] `true` if the table should be rendered after finishing the function.
     * @param {Boolean} [doTrimming = true] `true` if the table should trim the provided rows.
     */

  }, {
    key: "collapseMultipleChildren",
    value: function collapseMultipleChildren(rows) {
      var _this3 = this;

      var forceRender = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : true;
      var rowsToTrim = [];
      (0, _array.arrayEach)(rows, function (elem) {
        rowsToTrim.push.apply(rowsToTrim, _toConsumableArray(_this3.collapseChildren(elem, false, false)));
      });

      if (doTrimming) {
        this.trimRowsPlugin.trimRows(rowsToTrim);
      }

      if (forceRender) {
        this.renderAndAdjust();
      }
    }
    /**
     * Collapse a single row.
     *
     * @param {Number} rowIndex Index of the row to collapse.
     * @param {Boolean} [recursive = true] `true` if it should collapse the row's children.
     */

  }, {
    key: "collapseRow",
    value: function collapseRow(rowIndex) {
      var recursive = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      this.collapseRows([rowIndex], recursive);
    }
    /**
     * Collapse multiple rows.
     *
     * @param {Array} rowIndexes Array of row indexes to collapse.
     * @param {Boolean} [recursive = true] `true` if it should collapse the rows' children.
     * @param {Boolean} [doTrimming = false] `true` if the provided rows should be collapsed.
     * @returns {Array} Rows prepared for trimming (or trimmed, if doTrimming == true)
     */

  }, {
    key: "collapseRows",
    value: function collapseRows(rowIndexes) {
      var _this4 = this;

      var recursive = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : false;
      var rowsToTrim = [];
      (0, _array.arrayEach)(rowIndexes, function (elem) {
        rowsToTrim.push(elem);

        if (recursive) {
          _this4.collapseChildRows(elem, rowsToTrim);
        }
      });

      if (doTrimming) {
        this.trimRowsPlugin.trimRows(rowsToTrim);
      }

      return rowsToTrim;
    }
    /**
     * Collapse child rows of the row at the provided index.
     *
     * @param {Number} parentIndex Index of the parent node.
     * @param {Array} [rowsToTrim = []] Array of rows to trim. Defaults to an empty array.
     * @param {Boolean} [recursive] `true` if the collapsing process should be recursive.
     * @param {Boolean} [doTrimming = false] `true` if rows should be trimmed.
     */

  }, {
    key: "collapseChildRows",
    value: function collapseChildRows(parentIndex) {
      var _this5 = this;

      var rowsToTrim = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
      var recursive = arguments.length > 2 ? arguments[2] : undefined;
      var doTrimming = arguments.length > 3 && arguments[3] !== undefined ? arguments[3] : false;

      if (this.dataManager.hasChildren(parentIndex)) {
        var parentObject = this.dataManager.getDataObject(parentIndex);
        (0, _array.arrayEach)(parentObject.__children, function (elem) {
          var elemIndex = _this5.dataManager.getRowIndex(elem);

          rowsToTrim.push(elemIndex);

          _this5.collapseChildRows(elemIndex, rowsToTrim);
        });
      }

      if (doTrimming) {
        this.trimRowsPlugin.trimRows(rowsToTrim);
      }
    }
    /**
     * Expand a single row.
     *
     * @param {Number} rowIndex Index of the row to expand.
     * @param {Boolean} [recursive = true] `true` if it should expand the row's children recursively.
     */

  }, {
    key: "expandRow",
    value: function expandRow(rowIndex) {
      var recursive = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      this.expandRows([rowIndex], recursive);
    }
    /**
     * Expand multiple rows.
     *
     * @param {Array} rowIndexes Array of indexes of the rows to expand.
     * @param {Boolean} [recursive = true] `true` if it should expand the rows' children recursively.
     * @param {Boolean} [doTrimming = false] `true` if rows should be untrimmed.
     * @returns {Array} Array of row indexes to be untrimmed.
     */

  }, {
    key: "expandRows",
    value: function expandRows(rowIndexes) {
      var _this6 = this;

      var recursive = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : false;
      var rowsToUntrim = [];
      (0, _array.arrayEach)(rowIndexes, function (elem) {
        rowsToUntrim.push(elem);

        if (recursive) {
          _this6.expandChildRows(elem, rowsToUntrim);
        }
      });

      if (doTrimming) {
        this.trimRowsPlugin.untrimRows(rowsToUntrim);
      }

      return rowsToUntrim;
    }
    /**
     * Expand child rows of the provided index.
     *
     * @param {Number} parentIndex Index of the parent row.
     * @param {Array} [rowsToUntrim = []] Array of the rows to be untrimmed.
     * @param {Boolean} [recursive] `true` if it should expand the rows' children recursively.
     * @param {Boolean} [doTrimming = false] `true` if rows should be untrimmed.
     */

  }, {
    key: "expandChildRows",
    value: function expandChildRows(parentIndex) {
      var _this7 = this;

      var rowsToUntrim = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
      var recursive = arguments.length > 2 ? arguments[2] : undefined;
      var doTrimming = arguments.length > 3 && arguments[3] !== undefined ? arguments[3] : false;

      if (this.dataManager.hasChildren(parentIndex)) {
        var parentObject = this.dataManager.getDataObject(parentIndex);
        (0, _array.arrayEach)(parentObject.__children, function (elem) {
          if (!_this7.isAnyParentCollapsed(elem)) {
            var elemIndex = _this7.dataManager.getRowIndex(elem);

            rowsToUntrim.push(elemIndex);

            _this7.expandChildRows(elemIndex, rowsToUntrim);
          }
        });
      }

      if (doTrimming) {
        this.trimRowsPlugin.untrimRows(rowsToUntrim);
      }
    }
    /**
     * Expand the children of the row passed as an argument.
     *
     * @param {Number|Object} row Parent row.
     * @param {Boolean} [forceRender=true] Whether to render the table after the function ends.
     * @param {Boolean} [doTrimming=true] If set to `true`, the trimming will be applied when the function finishes.
     */

  }, {
    key: "expandChildren",
    value: function expandChildren(row) {
      var _this8 = this;

      var forceRender = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : true;
      var rowsToExpand = [];
      var rowObject = null;
      var rowIndex = null;
      var rowsToUntrim = null;

      if (isNaN(row)) {
        rowObject = row;
        rowIndex = this.dataManager.getRowIndex(row);
      } else {
        rowObject = this.dataManager.getDataObject(row);
        rowIndex = row;
      }

      this.collapsedRows.splice(this.collapsedRows.indexOf(rowIndex), 1);

      if (this.dataManager.hasChildren(rowObject)) {
        (0, _array.arrayEach)(rowObject.__children, function (elem) {
          var childIndex = _this8.dataManager.getRowIndex(elem);

          rowsToExpand.push(childIndex);
        });
      }

      rowsToUntrim = this.expandRows(rowsToExpand, true, false);

      if (doTrimming) {
        this.trimRowsPlugin.untrimRows(rowsToUntrim);
      }

      if (forceRender) {
        this.renderAndAdjust();
      }

      return rowsToUntrim;
    }
    /**
     * Expand multiple rows' children.
     *
     * @param {Array} rows Array of rows which children are about to be expanded.
     * @param {Boolean} [forceRender = true] `true` if the table should render after finishing the function.
     * @param {Boolean} [doTrimming = true] `true` if the rows should be untrimmed after finishing the function.
     */

  }, {
    key: "expandMultipleChildren",
    value: function expandMultipleChildren(rows) {
      var _this9 = this;

      var forceRender = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var doTrimming = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : true;
      var rowsToUntrim = [];
      (0, _array.arrayEach)(rows, function (elem) {
        rowsToUntrim.push.apply(rowsToUntrim, _toConsumableArray(_this9.expandChildren(elem, false, false)));
      });

      if (doTrimming) {
        this.trimRowsPlugin.untrimRows(rowsToUntrim);
      }

      if (forceRender) {
        this.renderAndAdjust();
      }
    }
    /**
     * Collapse all collapsable rows.
     */

  }, {
    key: "collapseAll",
    value: function collapseAll() {
      var _this10 = this;

      var sourceData = this.hot.getSourceData();
      var parentsToCollapse = [];
      (0, _array.arrayEach)(sourceData, function (elem) {
        if (_this10.dataManager.hasChildren(elem)) {
          parentsToCollapse.push(elem);
        }
      });
      this.collapseMultipleChildren(parentsToCollapse);
      this.renderAndAdjust();
    }
    /**
     * Expand all collapsable rows.
     */

  }, {
    key: "expandAll",
    value: function expandAll() {
      var _this11 = this;

      var sourceData = this.hot.getSourceData();
      var parentsToExpand = [];
      (0, _array.arrayEach)(sourceData, function (elem) {
        if (_this11.dataManager.hasChildren(elem)) {
          parentsToExpand.push(elem);
        }
      });
      this.expandMultipleChildren(parentsToExpand);
      this.renderAndAdjust();
    }
    /**
     * Check if all child rows are collapsed.
     *
     * @param {Number|Object} row The parent row.
     * @private
     */

  }, {
    key: "areChildrenCollapsed",
    value: function areChildrenCollapsed(row) {
      var _this12 = this;

      var rowObj = null;
      var allCollapsed = true;

      if (isNaN(row)) {
        rowObj = row;
      } else {
        rowObj = this.dataManager.getDataObject(row);
      }

      if (this.dataManager.hasChildren(rowObj)) {
        (0, _array.arrayEach)(rowObj.__children, function (elem) {
          var rowIndex = _this12.dataManager.getRowIndex(elem);

          if (!_this12.trimRowsPlugin.isTrimmed(rowIndex)) {
            allCollapsed = false;
            return false;
          }
        });
      }

      return allCollapsed;
    }
    /**
     * Check if any of the row object parents are collapsed.
     *
     * @private
     * @param {Object} rowObj Row object.
     * @returns {Boolean}
     */

  }, {
    key: "isAnyParentCollapsed",
    value: function isAnyParentCollapsed(rowObj) {
      var parent = rowObj;

      while (parent !== null) {
        parent = this.dataManager.getRowParent(parent);
        var parentIndex = this.dataManager.getRowIndex(parent);

        if (this.collapsedRows.indexOf(parentIndex) > -1) {
          return true;
        }
      }

      return false;
    }
    /**
     * Toggle collapsed state. Callback for the `beforeOnCellMousedown` hook.
     *
     * @private
     * @param {MouseEvent} event `mousedown` event
     * @param {Object} coords Coordinates of the clicked cell/header.
     */

  }, {
    key: "toggleState",
    value: function toggleState(event, coords) {
      if (coords.col >= 0) {
        return;
      }

      var row = this.translateTrimmedRow(coords.row);

      if ((0, _element.hasClass)(event.target, _headers.default.CSS_CLASSES.button)) {
        if (this.areChildrenCollapsed(row)) {
          this.expandChildren(row);
        } else {
          this.collapseChildren(row);
        }

        (0, _event.stopImmediatePropagation)(event);
      }
    }
    /**
     * Translate physical row after trimming to physical base row index.
     *
     * @private
     * @param {Number} row Row index.
     * @returns {Number} Base row index.
     */

  }, {
    key: "translateTrimmedRow",
    value: function translateTrimmedRow(row) {
      return this.trimRowsPlugin.rowsMapper.getValueByIndex(row);
    }
    /**
     * Helper function to render the table and call the `adjustElementsSize` method.
     *
     * @private
     */

  }, {
    key: "renderAndAdjust",
    value: function renderAndAdjust() {
      this.hot.render(); // Dirty workaround to prevent scroll height not adjusting to the table height. Needs refactoring in the future.

      this.hot.view.wt.wtOverlays.adjustElementsSize();
    }
  }]);

  return CollapsingUI;
}(_base.default);

var _default = CollapsingUI;
exports.default = _default;