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

var _left = _interopRequireDefault(require("./../table/left"));

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
 * @class LeftOverlay
 */
var LeftOverlay =
/*#__PURE__*/
function (_Overlay) {
  _inherits(LeftOverlay, _Overlay);

  /**
   * @param {Walkontable} wotInstance
   */
  function LeftOverlay(wotInstance) {
    var _this;

    _classCallCheck(this, LeftOverlay);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(LeftOverlay).call(this, wotInstance));
    _this.clone = _this.makeClone(_base.default.CLONE_LEFT);
    return _this;
  }
  /**
   * Factory method to create a subclass of `Table` that is relevant to this overlay.
   *
   * @see Table#constructor
   * @param {...*} args Parameters that will be forwarded to the `Table` constructor
   * @returns {Table}
   */


  _createClass(LeftOverlay, [{
    key: "createTable",
    value: function createTable() {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }

      return _construct(_left.default, args);
    }
    /**
     * Checks if overlay should be fully rendered.
     *
     * @returns {Boolean}
     */

  }, {
    key: "shouldBeRendered",
    value: function shouldBeRendered() {
      return !!(this.wot.getSetting('fixedColumnsLeft') || this.wot.getSetting('rowHeaders').length);
    }
    /**
     * Updates the left overlay position.
     */

  }, {
    key: "resetFixedPosition",
    value: function resetFixedPosition() {
      var wtTable = this.wot.wtTable;

      if (!this.needFullRender || !wtTable.holder.parentNode) {
        // removed from DOM
        return;
      }

      var overlayRoot = this.clone.wtTable.holder.parentNode;
      var headerPosition = 0;
      var preventOverflow = this.wot.getSetting('preventOverflow');

      if (this.trimmingContainer === this.wot.rootWindow && (!preventOverflow || preventOverflow !== 'horizontal')) {
        var box = wtTable.hider.getBoundingClientRect();
        var left = Math.ceil(box.left);
        var right = Math.ceil(box.right);
        var finalLeft;
        var finalTop;
        finalTop = wtTable.hider.style.top;
        finalTop = finalTop === '' ? 0 : finalTop;

        if (left < 0 && right - overlayRoot.offsetWidth > 0) {
          finalLeft = -left;
        } else {
          finalLeft = 0;
        }

        headerPosition = finalLeft;
        finalLeft += 'px';
        (0, _element.setOverlayPosition)(overlayRoot, finalLeft, finalTop);
      } else {
        headerPosition = this.getScrollPosition();
        (0, _element.resetCssTransform)(overlayRoot);
      }

      this.adjustHeaderBordersPosition(headerPosition);
      this.adjustElementsSize();
    }
    /**
     * Sets the main overlay's horizontal scroll position.
     *
     * @param {Number} pos
     * @returns {Boolean}
     */

  }, {
    key: "setScrollPosition",
    value: function setScrollPosition(pos) {
      var rootWindow = this.wot.rootWindow;
      var result = false;

      if (this.mainTableScrollableElement === rootWindow && rootWindow.scrollX !== pos) {
        rootWindow.scrollTo(pos, (0, _element.getWindowScrollTop)(rootWindow));
        result = true;
      } else if (this.mainTableScrollableElement.scrollLeft !== pos) {
        this.mainTableScrollableElement.scrollLeft = pos;
        result = true;
      }

      return result;
    }
    /**
     * Triggers onScroll hook callback.
     */

  }, {
    key: "onScroll",
    value: function onScroll() {
      this.wot.getSetting('onScrollVertically');
    }
    /**
     * Calculates total sum cells width.
     *
     * @param {Number} from Column index which calculates started from.
     * @param {Number} to Column index where calculation is finished.
     * @returns {Number} Width sum.
     */

  }, {
    key: "sumCellSizes",
    value: function sumCellSizes(from, to) {
      var defaultColumnWidth = this.wot.wtSettings.defaultColumnWidth;
      var column = from;
      var sum = 0;

      while (column < to) {
        sum += this.wot.wtTable.getStretchedColumnWidth(column) || defaultColumnWidth;
        column += 1;
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
      var _this$wot = this.wot,
          wtTable = _this$wot.wtTable,
          rootDocument = _this$wot.rootDocument,
          rootWindow = _this$wot.rootWindow;
      var scrollbarHeight = (0, _element.getScrollbarWidth)(rootDocument);
      var overlayRoot = this.clone.wtTable.holder.parentNode;
      var overlayRootStyle = overlayRoot.style;
      var preventOverflow = this.wot.getSetting('preventOverflow');

      if (this.trimmingContainer !== rootWindow || preventOverflow === 'vertical') {
        var height = this.wot.wtViewport.getWorkspaceHeight();

        if (this.wot.wtOverlays.hasScrollbarBottom) {
          height -= scrollbarHeight;
        }

        height = Math.min(height, wtTable.wtRootElement.scrollHeight);
        overlayRootStyle.height = "".concat(height, "px");
      } else {
        overlayRootStyle.height = '';
      }

      this.clone.wtTable.holder.style.height = overlayRootStyle.height;
      var tableWidth = (0, _element.outerWidth)(this.clone.wtTable.TABLE);
      overlayRootStyle.width = "".concat(tableWidth === 0 ? tableWidth : tableWidth + 4, "px");
    }
    /**
     * Adjust overlay root childs size.
     */

  }, {
    key: "adjustRootChildrenSize",
    value: function adjustRootChildrenSize() {
      var scrollbarWidth = (0, _element.getScrollbarWidth)(this.wot.rootDocument);
      this.clone.wtTable.hider.style.height = this.hider.style.height;
      this.clone.wtTable.holder.style.height = this.clone.wtTable.holder.parentNode.style.height;

      if (scrollbarWidth === 0) {
        scrollbarWidth = 30;
      }

      this.clone.wtTable.holder.style.width = "".concat(parseInt(this.clone.wtTable.holder.parentNode.style.width, 10) + scrollbarWidth, "px");
    }
    /**
     * Adjust the overlay dimensions and position.
     */

  }, {
    key: "applyToDOM",
    value: function applyToDOM() {
      var total = this.wot.getSetting('totalColumns');

      if (!this.areElementSizesAdjusted) {
        this.adjustElementsSize();
      }

      if (typeof this.wot.wtViewport.columnsRenderCalculator.startPosition === 'number') {
        this.spreader.style.left = "".concat(this.wot.wtViewport.columnsRenderCalculator.startPosition, "px");
      } else if (total === 0) {
        this.spreader.style.left = '0';
      } else {
        throw new Error('Incorrect value of the columnsRenderCalculator');
      }

      this.spreader.style.right = '';

      if (this.needFullRender) {
        this.syncOverlayOffset();
      }
    }
    /**
     * Synchronize calculated top position to an element.
     */

  }, {
    key: "syncOverlayOffset",
    value: function syncOverlayOffset() {
      if (typeof this.wot.wtViewport.rowsRenderCalculator.startPosition === 'number') {
        this.clone.wtTable.spreader.style.top = "".concat(this.wot.wtViewport.rowsRenderCalculator.startPosition, "px");
      } else {
        this.clone.wtTable.spreader.style.top = '';
      }
    }
    /**
     * Scrolls horizontally to a column at the left edge of the viewport.
     *
     * @param {Number} sourceCol  Column index which you want to scroll to.
     * @param {Boolean} [beyondRendered]  if `true`, scrolls according to the bottom edge (top edge is by default).
     * @returns {Boolean}
     */

  }, {
    key: "scrollTo",
    value: function scrollTo(sourceCol, beyondRendered) {
      var newX = this.getTableParentOffset();
      var sourceInstance = this.wot.cloneSource ? this.wot.cloneSource : this.wot;
      var mainHolder = sourceInstance.wtTable.holder;
      var scrollbarCompensation = 0;

      if (beyondRendered && mainHolder.offsetWidth !== mainHolder.clientWidth) {
        scrollbarCompensation = (0, _element.getScrollbarWidth)(this.wot.rootDocument);
      }

      if (beyondRendered) {
        newX += this.sumCellSizes(0, sourceCol + 1);
        newX -= this.wot.wtViewport.getViewportWidth();
      } else {
        newX += this.sumCellSizes(this.wot.getSetting('fixedColumnsLeft'), sourceCol);
      }

      newX += scrollbarCompensation;
      return this.setScrollPosition(newX);
    }
    /**
     * Gets table parent left position.
     *
     * @returns {Number}
     */

  }, {
    key: "getTableParentOffset",
    value: function getTableParentOffset() {
      var preventOverflow = this.wot.getSetting('preventOverflow');
      var offset = 0;

      if (!preventOverflow && this.trimmingContainer === this.wot.rootWindow) {
        offset = this.wot.wtTable.holderOffset.left;
      }

      return offset;
    }
    /**
     * Gets the main overlay's horizontal scroll position.
     *
     * @returns {Number} Main table's vertical scroll position.
     */

  }, {
    key: "getScrollPosition",
    value: function getScrollPosition() {
      return (0, _element.getScrollLeft)(this.mainTableScrollableElement, this.wot.rootWindow);
    }
    /**
     * Adds css classes to hide the header border's header (cell-selection border hiding issue).
     *
     * @param {Number} position Header X position if trimming container is window or scroll top if not.
     */

  }, {
    key: "adjustHeaderBordersPosition",
    value: function adjustHeaderBordersPosition(position) {
      var masterParent = this.wot.wtTable.holder.parentNode;
      var rowHeaders = this.wot.getSetting('rowHeaders');
      var fixedColumnsLeft = this.wot.getSetting('fixedColumnsLeft');
      var totalRows = this.wot.getSetting('totalRows');

      if (totalRows) {
        (0, _element.removeClass)(masterParent, 'emptyRows');
      } else {
        (0, _element.addClass)(masterParent, 'emptyRows');
      }

      if (fixedColumnsLeft && !rowHeaders.length) {
        (0, _element.addClass)(masterParent, 'innerBorderLeft');
      } else if (!fixedColumnsLeft && rowHeaders.length) {
        var previousState = (0, _element.hasClass)(masterParent, 'innerBorderLeft');

        if (position) {
          (0, _element.addClass)(masterParent, 'innerBorderLeft');
        } else {
          (0, _element.removeClass)(masterParent, 'innerBorderLeft');
        }

        if (!previousState && position || previousState && !position) {
          this.wot.wtOverlays.adjustElementsSize();
        }
      }
    }
  }]);

  return LeftOverlay;
}(_base.default);

_base.default.registerOverlay(_base.default.CLONE_LEFT, LeftOverlay);

var _default = LeftOverlay;
exports.default = _default;