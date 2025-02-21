"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.map");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _element = require("./../../../../helpers/dom/element");

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Column utils class contains all necessary information about sizes of the columns.
 *
 * @class {ColumnUtils}
 */
var ColumnUtils =
/*#__PURE__*/
function () {
  function ColumnUtils(wot) {
    _classCallCheck(this, ColumnUtils);

    this.wot = wot;
    this.headerWidths = new Map();
  }
  /**
   * Returns column width based on passed source index.
   *
   * @param {Number} sourceIndex Column source index.
   * @returns {Number}
   */


  _createClass(ColumnUtils, [{
    key: "getWidth",
    value: function getWidth(sourceIndex) {
      var width = this.wot.wtSettings.settings.columnWidth;

      if (typeof width === 'function') {
        width = width(sourceIndex);
      } else if (_typeof(width) === 'object') {
        width = width[sourceIndex];
      }

      return width || this.wot.wtSettings.settings.defaultColumnWidth;
    }
    /**
     * Returns stretched column width based on passed source index.
     *
     * @param {Number} sourceIndex Column source index.
     * @returns {Number}
     */

  }, {
    key: "getStretchedColumnWidth",
    value: function getStretchedColumnWidth(sourceIndex) {
      var columnWidth = this.getWidth(sourceIndex);
      var calculator = this.wot.wtViewport.columnsRenderCalculator;
      var width = columnWidth === null || columnWidth === void 0 ? this.wot.wtSettings.settings.defaultColumnWidth : columnWidth;

      if (calculator) {
        var stretchedWidth = calculator.getStretchedColumnWidth(sourceIndex, width);

        if (stretchedWidth) {
          width = stretchedWidth;
        }
      }

      return width;
    }
    /**
     * Returns column header height based on passed header level.
     *
     * @param {Number} level Column header level.
     * @returns {Number}
     */

  }, {
    key: "getHeaderHeight",
    value: function getHeaderHeight(level) {
      var height = this.wot.wtSettings.settings.defaultRowHeight;
      var oversizedHeight = this.wot.wtViewport.oversizedColumnHeaders[level];

      if (oversizedHeight !== void 0) {
        height = height ? Math.max(height, oversizedHeight) : oversizedHeight;
      }

      return height;
    }
    /**
     * Returns column header width based on passed source index.
     *
     * @param {Number} sourceIndex Column source index.
     * @returns {Number}
     */

  }, {
    key: "getHeaderWidth",
    value: function getHeaderWidth(sourceIndex) {
      return this.headerWidths.get(this.wot.wtTable.columnFilter.sourceToRendered(sourceIndex));
    }
    /**
     * Calculates column header widths that can be retrieved from the cache.
     */

  }, {
    key: "calculateWidths",
    value: function calculateWidths() {
      var wot = this.wot;
      var wtTable = wot.wtTable,
          wtViewport = wot.wtViewport,
          cloneSource = wot.cloneSource;
      var mainHolder = cloneSource ? cloneSource.wtTable.holder : wtTable.holder;
      var scrollbarCompensation = mainHolder.offsetHeight < mainHolder.scrollHeight ? (0, _element.getScrollbarWidth)() : 0;
      var rowHeaderWidthSetting = wot.getSetting('rowHeaderWidth');
      wtViewport.columnsRenderCalculator.refreshStretching(wtViewport.getViewportWidth() - scrollbarCompensation);
      rowHeaderWidthSetting = wot.getSetting('onModifyRowHeaderWidth', rowHeaderWidthSetting);

      if (rowHeaderWidthSetting !== null && rowHeaderWidthSetting !== void 0) {
        var rowHeadersCount = wot.getSetting('rowHeaders').length;
        var defaultColumnWidth = wot.getSetting('defaultColumnWidth');

        for (var visibleColumnIndex = 0; visibleColumnIndex < rowHeadersCount; visibleColumnIndex++) {
          var width = Array.isArray(rowHeaderWidthSetting) ? rowHeaderWidthSetting[visibleColumnIndex] : rowHeaderWidthSetting;
          width = width === null || width === void 0 ? defaultColumnWidth : width;
          this.headerWidths.set(visibleColumnIndex, width);
        }
      }
    }
  }]);

  return ColumnUtils;
}();

exports.default = ColumnUtils;