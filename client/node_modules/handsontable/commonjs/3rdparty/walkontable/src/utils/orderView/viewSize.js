"use strict";

exports.__esModule = true;
exports.default = void 0;

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Holder for current and next size (count of rendered and to render DOM elements) and offset.
 *
 * @class {ViewSize}
 */
var ViewSize =
/*#__PURE__*/
function () {
  function ViewSize() {
    _classCallCheck(this, ViewSize);

    /**
     * Current size of the rendered DOM elements.
     *
     * @type {Number}
     */
    this.currentSize = 0;
    /**
     * Next size of the rendered DOM elements which should be fulfilled.
     *
     * @type {Number}
     */

    this.nextSize = 0;
    /**
     * Current offset.
     *
     * @type {Number}
     */

    this.currentOffset = 0;
    /**
     * Next ofset.
     *
     * @type {Number}
     */

    this.nextOffset = 0;
  }
  /**
   * Sets new size of the rendered DOM elements.
   *
   * @param {Number} size
   */


  _createClass(ViewSize, [{
    key: "setSize",
    value: function setSize(size) {
      this.currentSize = this.nextSize;
      this.nextSize = size;
    }
    /**
     * Sets new offset.
     *
     * @param {Number} offset
     */

  }, {
    key: "setOffset",
    value: function setOffset(offset) {
      this.currentOffset = this.nextOffset;
      this.nextOffset = offset;
    }
  }]);

  return ViewSize;
}();

exports.default = ViewSize;