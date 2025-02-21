"use strict";

require("core-js/modules/es.array.index-of");

exports.__esModule = true;
exports.default = void 0;

var _element = require("./../../../../helpers/dom/element");

var _object = require("./../../../../helpers/object");

var _array = require("./../../../../helpers/array");

var _console = require("./../../../../helpers/console");

var _eventManager = _interopRequireDefault(require("./../../../../eventManager"));

var _core = _interopRequireDefault(require("./../core"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

var registeredOverlays = {};
/**
 * Creates an overlay over the original Walkontable instance. The overlay renders the clone of the original Walkontable
 * and (optionally) implements behavior needed for native horizontal and vertical scrolling.
 *
 * @class Overlay
 */

var Overlay =
/*#__PURE__*/
function () {
  _createClass(Overlay, null, [{
    key: "registerOverlay",

    /**
     * Register overlay class.
     *
     * @param {String} type Overlay type, one of the CLONE_TYPES value
     * @param {Overlay} overlayClass Overlay class extended from base overlay class {@link Overlay}
     */
    value: function registerOverlay(type, overlayClass) {
      if (Overlay.CLONE_TYPES.indexOf(type) === -1) {
        throw new Error("Unsupported overlay (".concat(type, ")."));
      }

      registeredOverlays[type] = overlayClass;
    }
    /**
     * Create new instance of overlay type.
     *
     * @param {String} type Overlay type, one of the CLONE_TYPES value
     * @param {Walkontable} wot Walkontable instance
     */

  }, {
    key: "createOverlay",
    value: function createOverlay(type, wot) {
      return new registeredOverlays[type](wot);
    }
    /**
     * Check if specified overlay was registered.
     *
     * @param {String} type Overlay type, one of the CLONE_TYPES value
     * @returns {Boolean}
     */

  }, {
    key: "hasOverlay",
    value: function hasOverlay(type) {
      return registeredOverlays[type] !== void 0;
    }
    /**
     * Checks if overlay object (`overlay`) is instance of overlay type (`type`).
     *
     * @param {Overlay} overlay Overlay object
     * @param {String} type Overlay type, one of the CLONE_TYPES value
     * @returns {Boolean}
     */

  }, {
    key: "isOverlayTypeOf",
    value: function isOverlayTypeOf(overlay, type) {
      if (!overlay || !registeredOverlays[type]) {
        return false;
      }

      return overlay instanceof registeredOverlays[type];
    }
    /**
     * @param {Walkontable} wotInstance
     */

  }, {
    key: "CLONE_TOP",

    /**
     * @type {String}
     */
    get: function get() {
      return 'top';
    }
    /**
     * @type {String}
     */

  }, {
    key: "CLONE_BOTTOM",
    get: function get() {
      return 'bottom';
    }
    /**
     * @type {String}
     */

  }, {
    key: "CLONE_LEFT",
    get: function get() {
      return 'left';
    }
    /**
     * @type {String}
     */

  }, {
    key: "CLONE_TOP_LEFT_CORNER",
    get: function get() {
      return 'top_left_corner';
    }
    /**
     * @type {String}
     */

  }, {
    key: "CLONE_BOTTOM_LEFT_CORNER",
    get: function get() {
      return 'bottom_left_corner';
    }
    /**
     * @type {String}
     */

  }, {
    key: "CLONE_DEBUG",
    get: function get() {
      return 'debug';
    }
    /**
     * List of all availables clone types
     *
     * @type {Array}
     */

  }, {
    key: "CLONE_TYPES",
    get: function get() {
      return [Overlay.CLONE_TOP, Overlay.CLONE_BOTTOM, Overlay.CLONE_LEFT, Overlay.CLONE_TOP_LEFT_CORNER, Overlay.CLONE_BOTTOM_LEFT_CORNER, Overlay.CLONE_DEBUG];
    }
  }]);

  function Overlay(wotInstance) {
    _classCallCheck(this, Overlay);

    (0, _object.defineGetter)(this, 'wot', wotInstance, {
      writable: false
    });
    var _this$wot$wtTable = this.wot.wtTable,
        TABLE = _this$wot$wtTable.TABLE,
        hider = _this$wot$wtTable.hider,
        spreader = _this$wot$wtTable.spreader,
        holder = _this$wot$wtTable.holder,
        wtRootElement = _this$wot$wtTable.wtRootElement; // legacy support, deprecated in the future

    this.instance = this.wot;
    this.type = '';
    this.mainTableScrollableElement = null;
    this.TABLE = TABLE;
    this.hider = hider;
    this.spreader = spreader;
    this.holder = holder;
    this.wtRootElement = wtRootElement;
    this.trimmingContainer = (0, _element.getTrimmingContainer)(this.hider.parentNode.parentNode);
    this.areElementSizesAdjusted = false;
    this.updateStateOfRendering();
  }
  /**
   * Update internal state of object with an information about the need of full rendering of the overlay.
   *
   * @returns {Boolean} Returns `true` if the state has changed since the last check.
   */


  _createClass(Overlay, [{
    key: "updateStateOfRendering",
    value: function updateStateOfRendering() {
      var previousState = this.needFullRender;
      this.needFullRender = this.shouldBeRendered();
      var changed = previousState !== this.needFullRender;

      if (changed && !this.needFullRender) {
        this.reset();
      }

      return changed;
    }
    /**
     * Checks if overlay should be fully rendered
     *
     * @returns {Boolean}
     */

  }, {
    key: "shouldBeRendered",
    value: function shouldBeRendered() {
      return true;
    }
    /**
     * Update the trimming container.
     */

  }, {
    key: "updateTrimmingContainer",
    value: function updateTrimmingContainer() {
      this.trimmingContainer = (0, _element.getTrimmingContainer)(this.hider.parentNode.parentNode);
    }
    /**
     * Update the main scrollable element.
     */

  }, {
    key: "updateMainScrollableElement",
    value: function updateMainScrollableElement() {
      var _this$wot = this.wot,
          wtTable = _this$wot.wtTable,
          rootWindow = _this$wot.rootWindow;

      if (rootWindow.getComputedStyle(wtTable.wtRootElement.parentNode).getPropertyValue('overflow') === 'hidden') {
        this.mainTableScrollableElement = this.wot.wtTable.holder;
      } else {
        this.mainTableScrollableElement = (0, _element.getScrollableElement)(wtTable.TABLE);
      }
    }
    /**
     * Calculates coordinates of the provided element, relative to the root Handsontable element.
     * NOTE: The element needs to be a child of the overlay in order for the method to work correctly.
     *
     * @param {HTMLElement} element The cell element to calculate the position for.
     * @param {Number} rowIndex Visual row index.
     * @param {Number} columnIndex Visual column index.
     * @returns {{top: Number, left: Number}|undefined}
     */

  }, {
    key: "getRelativeCellPosition",
    value: function getRelativeCellPosition(element, rowIndex, columnIndex) {
      if (this.clone.wtTable.holder.contains(element) === false) {
        (0, _console.warn)("The provided element is not a child of the ".concat(this.type, " overlay"));
        return;
      }

      var windowScroll = this.mainTableScrollableElement === this.wot.rootWindow;
      var fixedColumn = columnIndex < this.wot.getSetting('fixedColumnsLeft');
      var fixedRowTop = rowIndex < this.wot.getSetting('fixedRowsTop');
      var fixedRowBottom = rowIndex >= this.wot.getSetting('totalRows') - this.wot.getSetting('fixedRowsBottom');
      var spreaderOffset = {
        left: this.clone.wtTable.spreader.offsetLeft,
        top: this.clone.wtTable.spreader.offsetTop
      };
      var elementOffset = {
        left: element.offsetLeft,
        top: element.offsetTop
      };
      var offsetObject = null;

      if (windowScroll) {
        offsetObject = this.getRelativeCellPositionWithinWindow(fixedRowTop, fixedColumn, elementOffset, spreaderOffset);
      } else {
        offsetObject = this.getRelativeCellPositionWithinHolder(fixedRowTop, fixedRowBottom, fixedColumn, elementOffset, spreaderOffset);
      }

      return offsetObject;
    }
    /**
     * Calculates coordinates of the provided element, relative to the root Handsontable element within a table with window
     * as a scrollable element.
     *
     * @private
     * @param {Boolean} onFixedRowTop `true` if the coordinates point to a place within the top fixed rows.
     * @param {Boolean} onFixedColumn `true` if the coordinates point to a place within the fixed columns.
     * @param {Number} elementOffset Offset position of the cell element.
     * @param {Number} spreaderOffset Offset position of the spreader element.
     * @returns {{top: Number, left: Number}}
     */

  }, {
    key: "getRelativeCellPositionWithinWindow",
    value: function getRelativeCellPositionWithinWindow(onFixedRowTop, onFixedColumn, elementOffset, spreaderOffset) {
      var absoluteRootElementPosition = this.wot.wtTable.wtRootElement.getBoundingClientRect();
      var horizontalOffset = 0;
      var verticalOffset = 0;

      if (!onFixedColumn) {
        horizontalOffset = spreaderOffset.left;
      } else {
        horizontalOffset = absoluteRootElementPosition.left <= 0 ? -1 * absoluteRootElementPosition.left : 0;
      }

      if (onFixedRowTop) {
        var absoluteOverlayPosition = this.clone.wtTable.TABLE.getBoundingClientRect();
        verticalOffset = absoluteOverlayPosition.top - absoluteRootElementPosition.top;
      } else {
        verticalOffset = spreaderOffset.top;
      }

      return {
        left: elementOffset.left + horizontalOffset,
        top: elementOffset.top + verticalOffset
      };
    }
    /**
     * Calculates coordinates of the provided element, relative to the root Handsontable element within a table with window
     * as a scrollable element.
     *
     * @private
     * @param {Boolean} onFixedRowTop `true` if the coordinates point to a place within the top fixed rows.
     * @param {Boolean} onFixedRowBottom `true` if the coordinates point to a place within the bottom fixed rows.
     * @param {Boolean} onFixedColumn `true` if the coordinates point to a place within the fixed columns.
     * @param {Number} elementOffset Offset position of the cell element.
     * @param {Number} spreaderOffset Offset position of the spreader element.
     * @returns {{top: Number, left: Number}}
     */

  }, {
    key: "getRelativeCellPositionWithinHolder",
    value: function getRelativeCellPositionWithinHolder(onFixedRowTop, onFixedRowBottom, onFixedColumn, elementOffset, spreaderOffset) {
      var tableScrollPosition = {
        horizontal: this.clone.cloneSource.wtOverlays.leftOverlay.getScrollPosition(),
        vertical: this.clone.cloneSource.wtOverlays.topOverlay.getScrollPosition()
      };
      var horizontalOffset = 0;
      var verticalOffset = 0;

      if (!onFixedColumn) {
        horizontalOffset = tableScrollPosition.horizontal - spreaderOffset.left;
      }

      if (onFixedRowBottom) {
        var absoluteRootElementPosition = this.wot.wtTable.wtRootElement.getBoundingClientRect();
        var absoluteOverlayPosition = this.clone.wtTable.TABLE.getBoundingClientRect();
        verticalOffset = absoluteOverlayPosition.top * -1 + absoluteRootElementPosition.top;
      } else if (!onFixedRowTop) {
        verticalOffset = tableScrollPosition.vertical - spreaderOffset.top;
      }

      return {
        left: elementOffset.left - horizontalOffset,
        top: elementOffset.top - verticalOffset
      };
    }
    /**
     * Make a clone of table for overlay
     *
     * @param {String} direction Can be `Overlay.CLONE_TOP`, `Overlay.CLONE_LEFT`,
     *                           `Overlay.CLONE_TOP_LEFT_CORNER`, `Overlay.CLONE_DEBUG`
     * @returns {Walkontable}
     */

  }, {
    key: "makeClone",
    value: function makeClone(direction) {
      if (Overlay.CLONE_TYPES.indexOf(direction) === -1) {
        throw new Error("Clone type \"".concat(direction, "\" is not supported."));
      }

      var _this$wot2 = this.wot,
          wtTable = _this$wot2.wtTable,
          rootDocument = _this$wot2.rootDocument,
          rootWindow = _this$wot2.rootWindow;
      var clone = rootDocument.createElement('DIV');
      var clonedTable = rootDocument.createElement('TABLE');
      clone.className = "ht_clone_".concat(direction, " handsontable");
      clone.style.position = 'absolute';
      clone.style.top = 0;
      clone.style.left = 0;
      clone.style.overflow = 'hidden';
      clonedTable.className = wtTable.TABLE.className;
      clone.appendChild(clonedTable);
      this.type = direction;
      wtTable.wtRootElement.parentNode.appendChild(clone);
      var preventOverflow = this.wot.getSetting('preventOverflow');

      if (preventOverflow === true || preventOverflow === 'horizontal' && this.type === Overlay.CLONE_TOP || preventOverflow === 'vertical' && this.type === Overlay.CLONE_LEFT) {
        this.mainTableScrollableElement = rootWindow;
      } else if (rootWindow.getComputedStyle(wtTable.wtRootElement.parentNode).getPropertyValue('overflow') === 'hidden') {
        this.mainTableScrollableElement = wtTable.holder;
      } else {
        this.mainTableScrollableElement = (0, _element.getScrollableElement)(wtTable.TABLE);
      }

      return new _core.default({
        cloneSource: this.wot,
        cloneOverlay: this,
        table: clonedTable
      });
    }
    /**
     * Refresh/Redraw overlay
     *
     * @param {Boolean} [fastDraw=false]
     */

  }, {
    key: "refresh",
    value: function refresh() {
      var fastDraw = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : false;
      // When hot settings are changed we allow to refresh overlay once before blocking
      var nextCycleRenderFlag = this.shouldBeRendered();

      if (this.clone && (this.needFullRender || nextCycleRenderFlag)) {
        this.clone.draw(fastDraw);
      }

      this.needFullRender = nextCycleRenderFlag;
    }
    /**
     * Reset overlay styles to initial values.
     */

  }, {
    key: "reset",
    value: function reset() {
      if (!this.clone) {
        return;
      }

      var holder = this.clone.wtTable.holder;
      var hider = this.clone.wtTable.hider;
      var holderStyle = holder.style;
      var hidderStyle = hider.style;
      var rootStyle = holder.parentNode.style;
      (0, _array.arrayEach)([holderStyle, hidderStyle, rootStyle], function (style) {
        style.width = '';
        style.height = '';
      });
    }
    /**
     * Destroy overlay instance
     */

  }, {
    key: "destroy",
    value: function destroy() {
      new _eventManager.default(this.clone).destroy();
    }
  }]);

  return Overlay;
}();

var _default = Overlay;
exports.default = _default;