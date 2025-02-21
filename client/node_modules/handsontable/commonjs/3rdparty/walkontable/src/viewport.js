"use strict";

exports.__esModule = true;
exports.default = void 0;

var _element = require("./../../../helpers/dom/element");

var _object = require("./../../../helpers/object");

var _eventManager = _interopRequireDefault(require("./../../../eventManager"));

var _calculator = require("./calculator");

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * @class Viewport
 */
var Viewport =
/*#__PURE__*/
function () {
  /**
   * @param wotInstance
   */
  function Viewport(wotInstance) {
    var _this = this;

    _classCallCheck(this, Viewport);

    this.wot = wotInstance; // legacy support

    this.instance = this.wot;
    this.oversizedRows = [];
    this.oversizedColumnHeaders = [];
    this.hasOversizedColumnHeadersMarked = {};
    this.clientHeight = 0;
    this.containerWidth = NaN;
    this.rowHeaderWidth = NaN;
    this.rowsVisibleCalculator = null;
    this.columnsVisibleCalculator = null;
    this.eventManager = new _eventManager.default(this.wot);
    this.eventManager.addEventListener(this.wot.rootWindow, 'resize', function () {
      _this.clientHeight = _this.getWorkspaceHeight();
    });
  }
  /**
   * @returns {number}
   */


  _createClass(Viewport, [{
    key: "getWorkspaceHeight",
    value: function getWorkspaceHeight() {
      var currentDocument = this.wot.rootDocument;
      var trimmingContainer = this.instance.wtOverlays.topOverlay.trimmingContainer;
      var height = 0;

      if (trimmingContainer === this.wot.rootWindow) {
        height = currentDocument.documentElement.clientHeight;
      } else {
        var elemHeight = (0, _element.outerHeight)(trimmingContainer); // returns height without DIV scrollbar

        height = elemHeight > 0 && trimmingContainer.clientHeight > 0 ? trimmingContainer.clientHeight : Infinity;
      }

      return height;
    }
  }, {
    key: "getWorkspaceWidth",
    value: function getWorkspaceWidth() {
      var wot = this.wot;
      var rootDocument = wot.rootDocument,
          rootWindow = wot.rootWindow;
      var trimmingContainer = this.instance.wtOverlays.leftOverlay.trimmingContainer;
      var docOffsetWidth = rootDocument.documentElement.offsetWidth;
      var totalColumns = wot.getSetting('totalColumns');
      var preventOverflow = wot.getSetting('preventOverflow');
      var width;
      var overflow;

      if (preventOverflow) {
        return (0, _element.outerWidth)(this.instance.wtTable.wtRootElement);
      }

      if (wot.getSetting('freezeOverlays')) {
        width = Math.min(docOffsetWidth - this.getWorkspaceOffset().left, docOffsetWidth);
      } else {
        width = Math.min(this.getContainerFillWidth(), docOffsetWidth - this.getWorkspaceOffset().left, docOffsetWidth);
      }

      if (trimmingContainer === rootWindow && totalColumns > 0 && this.sumColumnWidths(0, totalColumns - 1) > width) {
        // in case sum of column widths is higher than available stylesheet width, let's assume using the whole window
        // otherwise continue below, which will allow stretching
        // this is used in `scroll_window.html`
        // TODO test me
        return rootDocument.documentElement.clientWidth;
      }

      if (trimmingContainer !== rootWindow) {
        overflow = (0, _element.getStyle)(this.instance.wtOverlays.leftOverlay.trimmingContainer, 'overflow', rootWindow);

        if (overflow === 'scroll' || overflow === 'hidden' || overflow === 'auto') {
          // this is used in `scroll.html`
          // TODO test me
          return Math.max(width, trimmingContainer.clientWidth);
        }
      }

      var stretchSetting = wot.getSetting('stretchH');

      if (stretchSetting === 'none' || !stretchSetting) {
        // if no stretching is used, return the maximum used workspace width
        return Math.max(width, (0, _element.outerWidth)(this.instance.wtTable.TABLE));
      } // if stretching is used, return the actual container width, so the columns can fit inside it


      return width;
    }
    /**
     * Checks if viewport has vertical scroll
     *
     * @returns {Boolean}
     */

  }, {
    key: "hasVerticalScroll",
    value: function hasVerticalScroll() {
      return this.getWorkspaceActualHeight() > this.getWorkspaceHeight();
    }
    /**
     * Checks if viewport has horizontal scroll
     *
     * @returns {Boolean}
     */

  }, {
    key: "hasHorizontalScroll",
    value: function hasHorizontalScroll() {
      return this.getWorkspaceActualWidth() > this.getWorkspaceWidth();
    }
    /**
     * @param from
     * @param length
     * @returns {Number}
     */

  }, {
    key: "sumColumnWidths",
    value: function sumColumnWidths(from, length) {
      var wtTable = this.wot.wtTable;
      var sum = 0;
      var column = from;

      while (column < length) {
        sum += wtTable.getColumnWidth(column);
        column += 1;
      }

      return sum;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getContainerFillWidth",
    value: function getContainerFillWidth() {
      if (this.containerWidth) {
        return this.containerWidth;
      }

      var mainContainer = this.instance.wtTable.holder;
      var dummyElement = this.wot.rootDocument.createElement('div');
      dummyElement.style.width = '100%';
      dummyElement.style.height = '1px';
      mainContainer.appendChild(dummyElement);
      var fillWidth = dummyElement.offsetWidth;
      this.containerWidth = fillWidth;
      mainContainer.removeChild(dummyElement);
      return fillWidth;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getWorkspaceOffset",
    value: function getWorkspaceOffset() {
      return (0, _element.offset)(this.wot.wtTable.TABLE);
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getWorkspaceActualHeight",
    value: function getWorkspaceActualHeight() {
      return (0, _element.outerHeight)(this.wot.wtTable.TABLE);
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getWorkspaceActualWidth",
    value: function getWorkspaceActualWidth() {
      var wtTable = this.wot.wtTable;
      return (0, _element.outerWidth)(wtTable.TABLE) || (0, _element.outerWidth)(wtTable.TBODY) || (0, _element.outerWidth)(wtTable.THEAD); // IE8 reports 0 as <table> offsetWidth;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getColumnHeaderHeight",
    value: function getColumnHeaderHeight() {
      var columnHeaders = this.instance.getSetting('columnHeaders');

      if (!columnHeaders.length) {
        this.columnHeaderHeight = 0;
      } else if (isNaN(this.columnHeaderHeight)) {
        this.columnHeaderHeight = (0, _element.outerHeight)(this.wot.wtTable.THEAD);
      }

      return this.columnHeaderHeight;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getViewportHeight",
    value: function getViewportHeight() {
      var containerHeight = this.getWorkspaceHeight();

      if (containerHeight === Infinity) {
        return containerHeight;
      }

      var columnHeaderHeight = this.getColumnHeaderHeight();

      if (columnHeaderHeight > 0) {
        containerHeight -= columnHeaderHeight;
      }

      return containerHeight;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getRowHeaderWidth",
    value: function getRowHeaderWidth() {
      var rowHeadersWidthSetting = this.instance.getSetting('rowHeaderWidth');
      var rowHeaders = this.instance.getSetting('rowHeaders');

      if (rowHeadersWidthSetting) {
        this.rowHeaderWidth = 0;

        for (var i = 0, len = rowHeaders.length; i < len; i++) {
          this.rowHeaderWidth += rowHeadersWidthSetting[i] || rowHeadersWidthSetting;
        }
      }

      if (this.wot.cloneSource) {
        return this.wot.cloneSource.wtViewport.getRowHeaderWidth();
      }

      if (isNaN(this.rowHeaderWidth)) {
        if (rowHeaders.length) {
          var TH = this.instance.wtTable.TABLE.querySelector('TH');
          this.rowHeaderWidth = 0;

          for (var _i = 0, _len = rowHeaders.length; _i < _len; _i++) {
            if (TH) {
              this.rowHeaderWidth += (0, _element.outerWidth)(TH);
              TH = TH.nextSibling;
            } else {
              // yes this is a cheat but it worked like that before, just taking assumption from CSS instead of measuring.
              // TODO: proper fix
              this.rowHeaderWidth += 50;
            }
          }
        } else {
          this.rowHeaderWidth = 0;
        }
      }

      this.rowHeaderWidth = this.instance.getSetting('onModifyRowHeaderWidth', this.rowHeaderWidth) || this.rowHeaderWidth;
      return this.rowHeaderWidth;
    }
    /**
     * @returns {Number}
     */

  }, {
    key: "getViewportWidth",
    value: function getViewportWidth() {
      var containerWidth = this.getWorkspaceWidth();

      if (containerWidth === Infinity) {
        return containerWidth;
      }

      var rowHeaderWidth = this.getRowHeaderWidth();

      if (rowHeaderWidth > 0) {
        return containerWidth - rowHeaderWidth;
      }

      return containerWidth;
    }
    /**
     * Creates:
     *  - rowsRenderCalculator (before draw, to qualify rows for rendering)
     *  - rowsVisibleCalculator (after draw, to measure which rows are actually visible)
     *
     * @returns {ViewportRowsCalculator}
     */

  }, {
    key: "createRowsCalculator",
    value: function createRowsCalculator() {
      var calculationType = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : _calculator.RENDER_TYPE;
      var wot = this.wot;
      var wtSettings = wot.wtSettings,
          wtOverlays = wot.wtOverlays,
          wtTable = wot.wtTable,
          rootDocument = wot.rootDocument;
      var height;
      var scrollbarHeight;
      var fixedRowsHeight;
      this.rowHeaderWidth = NaN;

      if (wtSettings.settings.renderAllRows && calculationType === _calculator.RENDER_TYPE) {
        height = Infinity;
      } else {
        height = this.getViewportHeight();
      }

      var pos = wtOverlays.topOverlay.getScrollPosition() - wtOverlays.topOverlay.getTableParentOffset();

      if (pos < 0) {
        pos = 0;
      }

      var fixedRowsTop = wot.getSetting('fixedRowsTop');
      var fixedRowsBottom = wot.getSetting('fixedRowsBottom');
      var totalRows = wot.getSetting('totalRows');

      if (fixedRowsTop) {
        fixedRowsHeight = wtOverlays.topOverlay.sumCellSizes(0, fixedRowsTop);
        pos += fixedRowsHeight;
        height -= fixedRowsHeight;
      }

      if (fixedRowsBottom && wtOverlays.bottomOverlay.clone) {
        fixedRowsHeight = wtOverlays.bottomOverlay.sumCellSizes(totalRows - fixedRowsBottom, totalRows);
        height -= fixedRowsHeight;
      }

      if (wtTable.holder.clientHeight === wtTable.holder.offsetHeight) {
        scrollbarHeight = 0;
      } else {
        scrollbarHeight = (0, _element.getScrollbarWidth)(rootDocument);
      }

      return new _calculator.ViewportRowsCalculator({
        viewportSize: height,
        scrollOffset: pos,
        totalItems: wot.getSetting('totalRows'),
        itemSizeFn: function itemSizeFn(sourceRow) {
          return wtTable.getRowHeight(sourceRow);
        },
        overrideFn: wtSettings.settings.viewportRowCalculatorOverride,
        calculationType: calculationType,
        scrollbarHeight: scrollbarHeight
      });
    }
    /**
     * Creates:
     *  - columnsRenderCalculator (before draw, to qualify columns for rendering)
     *  - columnsVisibleCalculator (after draw, to measure which columns are actually visible)
     *
     * @returns {ViewportRowsCalculator}
     */

  }, {
    key: "createColumnsCalculator",
    value: function createColumnsCalculator() {
      var calculationType = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : _calculator.RENDER_TYPE;
      var wot = this.wot;
      var wtSettings = wot.wtSettings,
          wtOverlays = wot.wtOverlays,
          wtTable = wot.wtTable,
          rootDocument = wot.rootDocument;
      var width = this.getViewportWidth();
      var pos = wtOverlays.leftOverlay.getScrollPosition() - wtOverlays.leftOverlay.getTableParentOffset();
      this.columnHeaderHeight = NaN;

      if (pos < 0) {
        pos = 0;
      }

      var fixedColumnsLeft = wot.getSetting('fixedColumnsLeft');

      if (fixedColumnsLeft) {
        var fixedColumnsWidth = wtOverlays.leftOverlay.sumCellSizes(0, fixedColumnsLeft);
        pos += fixedColumnsWidth;
        width -= fixedColumnsWidth;
      }

      if (wtTable.holder.clientWidth !== wtTable.holder.offsetWidth) {
        width -= (0, _element.getScrollbarWidth)(rootDocument);
      }

      return new _calculator.ViewportColumnsCalculator({
        viewportSize: width,
        scrollOffset: pos,
        totalItems: wot.getSetting('totalColumns'),
        itemSizeFn: function itemSizeFn(sourceCol) {
          return wot.wtTable.getColumnWidth(sourceCol);
        },
        overrideFn: wtSettings.settings.viewportColumnCalculatorOverride,
        calculationType: calculationType,
        stretchMode: wot.getSetting('stretchH'),
        stretchingItemWidthFn: function stretchingItemWidthFn(stretchedWidth, column) {
          return wot.getSetting('onBeforeStretchingColumnWidth', stretchedWidth, column);
        }
      });
    }
    /**
     * Creates rowsRenderCalculator and columnsRenderCalculator (before draw, to determine what rows and
     * cols should be rendered)
     *
     * @param fastDraw {Boolean} If `true`, will try to avoid full redraw and only update the border positions.
     *                           If `false` or `undefined`, will perform a full redraw
     * @returns fastDraw {Boolean} The fastDraw value, possibly modified
     */

  }, {
    key: "createRenderCalculators",
    value: function createRenderCalculators() {
      var fastDraw = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : false;
      var runFastDraw = fastDraw;

      if (runFastDraw) {
        var proposedRowsVisibleCalculator = this.createRowsCalculator(_calculator.FULLY_VISIBLE_TYPE);
        var proposedColumnsVisibleCalculator = this.createColumnsCalculator(_calculator.FULLY_VISIBLE_TYPE);

        if (!(this.areAllProposedVisibleRowsAlreadyRendered(proposedRowsVisibleCalculator) && this.areAllProposedVisibleColumnsAlreadyRendered(proposedColumnsVisibleCalculator))) {
          runFastDraw = false;
        }
      }

      if (!runFastDraw) {
        this.rowsRenderCalculator = this.createRowsCalculator(_calculator.RENDER_TYPE);
        this.columnsRenderCalculator = this.createColumnsCalculator(_calculator.RENDER_TYPE);
      } // delete temporarily to make sure that renderers always use rowsRenderCalculator, not rowsVisibleCalculator


      this.rowsVisibleCalculator = null;
      this.columnsVisibleCalculator = null;
      return runFastDraw;
    }
    /**
     * Creates rowsVisibleCalculator and columnsVisibleCalculator (after draw, to determine what are
     * the actually fully visible rows and columns)
     */

  }, {
    key: "createVisibleCalculators",
    value: function createVisibleCalculators() {
      this.rowsVisibleCalculator = this.createRowsCalculator(_calculator.FULLY_VISIBLE_TYPE);
      this.columnsVisibleCalculator = this.createColumnsCalculator(_calculator.FULLY_VISIBLE_TYPE);
    }
    /**
     * Returns information whether proposedRowsVisibleCalculator viewport
     * is contained inside rows rendered in previous draw (cached in rowsRenderCalculator)
     *
     * @param {Object} proposedRowsVisibleCalculator
     * @returns {Boolean} Returns `true` if all proposed visible rows are already rendered (meaning: redraw is not needed).
     *                    Returns `false` if at least one proposed visible row is not already rendered (meaning: redraw is needed)
     */

  }, {
    key: "areAllProposedVisibleRowsAlreadyRendered",
    value: function areAllProposedVisibleRowsAlreadyRendered(proposedRowsVisibleCalculator) {
      if (!this.rowsVisibleCalculator) {
        return false;
      }

      var startRow = proposedRowsVisibleCalculator.startRow,
          endRow = proposedRowsVisibleCalculator.endRow;
      var _this$rowsRenderCalcu = this.rowsRenderCalculator,
          renderedStartRow = _this$rowsRenderCalcu.startRow,
          renderedEndRow = _this$rowsRenderCalcu.endRow;

      if (startRow < renderedStartRow || startRow === renderedStartRow && startRow > 0) {
        return false;
      } else if (endRow > renderedEndRow || endRow === renderedEndRow && endRow < this.wot.getSetting('totalRows') - 1) {
        return false;
      }

      return true;
    }
    /**
     * Returns information whether proposedColumnsVisibleCalculator viewport
     * is contained inside column rendered in previous draw (cached in columnsRenderCalculator)
     *
     * @param {Object} proposedColumnsVisibleCalculator
     * @returns {Boolean} Returns `true` if all proposed visible columns are already rendered (meaning: redraw is not needed).
     *                    Returns `false` if at least one proposed visible column is not already rendered (meaning: redraw is needed)
     */

  }, {
    key: "areAllProposedVisibleColumnsAlreadyRendered",
    value: function areAllProposedVisibleColumnsAlreadyRendered(proposedColumnsVisibleCalculator) {
      if (!this.columnsVisibleCalculator) {
        return false;
      }

      var startColumn = proposedColumnsVisibleCalculator.startColumn,
          endColumn = proposedColumnsVisibleCalculator.endColumn;
      var _this$columnsRenderCa = this.columnsRenderCalculator,
          renderedStartColumn = _this$columnsRenderCa.startColumn,
          renderedEndColumn = _this$columnsRenderCa.endColumn;

      if (startColumn < renderedStartColumn || startColumn === renderedStartColumn && startColumn > 0) {
        return false;
      } else if (endColumn > renderedEndColumn || endColumn === renderedEndColumn && endColumn < this.wot.getSetting('totalColumns') - 1) {
        return false;
      }

      return true;
    }
    /**
     * Resets values in keys of the hasOversizedColumnHeadersMarked object after updateSettings.
     */

  }, {
    key: "resetHasOversizedColumnHeadersMarked",
    value: function resetHasOversizedColumnHeadersMarked() {
      (0, _object.objectEach)(this.hasOversizedColumnHeadersMarked, function (value, key, object) {
        object[key] = void 0;
      });
    }
  }]);

  return Viewport;
}();

var _default = Viewport;
exports.default = _default;