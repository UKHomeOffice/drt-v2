import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.weak-map";
import "core-js/modules/web.dom-collections.iterator";

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { RENDER_TYPE, FULLY_VISIBLE_TYPE } from './constants';
var privatePool = new WeakMap();
/**
 * Calculates indexes of columns to render OR columns that are visible.
 * To redo the calculation, you need to create a new calculator.
 *
 * @class ViewportColumnsCalculator
 */

var ViewportColumnsCalculator =
/*#__PURE__*/
function () {
  _createClass(ViewportColumnsCalculator, null, [{
    key: "DEFAULT_WIDTH",

    /**
     * Default column width
     *
     * @type {Number}
     */
    get: function get() {
      return 50;
    }
    /**
     * @param {Object} options Object with all options specyfied for column viewport calculation.
     * @param {Number} options.viewportWidth Width of the viewport
     * @param {Number} options.scrollOffset Current horizontal scroll position of the viewport
     * @param {Number} options.totalColumns Total number of columns
     * @param {Function} options.columnWidthFn Function that returns the width of the column at a given index (in px)
     * @param {Function} options.overrideFn Function that changes calculated this.startRow, this.endRow (used by MergeCells plugin)
     * @param {String} options.calculationType String which describes types of calculation which will be performed.
     * @param {String} [options.stretchH] Stretch mode 'all' or 'last'
     * @param {Function} [options.stretchingColumnWidthFn] Function that returns the new width of the stretched column.
     */

  }]);

  function ViewportColumnsCalculator() {
    var _ref = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {},
        viewportSize = _ref.viewportSize,
        scrollOffset = _ref.scrollOffset,
        totalItems = _ref.totalItems,
        itemSizeFn = _ref.itemSizeFn,
        overrideFn = _ref.overrideFn,
        calculationType = _ref.calculationType,
        stretchMode = _ref.stretchMode,
        _ref$stretchingItemWi = _ref.stretchingItemWidthFn,
        stretchingItemWidthFn = _ref$stretchingItemWi === void 0 ? function (width) {
      return width;
    } : _ref$stretchingItemWi;

    _classCallCheck(this, ViewportColumnsCalculator);

    privatePool.set(this, {
      viewportWidth: viewportSize,
      scrollOffset: scrollOffset,
      totalColumns: totalItems,
      columnWidthFn: itemSizeFn,
      overrideFn: overrideFn,
      calculationType: calculationType,
      stretchingColumnWidthFn: stretchingItemWidthFn
    });
    /**
     * Number of rendered/visible columns
     *
     * @type {Number}
     */

    this.count = 0;
    /**
     * Index of the first rendered/visible column (can be overwritten using overrideFn)
     *
     * @type {Number|null}
     */

    this.startColumn = null;
    /**
     * Index of the last rendered/visible column (can be overwritten using overrideFn)
     *
     * @type {null}
     */

    this.endColumn = null;
    /**
     * Position of the first rendered/visible column (in px)
     *
     * @type {Number|null}
     */

    this.startPosition = null;
    this.stretchAllRatio = 0;
    this.stretchLastWidth = 0;
    this.stretch = stretchMode;
    this.totalTargetWidth = 0;
    this.needVerifyLastColumnWidth = true;
    this.stretchAllColumnsWidth = [];
    this.calculate();
  }
  /**
   * Calculates viewport
   */


  _createClass(ViewportColumnsCalculator, [{
    key: "calculate",
    value: function calculate() {
      var sum = 0;
      var needReverse = true;
      var startPositions = [];
      var columnWidth;
      var priv = privatePool.get(this);
      var calculationType = priv.calculationType;
      var overrideFn = priv.overrideFn;
      var scrollOffset = priv.scrollOffset;
      var totalColumns = priv.totalColumns;
      var viewportWidth = priv.viewportWidth;

      for (var i = 0; i < totalColumns; i++) {
        columnWidth = this._getColumnWidth(i);

        if (sum <= scrollOffset && calculationType !== FULLY_VISIBLE_TYPE) {
          this.startColumn = i;
        } // +1 pixel for row header width compensation for horizontal scroll > 0


        var compensatedViewportWidth = scrollOffset > 0 ? viewportWidth + 1 : viewportWidth;

        if (sum >= scrollOffset && sum + (calculationType === FULLY_VISIBLE_TYPE ? columnWidth : 0) <= scrollOffset + compensatedViewportWidth) {
          if (this.startColumn === null || this.startColumn === void 0) {
            this.startColumn = i;
          }

          this.endColumn = i;
        }

        startPositions.push(sum);
        sum += columnWidth;

        if (calculationType !== FULLY_VISIBLE_TYPE) {
          this.endColumn = i;
        }

        if (sum >= scrollOffset + viewportWidth) {
          needReverse = false;
          break;
        }
      }

      if (this.endColumn === totalColumns - 1 && needReverse) {
        this.startColumn = this.endColumn;

        while (this.startColumn > 0) {
          var viewportSum = startPositions[this.endColumn] + columnWidth - startPositions[this.startColumn - 1];

          if (viewportSum <= viewportWidth || calculationType !== FULLY_VISIBLE_TYPE) {
            this.startColumn -= 1;
          }

          if (viewportSum > viewportWidth) {
            break;
          }
        }
      }

      if (calculationType === RENDER_TYPE && this.startColumn !== null && overrideFn) {
        overrideFn(this);
      }

      this.startPosition = startPositions[this.startColumn];

      if (this.startPosition === void 0) {
        this.startPosition = null;
      } // If totalColumns exceeded its total columns size set endColumn to the latest item


      if (totalColumns < this.endColumn) {
        this.endColumn = totalColumns - 1;
      }

      if (this.startColumn !== null) {
        this.count = this.endColumn - this.startColumn + 1;
      }
    }
    /**
     * Recalculate columns stretching.
     *
     * @param {Number} totalWidth
     */

  }, {
    key: "refreshStretching",
    value: function refreshStretching(totalWidth) {
      if (this.stretch === 'none') {
        return;
      }

      var totalColumnsWidth = totalWidth;
      this.totalTargetWidth = totalColumnsWidth;
      var priv = privatePool.get(this);
      var totalColumns = priv.totalColumns;
      var sumAll = 0;

      for (var i = 0; i < totalColumns; i++) {
        var columnWidth = this._getColumnWidth(i);

        var permanentColumnWidth = priv.stretchingColumnWidthFn(void 0, i);

        if (typeof permanentColumnWidth === 'number') {
          totalColumnsWidth -= permanentColumnWidth;
        } else {
          sumAll += columnWidth;
        }
      }

      var remainingSize = totalColumnsWidth - sumAll;

      if (this.stretch === 'all' && remainingSize > 0) {
        this.stretchAllRatio = totalColumnsWidth / sumAll;
        this.stretchAllColumnsWidth = [];
        this.needVerifyLastColumnWidth = true;
      } else if (this.stretch === 'last' && totalColumnsWidth !== Infinity) {
        var _columnWidth = this._getColumnWidth(totalColumns - 1);

        var lastColumnWidth = remainingSize + _columnWidth;
        this.stretchLastWidth = lastColumnWidth >= 0 ? lastColumnWidth : _columnWidth;
      }
    }
    /**
     * Get stretched column width based on stretchH (all or last) setting passed in handsontable instance.
     *
     * @param {Number} column
     * @param {Number} baseWidth
     * @returns {Number|null}
     */

  }, {
    key: "getStretchedColumnWidth",
    value: function getStretchedColumnWidth(column, baseWidth) {
      var result = null;

      if (this.stretch === 'all' && this.stretchAllRatio !== 0) {
        result = this._getStretchedAllColumnWidth(column, baseWidth);
      } else if (this.stretch === 'last' && this.stretchLastWidth !== 0) {
        result = this._getStretchedLastColumnWidth(column);
      }

      return result;
    }
    /**
     * @param {Number} column
     * @param {Number} baseWidth
     * @returns {Number}
     * @private
     */

  }, {
    key: "_getStretchedAllColumnWidth",
    value: function _getStretchedAllColumnWidth(column, baseWidth) {
      var sumRatioWidth = 0;
      var priv = privatePool.get(this);
      var totalColumns = priv.totalColumns;

      if (!this.stretchAllColumnsWidth[column]) {
        var stretchedWidth = Math.round(baseWidth * this.stretchAllRatio);
        var newStretchedWidth = priv.stretchingColumnWidthFn(stretchedWidth, column);

        if (newStretchedWidth === void 0) {
          this.stretchAllColumnsWidth[column] = stretchedWidth;
        } else {
          this.stretchAllColumnsWidth[column] = isNaN(newStretchedWidth) ? this._getColumnWidth(column) : newStretchedWidth;
        }
      }

      if (this.stretchAllColumnsWidth.length === totalColumns && this.needVerifyLastColumnWidth) {
        this.needVerifyLastColumnWidth = false;

        for (var i = 0; i < this.stretchAllColumnsWidth.length; i++) {
          sumRatioWidth += this.stretchAllColumnsWidth[i];
        }

        if (sumRatioWidth !== this.totalTargetWidth) {
          this.stretchAllColumnsWidth[this.stretchAllColumnsWidth.length - 1] += this.totalTargetWidth - sumRatioWidth;
        }
      }

      return this.stretchAllColumnsWidth[column];
    }
    /**
     * @param {Number} column
     * @returns {Number|null}
     * @private
     */

  }, {
    key: "_getStretchedLastColumnWidth",
    value: function _getStretchedLastColumnWidth(column) {
      var priv = privatePool.get(this);
      var totalColumns = priv.totalColumns;

      if (column === totalColumns - 1) {
        return this.stretchLastWidth;
      }

      return null;
    }
    /**
     * @param {Number} column Column index.
     * @returns {Number}
     * @private
     */

  }, {
    key: "_getColumnWidth",
    value: function _getColumnWidth(column) {
      var width = privatePool.get(this).columnWidthFn(column);

      if (isNaN(width)) {
        width = ViewportColumnsCalculator.DEFAULT_WIDTH;
      }

      return width;
    }
  }]);

  return ViewportColumnsCalculator;
}();

export default ViewportColumnsCalculator;