"use strict";

exports.__esModule = true;
exports.default = void 0;

var _constants = require("./constants");

var _viewSizeSet = _interopRequireDefault(require("./viewSizeSet"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Executive model for each table renderer. It's responsible for injecting DOM nodes in a
 * specified order and adjusting the number of elements in the root node.
 *
 * Only this class have rights to juggling DOM elements within the root node (see render method).
 *
 * @class {OrderView}
 */
var OrderView =
/*#__PURE__*/
function () {
  function OrderView(rootNode, nodesPool, childNodeType) {
    _classCallCheck(this, OrderView);

    /**
     * The root node to manage with.
     *
     * @type {HTMLElement}
     */
    this.rootNode = rootNode;
    /**
     * Factory for newly created DOM elements.
     *
     * @type {Function}
     */

    this.nodesPool = nodesPool;
    /**
     * Holder for sizing and positioning of the view.
     *
     * @type {ViewSizeSet}
     */

    this.sizeSet = new _viewSizeSet.default();
    /**
     * Node type which the order view will manage while rendering the DOM elements.
     *
     * @type {String}
     */

    this.childNodeType = childNodeType.toUpperCase();
    /**
     * The visual index of currently processed row.
     *
     * @type {Number}
     */

    this.visualIndex = 0;
    /**
     * The list of DOM elements which are rendered for this render cycle.
     *
     * @type {HTMLElement[]}
     */

    this.collectedNodes = [];
  }
  /**
   * Sets the size for rendered elements. It can be a size for rows, cells or size for row
   * headers etc. it depends for what table renderer this instance was created.
   *
   * @param {Number} size
   * @returns {OrderView}
   */


  _createClass(OrderView, [{
    key: "setSize",
    value: function setSize(size) {
      this.sizeSet.setSize(size);
      return this;
    }
    /**
    * Sets the offset for rendered elements. The offset describes the shift between 0 and
    * the first rendered element according to the scroll position.
     *
     * @param {Number} offset
     * @returns {OrderView}
     */

  }, {
    key: "setOffset",
    value: function setOffset(offset) {
      this.sizeSet.setOffset(offset);
      return this;
    }
    /**
     * Checks if this instance of the view shares the root node with another instance. This happens only once when
     * a row (TR) as a root node is managed by two OrderView instances. If this happens another DOM injection
     * algorithm is performed to achieve consistent order.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isSharedViewSet",
    value: function isSharedViewSet() {
      return this.sizeSet.isShared();
    }
    /**
     * Returns rendered DOM element based on visual index.
     *
     * @param {Number} visualIndex
     * @returns {HTMLElement}
     */

  }, {
    key: "getNode",
    value: function getNode(visualIndex) {
      return visualIndex < this.collectedNodes.length ? this.collectedNodes[visualIndex] : null;
    }
    /**
     * Returns currently processed DOM element.
     *
     * @returns {HTMLElement}
     */

  }, {
    key: "getCurrentNode",
    value: function getCurrentNode() {
      var length = this.collectedNodes.length;
      return length > 0 ? this.collectedNodes[length - 1] : null;
    }
    /**
     * Returns rendered child count for this instance.
     *
     * @returns {Number}
     */

  }, {
    key: "getRenderedChildCount",
    value: function getRenderedChildCount() {
      var rootNode = this.rootNode,
          sizeSet = this.sizeSet;
      var childElementCount = 0;

      if (this.isSharedViewSet()) {
        var element = rootNode.firstElementChild;

        while (element) {
          if (element.tagName === this.childNodeType) {
            childElementCount += 1;
          } else if (sizeSet.isPlaceOn(_constants.WORKING_SPACE_TOP)) {
            break;
          }

          element = element.nextElementSibling;
        }
      } else {
        childElementCount = rootNode.childElementCount;
      }

      return childElementCount;
    }
    /**
     * Setups and prepares all necessary properties and start the rendering process.
     * This method has to be called only once (at the start) for the render cycle.
     */

  }, {
    key: "start",
    value: function start() {
      this.collectedNodes.length = 0;
      this.visualIndex = 0;
      var rootNode = this.rootNode,
          sizeSet = this.sizeSet;
      var isShared = this.isSharedViewSet();

      var _sizeSet$getViewSize = sizeSet.getViewSize(),
          nextSize = _sizeSet$getViewSize.nextSize;

      var childElementCount = this.getRenderedChildCount();

      while (childElementCount < nextSize) {
        var newNode = this.nodesPool();

        if (!isShared || isShared && sizeSet.isPlaceOn(_constants.WORKING_SPACE_BOTTOM)) {
          rootNode.appendChild(newNode);
        } else {
          rootNode.insertBefore(newNode, rootNode.firstChild);
        }

        childElementCount += 1;
      }

      var isSharedPlacedOnTop = isShared && sizeSet.isPlaceOn(_constants.WORKING_SPACE_TOP);

      while (childElementCount > nextSize) {
        rootNode.removeChild(isSharedPlacedOnTop ? rootNode.firstChild : rootNode.lastChild);
        childElementCount -= 1;
      }
    }
    /**
     * Renders the DOM element based on visual index (which is calculated internally).
     * This method has to be called as many times as the size count is met (to cover all previously rendered DOM elements).
     */

  }, {
    key: "render",
    value: function render() {
      var rootNode = this.rootNode,
          sizeSet = this.sizeSet;
      var visualIndex = this.visualIndex;

      if (this.isSharedViewSet() && sizeSet.isPlaceOn(_constants.WORKING_SPACE_BOTTOM)) {
        visualIndex += sizeSet.sharedSize.nextSize;
      }

      var node = rootNode.childNodes[visualIndex];

      if (node.tagName !== this.childNodeType) {
        var newNode = this.nodesPool();
        rootNode.replaceChild(newNode, node);
        node = newNode;
      }

      this.collectedNodes.push(node);
      this.visualIndex += 1;
    }
    /**
     * Ends the render process.
     * This method has to be called only once (at the end) for the render cycle.
     */

  }, {
    key: "end",
    value: function end() {}
  }]);

  return OrderView;
}();

exports.default = OrderView;