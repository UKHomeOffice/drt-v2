"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.construct");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _element = require("./../../../../helpers/dom/element");

var _bottom = _interopRequireDefault(require("./../table/bottom"));

var _base = _interopRequireDefault(require("./_base"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function isNativeReflectConstruct() { if (typeof Reflect === "undefined" || !Reflect.construct) return false; if (Reflect.construct.sham) return false; if (typeof Proxy === "function") return true; try { Date.prototype.toString.call(Reflect.construct(Date, [], function () {})); return true; } catch (e) { return false; } }

function _construct(Parent, args, Class) { if (isNativeReflectConstruct()) { _construct = Reflect.construct; } else { _construct = function _construct(Parent, args, Class) { var a = [null]; a.push.apply(a, args); var Constructor = Function.bind.apply(Parent, a); var instance = new Constructor(); if (Class) _setPrototypeOf(instance, Class.prototype); return instance; }; } return _construct.apply(null, arguments); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * @class BottomOverlay
 */
var BottomOverlay =
/*#__PURE__*/
function (_Overlay) {
  _inherits(BottomOverlay, _Overlay);

  /**
   * @param {Walkontable} wotInstance
   */
  function BottomOverlay(wotInstance) {
    var _this;

    _classCallCheck(this, BottomOverlay);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(BottomOverlay).call(this, wotInstance));
    _this.clone = _this.makeClone(_base.default.CLONE_BOTTOM);
    return _this;
  }
  /**
   * Factory method to create a subclass of `Table` that is relevant to this overlay.
   *
   * @see Table#constructor
   * @param {...*} args Parameters that will be forwarded to the `Table` constructor
   * @returns {Table}
   */


  _createClass(BottomOverlay, [{
    key: "createTable",
    value: function createTable() {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }

      return _construct(_bottom.default, args);
    }
    /**
     *
     */

  }, {
    key: "repositionOverlay",
    value: function repositionOverlay() {
      var _this$wot = this.wot,
          wtTable = _this$wot.wtTable,
          rootDocument = _this$wot.rootDocument;
      var cloneRoot = this.clone.wtTable.holder.parentNode;
      var scrollbarWidth = (0, _element.getScrollbarWidth)(rootDocument);

      if (wtTable.holder.clientHeight === wtTable.holder.offsetHeight) {
        scrollbarWidth = 0;
      }

      cloneRoot.style.top = '';
      cloneRoot.style.bottom = "".concat(scrollbarWidth, "px");
    }
    /**
     * Checks if overlay should be fully rendered
     *
     * @returns {Boolean}
     */

  }, {
    key: "shouldBeRendered",
    value: function shouldBeRendered() {
      /* eslint-disable no-unneeded-ternary */
      return this.wot.getSetting('fixedRowsBottom') ? true : false;
    }
    /**
     * Updates the top overlay position
     */

  }, {
    key: "resetFixedPosition",
    value: function resetFixedPosition() {
      if (!this.needFullRender || !this.wot.wtTable.holder.parentNode) {
        // removed from DOM
        return;
      }

      var overlayRoot = this.clone.wtTable.holder.parentNode;
      var headerPosition = 0;
      overlayRoot.style.top = '';
      var preventOverflow = this.wot.getSetting('preventOverflow');

      if (this.trimmingContainer === this.wot.rootWindow && (!preventOverflow || preventOverflow !== 'vertical')) {
        var _this$wot2 = this.wot,
            rootDocument = _this$wot2.rootDocument,
            wtTable = _this$wot2.wtTable;
        var box = wtTable.hider.getBoundingClientRect();
        var bottom = Math.ceil(box.bottom);
        var finalLeft;
        var finalBottom;
        var bodyHeight = rootDocument.body.offsetHeight;
        finalLeft = wtTable.hider.style.left;
        finalLeft = finalLeft === '' ? 0 : finalLeft;

        if (bottom > bodyHeight) {
          finalBottom = bottom - bodyHeight;
        } else {
          finalBottom = 0;
        }

        headerPosition = finalBottom;
        finalBottom += 'px';
        overlayRoot.style.top = '';
        overlayRoot.style.left = finalLeft;
        overlayRoot.style.bottom = finalBottom;
      } else {
        headerPosition = this.getScrollPosition();
        (0, _element.resetCssTransform)(overlayRoot);
        this.repositionOverlay();
      }

      this.adjustHeaderBordersPosition(headerPosition);
      this.adjustElementsSize();
    }
    /**
     * Sets the main overlay's vertical scroll position
     *
     * @param {Number} pos
     */

  }, {
    key: "setScrollPosition",
    value: function setScrollPosition(pos) {
      var rootWindow = this.wot.rootWindow;
      var result = false;

      if (this.mainTableScrollableElement === rootWindow) {
        rootWindow.scrollTo((0, _element.getWindowScrollLeft)(rootWindow), pos);
        result = true;
      } else if (this.mainTableScrollableElement.scrollTop !== pos) {
        this.mainTableScrollableElement.scrollTop = pos;
        result = true;
      }

      return result;
    }
    /**
     * Triggers onScroll hook callback
     */

  }, {
    key: "onScroll",
    value: function onScroll() {
      this.wot.getSetting('onScrollHorizontally');
    }
    /**
     * Calculates total sum cells height
     *
     * @param {Number} from Row index which calculates started from
     * @param {Number} to Row index where calculation is finished
     * @returns {Number} Height sum
     */

  }, {
    key: "sumCellSizes",
    value: function sumCellSizes(from, to) {
      var _this$wot3 = this.wot,
          wtTable = _this$wot3.wtTable,
          wtSettings = _this$wot3.wtSettings;
      var defaultRowHeight = wtSettings.settings.defaultRowHeight;
      var row = from;
      var sum = 0;

      while (row < to) {
        var height = wtTable.getRowHeight(row);
        sum += height === void 0 ? defaultRowHeight : height;
        row += 1;
      }

      return sum;
    }
    /**
     * Adjust overlay root element, childs and master table element sizes (width, height).
     *
     * @param {Boolean} [force=false]
     */

  }, {
    key: "adjustElementsSize",
    value: function adjustElementsSize() {
      var force = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : false;
      this.updateTrimmingContainer();

      if (this.needFullRender || force) {
        this.adjustRootElementSize();
        this.adjustRootChildrenSize();

        if (!force) {
          this.areElementSizesAdjusted = true;
        }
      }
    }
    /**
     * Adjust overlay root element size (width and height).
     */

  }, {
    key: "adjustRootElementSize",
    value: function adjustRootElementSize() {
      var _this$wot4 = this.wot,
          wtTable = _this$wot4.wtTable,
          wtViewport = _this$wot4.wtViewport,
          rootWindow = _this$wot4.rootWindow;
      var scrollbarWidth = (0, _element.getScrollbarWidth)(this.wot.rootDocument);
      var overlayRoot = this.clone.wtTable.holder.parentNode;
      var overlayRootStyle = overlayRoot.style;
      var preventOverflow = this.wot.getSetting('preventOverflow');

      if (this.trimmingContainer !== rootWindow || preventOverflow === 'horizontal') {
        var width = wtViewport.getWorkspaceWidth();

        if (this.wot.wtOverlays.hasScrollbarRight) {
          width -= scrollbarWidth;
        }

        width = Math.min(width, wtTable.wtRootElement.scrollWidth);
        overlayRootStyle.width = "".concat(width, "px");
      } else {
        overlayRootStyle.width = '';
      }

      this.clone.wtTable.holder.style.width = overlayRootStyle.width;
      var tableHeight = (0, _element.outerHeight)(this.clone.wtTable.TABLE);

      if (!this.wot.wtTable.hasDefinedSize()) {
        tableHeight = 0;
      }

      overlayRootStyle.height = "".concat(tableHeight === 0 ? tableHeight : tableHeight, "px");
    }
    /**
     * Adjust overlay root childs size
     */

  }, {
    key: "adjustRootChildrenSize",
    value: function adjustRootChildrenSize() {
      var scrollbarWidth = (0, _element.getScrollbarWidth)(this.wot.rootDocument);
      this.clone.wtTable.hider.style.width = this.hider.style.width;
      this.clone.wtTable.holder.style.width = this.clone.wtTable.holder.parentNode.style.width;

      if (scrollbarWidth === 0) {
        scrollbarWidth = 30;
      }

      this.clone.wtTable.holder.style.height = "".concat(parseInt(this.clone.wtTable.holder.parentNode.style.height, 10) + scrollbarWidth, "px");
    }
    /**
     * Adjust the overlay dimensions and position
     */

  }, {
    key: "applyToDOM",
    value: function applyToDOM() {
      var total = this.wot.getSetting('totalRows');

      if (!this.areElementSizesAdjusted) {
        this.adjustElementsSize();
      }

      if (typeof this.wot.wtViewport.rowsRenderCalculator.startPosition === 'number') {
        this.spreader.style.top = "".concat(this.wot.wtViewport.rowsRenderCalculator.startPosition, "px");
      } else if (total === 0) {
        // can happen if there are 0 rows
        this.spreader.style.top = '0';
      } else {
        throw new Error('Incorrect value of the rowsRenderCalculator');
      }

      this.spreader.style.bottom = '';

      if (this.needFullRender) {
        this.syncOverlayOffset();
      }
    }
    /**
     * Synchronize calculated left position to an element
     */

  }, {
    key: "syncOverlayOffset",
    value: function syncOverlayOffset() {
      if (typeof this.wot.wtViewport.columnsRenderCalculator.startPosition === 'number') {
        this.clone.wtTable.spreader.style.left = "".concat(this.wot.wtViewport.columnsRenderCalculator.startPosition, "px");
      } else {
        this.clone.wtTable.spreader.style.left = '';
      }
    }
    /**
     * Scrolls vertically to a row
     *
     * @param sourceRow {Number} Row index which you want to scroll to
     * @param [bottomEdge=false] {Boolean} if `true`, scrolls according to the bottom edge (top edge is by default)
     */

  }, {
    key: "scrollTo",
    value: function scrollTo(sourceRow, bottomEdge) {
      var newY = this.getTableParentOffset();
      var sourceInstance = this.wot.cloneSource ? this.wot.cloneSource : this.wot;
      var mainHolder = sourceInstance.wtTable.holder;
      var scrollbarCompensation = 0;

      if (bottomEdge && mainHolder.offsetHeight !== mainHolder.clientHeight) {
        scrollbarCompensation = (0, _element.getScrollbarWidth)(this.wot.rootDocument);
      }

      if (bottomEdge) {
        newY += this.sumCellSizes(0, sourceRow + 1);
        newY -= this.wot.wtViewport.getViewportHeight(); // Fix 1 pixel offset when cell is selected

        newY += 1;
      } else {
        newY += this.sumCellSizes(this.wot.getSetting('fixedRowsBottom'), sourceRow);
      }

      newY += scrollbarCompensation;
      this.setScrollPosition(newY);
    }
    /**
     * Gets table parent top position
     *
     * @returns {Number}
     */

  }, {
    key: "getTableParentOffset",
    value: function getTableParentOffset() {
      if (this.mainTableScrollableElement === this.wot.rootWindow) {
        return this.wot.wtTable.holderOffset.top;
      }

      return 0;
    }
    /**
     * Gets the main overlay's vertical scroll position
     *
     * @returns {Number} Main table's vertical scroll position
     */

  }, {
    key: "getScrollPosition",
    value: function getScrollPosition() {
      return (0, _element.getScrollTop)(this.mainTableScrollableElement, this.wot.rootWindow);
    }
    /**
     * Adds css classes to hide the header border's header (cell-selection border hiding issue)
     *
     * @param {Number} position Header Y position if trimming container is window or scroll top if not
     */

  }, {
    key: "adjustHeaderBordersPosition",
    value: function adjustHeaderBordersPosition(position) {
      if (this.wot.getSetting('fixedRowsBottom') === 0 && this.wot.getSetting('columnHeaders').length > 0) {
        var masterParent = this.wot.wtTable.holder.parentNode;
        var previousState = (0, _element.hasClass)(masterParent, 'innerBorderTop');

        if (position) {
          (0, _element.addClass)(masterParent, 'innerBorderTop');
        } else {
          (0, _element.removeClass)(masterParent, 'innerBorderTop');
        }

        if (!previousState && position || previousState && !position) {
          this.wot.wtOverlays.adjustElementsSize();
        }
      } // nasty workaround for double border in the header, TODO: find a pure-css solution


      if (this.wot.getSetting('rowHeaders').length === 0) {
        var secondHeaderCell = this.clone.wtTable.THEAD.querySelector('th:nth-of-type(2)');

        if (secondHeaderCell) {
          secondHeaderCell.style['border-left-width'] = 0;
        }
      }
    }
  }]);

  return BottomOverlay;
}(_base.default);

_base.default.registerOverlay(_base.default.CLONE_BOTTOM, BottomOverlay);

var _default = BottomOverlay;
exports.default = _default;