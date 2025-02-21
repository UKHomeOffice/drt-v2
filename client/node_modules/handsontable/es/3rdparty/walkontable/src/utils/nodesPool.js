function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Factory for newly created DOM elements.
 *
 * @class {NodesPool}
 */
var NodesPool =
/*#__PURE__*/
function () {
  function NodesPool(nodeType) {
    _classCallCheck(this, NodesPool);

    /**
     * Node type to generate (ew 'th', 'td').
     *
     * @type {String}
     */
    this.nodeType = nodeType.toUpperCase();
  }
  /**
   * Set document owner for this instance.
   *
   * @param {HTMLDocument} rootDocument
   */


  _createClass(NodesPool, [{
    key: "setRootDocument",
    value: function setRootDocument(rootDocument) {
      this.rootDocument = rootDocument;
    }
    /**
     * Obtains an element. The returned elements in the feature can be cached.
     *
     * @returns {HTMLElement}
     */

  }, {
    key: "obtain",
    value: function obtain() {
      return this.rootDocument.createElement(this.nodeType);
    }
  }]);

  return NodesPool;
}();

export { NodesPool as default };