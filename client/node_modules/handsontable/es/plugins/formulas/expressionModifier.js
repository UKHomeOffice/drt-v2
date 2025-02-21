import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.string.match";
import "core-js/modules/es.string.replace";
import "core-js/modules/es.string.split";
import "core-js/modules/es.string.starts-with";
import "core-js/modules/web.dom-collections.iterator";

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { toLabel as _toLabel, extractLabel, error, ERROR_REF } from 'hot-formula-parser';
import { arrayEach, arrayFilter } from '../../helpers/array';
import { mixin } from '../../helpers/object';
import localHooks from '../../mixins/localHooks';
import { toUpperCaseFormula } from './utils';
var BARE_CELL_STRICT_REGEX = /^\$?[A-Z]+\$?\d+$/;
var BARE_CELL_REGEX = /\$?[A-Z]+\$?\d+/;
var CELL_REGEX = /(?:[^0-9A-Z$: ]|^)\s*(\$?[A-Z]+\$?\d+)\s*(?![0-9A-Z_: ])/g;
var RANGE_REGEX = /\$?[A-Z]+\$?\d+\s*:\s*\$?[A-Z]+\$?\d+/g;
var CELL_AND_RANGE_REGEX = /((?:[^0-9A-Z$: ]|^)\s*(\$?[A-Z]+\$?\d+)\s*(?![0-9A-Z_: ]))|(\$?[A-Z]+\$?\d+\s*:\s*\$?[A-Z]+\$?\d+)/g;
/**
 * Component adds an ability to parse and modify formula expressions. It is designed for translating cell
 * coordinates and cell ranges in any direction. By default, component translates only relative coordinates but this
 * behavior can be overwritten by passing custom modifier which controls translating process.
 *
 * @class ExpressionModifier
 * @util
 */

