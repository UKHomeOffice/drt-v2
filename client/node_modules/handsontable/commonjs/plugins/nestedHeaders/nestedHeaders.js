"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.reverse");

require("core-js/modules/es.array.slice");

require("core-js/modules/es.object.freeze");

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

var _number = require("../../helpers/number");

var _array = require("../../helpers/array");

var _object = require("../../helpers/object");

var _templateLiteralTag = require("../../helpers/templateLiteralTag");

var _console = require("../../helpers/console");

var _plugins = require("../../plugins");

var _base = _interopRequireDefault(require("../_base"));

var _src = require("../../3rdparty/walkontable/src");

var _ghostTable = _interopRequireDefault(require("./utils/ghostTable"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _templateObject2() {
  var data = _taggedTemplateLiteral(["Your Nested Headers plugin setup contains overlapping headers. This kind of configuration\n                is currently not supported and might result in glitches."]);

  _templateObject2 = function _templateObject2() {
    return data;
  };

  return data;
}

function _templateObject() {
  var data = _taggedTemplateLiteral(["You have declared a Nested Header overlapping the Fixed Columns section - it may lead to visual\n          glitches. To prevent that kind of problems, split the nested headers between the fixed and non-fixed columns."]);

  _templateObject = function _templateObject() {
    return data;
  };

  return data;
}

function _taggedTemplateLiteral(strings, raw) { if (!raw) { raw = strings.slice(0); } return Object.freeze(Object.defineProperties(strings, { raw: { value: Object.freeze(raw) } })); }

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
 * @plugin NestedHeaders
 * @description
 * The plugin allows to create a nested header structure, using the HTML's colspan attribute.
 *
 * To make any header wider (covering multiple table columns), it's corresponding configuration array element should be
 * provided as an object with `label` and `colspan` properties. The `label` property defines the header's label,
 * while the `colspan` property defines a number of columns that the header should cover.
 *
 * __Note__ that the plugin supports a *nested* structure, which means, any header cannot be wider than it's "parent". In
 * other words, headers cannot overlap each other.
 * @example
 *
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   date: getData(),
 *   nestedHeaders: [
 *           ['A', {label: 'B', colspan: 8}, 'C'],
 *           ['D', {label: 'E', colspan: 4}, {label: 'F', colspan: 4}, 'G'],
 *           ['H', {label: 'I', colspan: 2}, {label: 'J', colspan: 2}, {label: 'K', colspan: 2}, {label: 'L', colspan: 2}, 'M'],
 *           ['N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W']
 *  ],
 * ```
 */
var NestedHeaders =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(NestedHeaders, _BasePlugin);

  function NestedHeaders(hotInstance) {
    var _this2;

    _classCallCheck(this, NestedHeaders);

    _this2 = _possibleConstructorReturn(this, _getPrototypeOf(NestedHeaders).call(this, hotInstance));
    /**
     * Nasted headers cached settings.
     *
     * @private
     * @type {Object}
     */

    _this2.settings = [];
    /**
     * Cached number of column header levels.
     *
     * @private
     * @type {Number}
     */

    _this2.columnHeaderLevelCount = 0;
    /**
     * Array of nested headers' colspans.
     *
     * @private
     * @type {Array}
     */

    _this2.colspanArray = [];
    /**
     * Custom helper for getting widths of the nested headers.
     * @TODO This should be changed after refactor handsontable/utils/ghostTable.
     *
     * @private
     * @type {GhostTable}
     */

    _this2.ghostTable = new _ghostTable.default(_assertThisInitialized(_this2));
    return _this2;
  }
  /**
   * Check if plugin is enabled
   *
   * @returns {Boolean}
   */


  _createClass(NestedHeaders, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().nestedHeaders;
    }
    /**
     * Enables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "enablePlugin",
    value: function enablePlugin() {
      var _this3 = this;

      if (this.enabled) {
        return;
      }

      this.settings = this.hot.getSettings().nestedHeaders;
      this.addHook('afterGetColumnHeaderRenderers', function (array) {
        return _this3.onAfterGetColumnHeaderRenderers(array);
      });
      this.addHook('afterInit', function () {
        return _this3.onAfterInit();
      });
      this.addHook('afterOnCellMouseDown', function (event, coords) {
        return _this3.onAfterOnCellMouseDown(event, coords);
      });
      this.addHook('beforeOnCellMouseOver', function (event, coords, TD, blockCalculations) {
        return _this3.onBeforeOnCellMouseOver(event, coords, TD, blockCalculations);
      });
      this.addHook('afterViewportColumnCalculatorOverride', function (calc) {
        return _this3.onAfterViewportColumnCalculatorOverride(calc);
      });
      this.addHook('modifyColWidth', function (width, column) {
        return _this3.onModifyColWidth(width, column);
      });
      this.setupColspanArray();
      this.checkForFixedColumnsCollision();
      this.columnHeaderLevelCount = this.hot.view ? this.hot.view.wt.getSetting('columnHeaders').length : 0;

      _get(_getPrototypeOf(NestedHeaders.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.clearColspans();
      this.settings = [];
      this.columnHeaderLevelCount = 0;
      this.colspanArray = [];
      this.ghostTable.clear();

      _get(_getPrototypeOf(NestedHeaders.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Updates the plugin state. This method is executed when {@link Core#updateSettings} is invoked.
     */

  }, {
    key: "updatePlugin",
    value: function updatePlugin() {
      this.disablePlugin();
      this.enablePlugin();

      _get(_getPrototypeOf(NestedHeaders.prototype), "updatePlugin", this).call(this);

      this.ghostTable.buildWidthsMapper();
    }
    /**
     * Clear the colspans remaining after plugin usage.
     *
     * @private
     */

  }, {
    key: "clearColspans",
    value: function clearColspans() {
      if (!this.hot.view) {
        return;
      }

      var headerLevels = this.hot.view.wt.getSetting('columnHeaders').length;
      var mainHeaders = this.hot.view.wt.wtTable.THEAD;
      var topHeaders = this.hot.view.wt.wtOverlays.topOverlay.clone.wtTable.THEAD;
      var topLeftCornerHeaders = this.hot.view.wt.wtOverlays.topLeftCornerOverlay ? this.hot.view.wt.wtOverlays.topLeftCornerOverlay.clone.wtTable.THEAD : null;

      for (var i = 0; i < headerLevels; i++) {
        var masterLevel = mainHeaders.childNodes[i];

        if (!masterLevel) {
          break;
        }

        var topLevel = topHeaders.childNodes[i];
        var topLeftCornerLevel = topLeftCornerHeaders ? topLeftCornerHeaders.childNodes[i] : null;

        for (var j = 0, masterNodes = masterLevel.childNodes.length; j < masterNodes; j++) {
          masterLevel.childNodes[j].removeAttribute('colspan');

          if (topLevel && topLevel.childNodes[j]) {
            topLevel.childNodes[j].removeAttribute('colspan');
          }

          if (topLeftCornerHeaders && topLeftCornerLevel && topLeftCornerLevel.childNodes[j]) {
            topLeftCornerLevel.childNodes[j].removeAttribute('colspan');
          }
        }
      }
    }
    /**
     * Check if the nested headers overlap the fixed columns overlay, if so - display a warning.
     *
     * @private
     */

  }, {
    key: "checkForFixedColumnsCollision",
    value: function checkForFixedColumnsCollision() {
      var _this4 = this;

      var fixedColumnsLeft = this.hot.getSettings().fixedColumnsLeft;
      (0, _array.arrayEach)(this.colspanArray, function (value, i) {
        if (_this4.getNestedParent(i, fixedColumnsLeft) !== fixedColumnsLeft) {
          (0, _console.warn)((0, _templateLiteralTag.toSingleLine)(_templateObject()));
        }
      });
    }
    /**
     * Check if the configuration contains overlapping headers.
     *
     * @private
     */

  }, {
    key: "checkForOverlappingHeaders",
    value: function checkForOverlappingHeaders() {
      var _this5 = this;

      (0, _array.arrayEach)(this.colspanArray, function (level, i) {
        (0, _array.arrayEach)(_this5.colspanArray[i], function (header, j) {
          if (header.colspan > 1) {
            var row = _this5.levelToRowCoords(i);

            var childHeaders = _this5.getChildHeaders(row, j);

            if (childHeaders.length > 0) {
              var childColspanSum = 0;
              (0, _array.arrayEach)(childHeaders, function (col) {
                childColspanSum += _this5.getColspan(row + 1, col);
              });

              if (childColspanSum > header.colspan) {
                (0, _console.warn)((0, _templateLiteralTag.toSingleLine)(_templateObject2()));
              }

              return false;
            }
          }
        });
      });
    }
    /**
     * Create an internal array containing information of the headers with a colspan attribute.
     *
     * @private
     */

  }, {
    key: "setupColspanArray",
    value: function setupColspanArray() {
      var _this6 = this;

      function checkIfExists(array, index) {
        if (!array[index]) {
          array[index] = [];
        }
      }

      (0, _object.objectEach)(this.settings, function (levelValues, level) {
        (0, _object.objectEach)(levelValues, function (val, col, levelValue) {
          checkIfExists(_this6.colspanArray, level);

          if (levelValue[col].colspan === void 0) {
            _this6.colspanArray[level].push({
              label: levelValue[col] || '',
              colspan: 1,
              hidden: false
            });
          } else {
            var colspan = levelValue[col].colspan || 1;

            _this6.colspanArray[level].push({
              label: levelValue[col].label || '',
              colspan: colspan,
              hidden: false
            });

            _this6.fillColspanArrayWithDummies(colspan, level);
          }
        });
      });
    }
    /**
     * Fill the "colspan array" with default data for the dummy hidden headers.
     *
     * @private
     * @param {Number} colspan The colspan value.
     * @param {Number} level Header level.
     */

  }, {
    key: "fillColspanArrayWithDummies",
    value: function fillColspanArrayWithDummies(colspan, level) {
      var _this7 = this;

      (0, _number.rangeEach)(0, colspan - 2, function () {
        _this7.colspanArray[level].push({
          label: '',
          colspan: 1,
          hidden: true
        });
      });
    }
    /**
     * Generates the appropriate header renderer for a header row.
     *
     * @private
     * @param {Number} headerRow The header row.
     * @returns {Function}
     *
     * @fires Hooks#afterGetColHeader
     */

  }, {
    key: "headerRendererFactory",
    value: function headerRendererFactory(headerRow) {
      var _this = this;

      return function (index, TH) {
        var rootDocument = _this.hot.rootDocument;
        TH.removeAttribute('colspan');
        (0, _element.removeClass)(TH, 'hiddenHeader'); // header row is the index of header row counting from the top (=> positive values)

        if (_this.colspanArray[headerRow][index] && _this.colspanArray[headerRow][index].colspan) {
          var colspan = _this.colspanArray[headerRow][index].colspan;
          var fixedColumnsLeft = _this.hot.getSettings().fixedColumnsLeft || 0;
          var _this$hot$view$wt$wtO = _this.hot.view.wt.wtOverlays,
              leftOverlay = _this$hot$view$wt$wtO.leftOverlay,
              topLeftCornerOverlay = _this$hot$view$wt$wtO.topLeftCornerOverlay;
          var isInTopLeftCornerOverlay = topLeftCornerOverlay ? topLeftCornerOverlay.clone.wtTable.THEAD.contains(TH) : false;
          var isInLeftOverlay = leftOverlay ? leftOverlay.clone.wtTable.THEAD.contains(TH) : false;

          if (colspan > 1) {
            TH.setAttribute('colspan', isInTopLeftCornerOverlay || isInLeftOverlay ? Math.min(colspan, fixedColumnsLeft - index) : colspan);
          }

          if (isInTopLeftCornerOverlay || isInLeftOverlay && index === fixedColumnsLeft - 1) {
            (0, _element.addClass)(TH, 'overlayEdge');
          }
        }

        if (_this.colspanArray[headerRow][index] && _this.colspanArray[headerRow][index].hidden) {
          (0, _element.addClass)(TH, 'hiddenHeader');
        }

        (0, _element.empty)(TH);
        var divEl = rootDocument.createElement('DIV');
        (0, _element.addClass)(divEl, 'relative');
        var spanEl = rootDocument.createElement('SPAN');
        (0, _element.addClass)(spanEl, 'colHeader');
        (0, _element.fastInnerHTML)(spanEl, _this.colspanArray[headerRow][index] ? _this.colspanArray[headerRow][index].label || '' : '');
        divEl.appendChild(spanEl);
        TH.appendChild(divEl);

        _this.hot.runHooks('afterGetColHeader', index, TH);
      };
    }
    /**
     * Returns the colspan for the provided coordinates.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @returns {Number}
     */

  }, {
    key: "getColspan",
    value: function getColspan(row, column) {
      var header = this.colspanArray[this.rowCoordsToLevel(row)][column];
      return header ? header.colspan : 1;
    }
    /**
     * Translates the level value (header row index from the top) to the row value (negative index).
     *
     * @private
     * @param {Number} level Header level.
     * @returns {Number}
     */

  }, {
    key: "levelToRowCoords",
    value: function levelToRowCoords(level) {
      return level - this.columnHeaderLevelCount;
    }
    /**
     * Translates the row value (negative index) to the level value (header row index from the top).
     *
     * @private
     * @param {Number} row Row index.
     * @returns {Number}
     */

  }, {
    key: "rowCoordsToLevel",
    value: function rowCoordsToLevel(row) {
      return row + this.columnHeaderLevelCount;
    }
    /**
     * Returns the column index of the "parent" nested header.
     *
     * @private
     * @param {Number} level Header level.
     * @param {Number} column Column index.
     * @returns {*}
     */

  }, {
    key: "getNestedParent",
    value: function getNestedParent(level, column) {
      if (level < 0) {
        return false;
      }

      var colspan = this.colspanArray[level][column] ? this.colspanArray[level][column].colspan : 1;
      var hidden = this.colspanArray[level][column] ? this.colspanArray[level][column].hidden : false;

      if (colspan > 1 || colspan === 1 && hidden === false) {
        return column;
      }

      var parentCol = column - 1;

      do {
        if (this.colspanArray[level][parentCol].colspan > 1) {
          break;
        }

        parentCol -= 1;
      } while (column >= 0);

      return parentCol;
    }
    /**
     * Returns (physical) indexes of headers below the header with provided coordinates.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @returns {Number[]}
     */

  }, {
    key: "getChildHeaders",
    value: function getChildHeaders(row, column) {
      var level = this.rowCoordsToLevel(row);
      var childColspanLevel = this.colspanArray[level + 1];
      var nestedParentCol = this.getNestedParent(level, column);
      var colspan = this.colspanArray[level][column].colspan;
      var childHeaderRange = [];

      if (!childColspanLevel) {
        return childHeaderRange;
      }

      (0, _number.rangeEach)(nestedParentCol, nestedParentCol + colspan - 1, function (i) {
        if (childColspanLevel[i] && childColspanLevel[i].colspan > 1) {
          colspan -= childColspanLevel[i].colspan - 1;
        }

        if (childColspanLevel[i] && !childColspanLevel[i].hidden && childHeaderRange.indexOf(i) === -1) {
          childHeaderRange.push(i);
        }
      });
      return childHeaderRange;
    }
    /**
     * Fill the remaining colspanArray entries for the undeclared column headers.
     *
     * @private
     */

  }, {
    key: "fillTheRemainingColspans",
    value: function fillTheRemainingColspans() {
      var _this8 = this;

      (0, _object.objectEach)(this.settings, function (levelValue, level) {
        (0, _number.rangeEach)(_this8.colspanArray[level].length - 1, _this8.hot.countCols() - 1, function (col) {
          _this8.colspanArray[level].push({
            label: levelValue[col] || '',
            colspan: 1,
            hidden: false
          });
        }, true);
      });
    }
    /**
     * Updates headers highlight in nested structure.
     *
     * @private
     */

  }, {
    key: "updateHeadersHighlight",
    value: function updateHeadersHighlight() {
      var _this9 = this;

      var selection = this.hot.getSelectedLast();

      if (selection === void 0) {
        return;
      }

      var wtOverlays = this.hot.view.wt.wtOverlays;
      var selectionByHeader = this.hot.selection.isSelectedByColumnHeader();
      var from = Math.min(selection[1], selection[3]);
      var to = Math.max(selection[1], selection[3]);
      var levelLimit = selectionByHeader ? -1 : this.columnHeaderLevelCount - 1;
      var changes = [];

      var classNameModifier = function classNameModifier(className) {
        return function (TH, modifier) {
          return function () {
            return modifier(TH, className);
          };
        };
      };

      var highlightHeader = classNameModifier('ht__highlight');
      var activeHeader = classNameModifier('ht__active_highlight');
      (0, _number.rangeEach)(from, to, function (column) {
        var _loop = function _loop(level) {
          var visibleColumnIndex = _this9.getNestedParent(level, column);

          var topTH = wtOverlays.topOverlay ? wtOverlays.topOverlay.clone.wtTable.getColumnHeader(visibleColumnIndex, level) : void 0;
          var topLeftTH = wtOverlays.topLeftCornerOverlay ? wtOverlays.topLeftCornerOverlay.clone.wtTable.getColumnHeader(visibleColumnIndex, level) : void 0;
          var listTH = [topTH, topLeftTH];

          var colspanLen = _this9.getColspan(level - _this9.columnHeaderLevelCount, visibleColumnIndex);

          var isInSelection = visibleColumnIndex >= from && visibleColumnIndex + colspanLen - 1 <= to;
          (0, _array.arrayEach)(listTH, function (TH) {
            if (TH === void 0) {
              return false;
            }

            if (!selectionByHeader && level < levelLimit || selectionByHeader && !isInSelection) {
              changes.push(highlightHeader(TH, _element.removeClass));

              if (selectionByHeader) {
                changes.push(activeHeader(TH, _element.removeClass));
              }
            } else {
              changes.push(highlightHeader(TH, _element.addClass));

              if (selectionByHeader) {
                changes.push(activeHeader(TH, _element.addClass));
              }
            }
          });
        };

        for (var level = _this9.columnHeaderLevelCount - 1; level > -1; level--) {
          _loop(level);
        }
      });
      (0, _array.arrayEach)(changes, function (fn) {
        return void fn();
      });
      changes.length = 0;
    }
    /**
     * Make the renderer render the first nested column in its entirety.
     *
     * @private
     * @param {Object} calc Viewport column calculator.
     */

  }, {
    key: "onAfterViewportColumnCalculatorOverride",
    value: function onAfterViewportColumnCalculatorOverride(calc) {
      var _this10 = this;

      var newStartColumn = calc.startColumn;
      (0, _number.rangeEach)(0, Math.max(this.columnHeaderLevelCount - 1, 0), function (l) {
        var startColumnNestedParent = _this10.getNestedParent(l, calc.startColumn);

        if (startColumnNestedParent < calc.startColumn) {
          newStartColumn = Math.min(newStartColumn, startColumnNestedParent);
        }
      });
      calc.startColumn = newStartColumn;
    }
    /**
     * Select all nested headers of clicked cell.
     *
     * @private
     * @param {MouseEvent} event Mouse event.
     * @param {Object} coords Clicked cell coords.
     */

  }, {
    key: "onAfterOnCellMouseDown",
    value: function onAfterOnCellMouseDown(event, coords) {
      if (coords.row < 0) {
        var colspan = this.getColspan(coords.row, coords.col);
        var lastColIndex = coords.col + colspan - 1;

        if (colspan > 1) {
          var lastRowIndex = this.hot.countRows() - 1;
          this.hot.selection.setRangeEnd(new _src.CellCoords(lastRowIndex, lastColIndex));
        }
      }
    }
    /**
     * Make the header-selection properly select the nested headers.
     *
     * @private
     * @param {MouseEvent} event Mouse event.
     * @param {Object} coords Clicked cell coords.
     * @param {HTMLElement} TD
     */

  }, {
    key: "onBeforeOnCellMouseOver",
    value: function onBeforeOnCellMouseOver(event, coords, TD, blockCalculations) {
      if (coords.row >= 0 || coords.col < 0 || !this.hot.view.isMouseDown()) {
        return;
      }

      var _this$hot$getSelected = this.hot.getSelectedRangeLast(),
          from = _this$hot$getSelected.from,
          to = _this$hot$getSelected.to;

      var colspan = this.getColspan(coords.row, coords.col);
      var lastColIndex = coords.col + colspan - 1;
      var changeDirection = false;

      if (from.col <= to.col) {
        if (coords.col < from.col && lastColIndex === to.col || coords.col < from.col && lastColIndex < from.col || coords.col < from.col && lastColIndex >= from.col && lastColIndex < to.col) {
          changeDirection = true;
        }
      } else if (coords.col < to.col && lastColIndex > from.col || coords.col > from.col || coords.col <= to.col && lastColIndex > from.col || coords.col > to.col && lastColIndex > from.col) {
        changeDirection = true;
      }

      if (changeDirection) {
        var _ref = [to.col, from.col];
        from.col = _ref[0];
        to.col = _ref[1];
      }

      if (colspan > 1) {
        var _this$hot;

        blockCalculations.column = true;
        blockCalculations.cell = true;
        var columnRange = [];

        if (from.col === to.col) {
          if (lastColIndex <= from.col && coords.col < from.col) {
            columnRange.push(to.col, coords.col);
          } else {
            columnRange.push(coords.col < from.col ? coords.col : from.col, lastColIndex > to.col ? lastColIndex : to.col);
          }
        }

        if (from.col < to.col) {
          columnRange.push(coords.col < from.col ? coords.col : from.col, lastColIndex);
        }

        if (from.col > to.col) {
          columnRange.push(from.col, coords.col);
        }

        (_this$hot = this.hot).selectColumns.apply(_this$hot, columnRange);
      }
    }
    /**
     * Cache column header count.
     *
     * @private
     */

  }, {
    key: "onAfterInit",
    value: function onAfterInit() {
      this.columnHeaderLevelCount = this.hot.view.wt.getSetting('columnHeaders').length;
      this.fillTheRemainingColspans();
      this.checkForOverlappingHeaders();
      this.ghostTable.buildWidthsMapper();
    }
    /**
     * `afterGetColumnHeader` hook callback - prepares the header structure.
     *
     * @private
     * @param {Array} renderersArray Array of renderers.
     */

  }, {
    key: "onAfterGetColumnHeaderRenderers",
    value: function onAfterGetColumnHeaderRenderers(renderersArray) {
      if (renderersArray) {
        renderersArray.length = 0;

        for (var headersCount = this.colspanArray.length, i = headersCount - 1; i >= 0; i--) {
          renderersArray.push(this.headerRendererFactory(i));
        }

        renderersArray.reverse();
      }

      this.updateHeadersHighlight();
    }
    /**
     * `modifyColWidth` hook callback - returns width from cache, when is greater than incoming from hook.
     *
     * @private
     * @param width Width from hook.
     * @param column Visual index of an column.
     * @returns {Number}
     */

  }, {
    key: "onModifyColWidth",
    value: function onModifyColWidth(width, column) {
      var cachedWidth = this.ghostTable.widthsCache[column];
      return width > cachedWidth ? width : cachedWidth;
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.settings = null;
      this.columnHeaderLevelCount = null;
      this.colspanArray = null;

      _get(_getPrototypeOf(NestedHeaders.prototype), "destroy", this).call(this);
    }
  }]);

  return NestedHeaders;
}(_base.default);

(0, _plugins.registerPlugin)('nestedHeaders', NestedHeaders);
var _default = NestedHeaders;
exports.default = _default;