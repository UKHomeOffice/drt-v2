"use strict";

exports.__esModule = true;
exports.default = void 0;

var _viewSize = _interopRequireDefault(require("./viewSize"));

var _constants = require("./constants");

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * The class is a source of the truth of information about the current and
 * next size of the rendered DOM elements and current and next offset of
 * the view. That information allows us to calculate diff between current
 * DOM order and this which should be rendered without touching the DOM API at all.
 *
 * Mostly the ViewSizeSet is created for each individual renderer. But in
 * the table, there is one case where this size information should be shared
 * between two different instances (different table renderers). This is a TR
 * element which can contain TH elements - managed by own renderer and
 * TD elements - managed by another renderer. To generate correct DOM order
 * for them it is required to connect these two instances by reference
 * through `sharedSize`.
 *
 * @class {ViewSizeSet}
 */
var ViewSizeSet =
/*#__PURE__*/
function () {
  function ViewSizeSet() {
    _classCallCheck(this, ViewSizeSet);

    /**
     * Holder for current and next view size and offset.
     *
     * @type {ViewSize}
     */
    this.size = new _viewSize.default();
    /**
     * Defines if this instance shares its size with another instance. If it's in the shared
     * mode it defines what space it occupies ('top' or 'bottom').
     *
     * @type {Number}
     */

    this.workingSpace = _constants.WORKING_SPACE_ALL;
    /**
     * Shared Size instance.
     *
     * @type {ViewSize}
     */

    this.sharedSize = null;
  }
  /**
   * Sets the size for rendered elements. It can be a size for rows, cells or size for row
   * headers etc.
   *
   * @param {Number} size
   */


  _createClass(ViewSizeSet, [{
    key: "setSize",
    value: function setSize(size) {
      this.size.setSize(size);
    }
    /**
     * Sets the offset for rendered elements. The offset describes the shift between 0 and
     * the first rendered element according to the scroll position.
     *
     * @param {Number} offset
     */

  }, {
    key: "setOffset",
    value: function setOffset(offset) {
      this.size.setOffset(offset);
    }
    /**
     * Returns ViewSize instance.
     *
     * @returns {ViewSize}
     */

  }, {
    key: "getViewSize",
    value: function getViewSize() {
      return this.size;
    }
    /**
     * Checks if this ViewSizeSet is sharing the size with another instance.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isShared",
    value: function isShared() {
      return this.sharedSize instanceof _viewSize.default;
    }
    /**
     * Checks what working space describes this size instance.
     *
     * @param {Number} workingSpace The number which describes the type of the working space (see constants.js).
     * @returns {Boolean}
     */

  }, {
    key: "isPlaceOn",
    value: function isPlaceOn(workingSpace) {
      return this.workingSpace === workingSpace;
    }
    /**
     * Appends the ViewSizeSet instance to this instance that turns it into a shared mode.
     *
     * @param {ViewSizeSet} viewSizeSet
     */

  }, {
    key: "append",
    value: function append(viewSize) {
      this.workingSpace = _constants.WORKING_SPACE_TOP;
      viewSize.workingSpace = _constants.WORKING_SPACE_BOTTOM;
      this.sharedSize = viewSize.getViewSize();
    }
    /**
     * Prepends the ViewSize instance to this instance that turns it into a shared mode.
     *
     * @param {ViewSizeSet} viewSizeSet
     */

  }, {
    key: "prepend",
    value: function prepend(viewSize) {
      this.workingSpace = _constants.WORKING_SPACE_BOTTOM;
      viewSize.workingSpace = _constants.WORKING_SPACE_TOP;
      this.sharedSize = viewSize.getViewSize();
    }
  }]);

  return ViewSizeSet;
}();

exports.default = ViewSizeSet;