var ExpressionModifier =
/*#__PURE__*/
function () {
  function ExpressionModifier(expression) {
    _classCallCheck(this, ExpressionModifier);

    /**
     * Formula expression to modify.
     *
     * @type {String}
     */
    this.expression = '';
    /**
     * Extracted cells and cells ranges.
     *
     * @type {Array}
     */

    this.cells = [];
    /**
     * Function which can modify default behaviour of how cells and cell ranges will be translated.
     *
     * @type {null|Function}
     */

    this.customModifier = null;

    if (typeof expression === 'string') {
      this.setExpression(expression);
    }
  }
  /**
   * Set formula expression to modify.
   *
   * @param {String} expression Formula expression to process.
   * @returns {ExpressionModifier}
   */


  _createClass(ExpressionModifier, [{
    key: "setExpression",
    value: function setExpression(expression) {
      this.cells.length = 0;
      this.expression = toUpperCaseFormula(expression);

      this._extractCells();

      this._extractCellsRange();

      return this;
    }
    /**
     * Set function which can modify default behavior of how cells and cell ranges will be translated.
     * The passed function will be called with 4 arguments:
     *  - cell, A cell object with structure
     *            like this: {start: {row, column}, end: {row, column}, origLabel, type: 'cell|range', refError, toLabel: () => {}}
     *  - axis, Type of currently processing axis ('row' or 'column')
     *  - delta, Number as distance to translate. Can be positive or negative.
     *  - startFromIndex, Base index which translation will be applied from.
     *
     * the function must return an array with 3 items, where:
     *  [
     *    deltaStart, Number as a delta to translate first part of coordinates.
     *    deltaEnd,   Number as a delta to translate second part of coordinates (if cell range is modified).
     *    refError,   Defines an error which refers to the situation when translated cell overcrossed the data boundary.
     *  ]
     *
     *
     * @param {Function} customModifier Function with custom logic.
     */

  }, {
    key: "useCustomModifier",
    value: function useCustomModifier(customModifier) {
      this.customModifier = customModifier;
    }
    /**
     * Translate formula expression cells.
     *
     * @param {Object} delta Distance to move in proper direction.
     * @param {Object} [startFrom] Coordinates which translation will be applied from.
     * @returns {ExpressionModifier}
     */

  }, {
    key: "translate",
    value: function translate(_ref) {
      var _this = this;

      var deltaRow = _ref.row,
          deltaColumn = _ref.column;
      var startFrom = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : {};
      arrayEach(this.cells, function (cell) {
        if (deltaRow !== null && deltaRow !== void 0) {
          _this._translateCell(cell, 'row', deltaRow, startFrom.row);
        }

        if (deltaColumn !== null && deltaColumn !== void 0) {
          _this._translateCell(cell, 'column', deltaColumn, startFrom.column);
        }
      });
      return this;
    }
    /**
     * Translate object into string representation.
     *
     * @returns {String}
     */

  }, {
    key: "toString",
    value: function toString() {
      var _this2 = this;

      var expression = this.expression.replace(CELL_AND_RANGE_REGEX, function (match, p1, p2) {
        var isSingleCell = match.indexOf(':') === -1;
        var result = match;
        var cellLabel = match;
        var translatedCellLabel = null;

        if (isSingleCell) {
          cellLabel = BARE_CELL_STRICT_REGEX.test(p1) ? p1 : p2;
        }

        var cell = _this2._searchCell(cellLabel);

        if (cell) {
          translatedCellLabel = cell.refError ? error(ERROR_REF) : cell.toLabel();

          if (isSingleCell) {
            result = match.replace(cellLabel, translatedCellLabel);
          } else {
            result = translatedCellLabel;
          }
        }

        return result;
      });

      if (!expression.startsWith('=')) {
        expression = "=".concat(expression);
      }

      return expression;
    }
    /**
     * Translate single cell.
     *
     * @param {Object} cell Cell object.
     * @param {String} axis Axis to modify.
     * @param {Number} delta Distance to move.
     * @param {Number} [startFromIndex] Base index which translation will be applied from.
     * @private
     */

  }, {
    key: "_translateCell",
    value: function _translateCell(cell, axis, delta, startFromIndex) {
      var start = cell.start,
          end = cell.end;
      var startIndex = start[axis].index;
      var endIndex = end[axis].index;
      var deltaStart = delta;
      var deltaEnd = delta;
      var refError = false;

      if (this.customModifier) {
        var _this$customModifier = this.customModifier(cell, axis, delta, startFromIndex);

        var _this$customModifier2 = _slicedToArray(_this$customModifier, 3);

        deltaStart = _this$customModifier2[0];
        deltaEnd = _this$customModifier2[1];
        refError = _this$customModifier2[2];
      } else {
        // By default only relative cells are translated, if meets absolute reset deltas to 0
        if (start[axis].isAbsolute) {
          deltaStart = 0;
        }

        if (end[axis].isAbsolute) {
          deltaEnd = 0;
        }
      }

      if (deltaStart && !refError) {
        if (startIndex + deltaStart < 0) {
          refError = true;
        }

        start[axis].index = Math.max(startIndex + deltaStart, 0);
      }

      if (deltaEnd && !refError) {
        if (endIndex + deltaEnd < 0) {
          refError = true;
        }

        end[axis].index = Math.max(endIndex + deltaEnd, 0);
      }

      if (refError) {
        cell.refError = true;
      }
    }
    /**
     * Extract all cells from the formula expression.
     *
     * @private
     */

  }, {
    key: "_extractCells",
    value: function _extractCells() {
      var _this3 = this;

      var matches = this.expression.match(CELL_REGEX);

      if (!matches) {
        return;
      }

      arrayEach(matches, function (coord) {
        var cellCoords = coord.match(BARE_CELL_REGEX);

        if (!cellCoords) {
          return;
        }

        var _extractLabel = extractLabel(cellCoords[0]),
            _extractLabel2 = _slicedToArray(_extractLabel, 2),
            row = _extractLabel2[0],
            column = _extractLabel2[1];

        _this3.cells.push(_this3._createCell({
          row: row,
          column: column
        }, {
          row: row,
          column: column
        }, cellCoords[0]));
      });
    }
    /**
     * Extract all cells range from the formula expression.
     *
     * @private
     */

  }, {
    key: "_extractCellsRange",
    value: function _extractCellsRange() {
      var _this4 = this;

      var matches = this.expression.match(RANGE_REGEX);

      if (!matches) {
        return;
      }

      arrayEach(matches, function (match) {
        var _match$split = match.split(':'),
            _match$split2 = _slicedToArray(_match$split, 2),
            start = _match$split2[0],
            end = _match$split2[1];

        var _extractLabel3 = extractLabel(start),
            _extractLabel4 = _slicedToArray(_extractLabel3, 2),
            startRow = _extractLabel4[0],
            startColumn = _extractLabel4[1];

        var _extractLabel5 = extractLabel(end),
            _extractLabel6 = _slicedToArray(_extractLabel5, 2),
            endRow = _extractLabel6[0],
            endColumn = _extractLabel6[1];

        var startCell = {
          row: startRow,
          column: startColumn
        };
        var endCell = {
          row: endRow,
          column: endColumn
        };

        _this4.cells.push(_this4._createCell(startCell, endCell, match));
      });
    }
    /**
     * Search cell by its label.
     *
     * @param {String} label Cell label eq. `B4` or `$B$6`.
     * @returns {Object|null}
     * @private
     */

  }, {
    key: "_searchCell",
    value: function _searchCell(label) {
      var _arrayFilter = arrayFilter(this.cells, function (cellMeta) {
        return cellMeta.origLabel === label;
      }),
          _arrayFilter2 = _slicedToArray(_arrayFilter, 1),
          cell = _arrayFilter2[0];

      return cell || null;
    }
    /**
     * Create object cell.
     *
     * @param {Object} start Start coordinates (top-left).
     * @param {Object} end End coordinates (bottom-right).
     * @param {String} label Original label name.
     * @returns {Object}
     * @private
     */

  }, {
    key: "_createCell",
    value: function _createCell(start, end, label) {
      return {
        start: start,
        end: end,
        origLabel: label,
        type: label.indexOf(':') === -1 ? 'cell' : 'range',
        refError: false,
        toLabel: function toLabel() {
          var newLabel = _toLabel(this.start.row, this.start.column);

          if (this.type === 'range') {
            newLabel += ":".concat(_toLabel(this.end.row, this.end.column));
          }

          return newLabel;
        }
      };
    }
  }]);

  return ExpressionModifier;
}();

mixin(ExpressionModifier, localHooks);
export default ExpressionModifier;