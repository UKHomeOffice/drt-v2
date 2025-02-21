"use strict";

exports.__esModule = true;
exports.default = void 0;

var _nodesPool = _interopRequireDefault(require("./../utils/nodesPool"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Base renderer class, abstract logic for specialized renderers.
 *
 * @class BaseRenderer
 */
var BaseRenderer =
/*#__PURE__*/
function () {
  function BaseRenderer(nodeType, rootNode) {
    _classCallCheck(this, BaseRenderer);

    /**
     * Factory for newly created DOM elements.
     *
     * NodePool should be used for each renderer. For the first stage of the refactoring
     * process, only some of the renderers are implemented a new approach.
     *
     * @type {NodesPool|null}
     */
    this.nodesPool = typeof nodeType === 'string' ? new _nodesPool.default(nodeType) : null;
    /**
     * Node type which the renderer will manage while building the table (eg. 'TD', 'TR', 'TH').
     *
     * @type {String}
     */

    this.nodeType = nodeType;
    /**
     * The root node to which newly created elements will be inserted.
     *
     * @type {HTMLElement}
     */

    this.rootNode = rootNode;
    /**
     * The instance of the Table class, a wrapper for all renderers and holder for properties describe table state.
     *
     * @type {TableRenderer}
     */

    this.table = null;
    /**
     * Counter of nodes already added.
     *
     * @type {Number}
     */

    this.renderedNodes = 0;
  }
  /**
   * Sets the table renderer instance to the current renderer.
   *
   * @param {TableRenderer} table The TableRenderer instance.
   */


  _createClass(BaseRenderer, [{
    key: "setTable",
    value: function setTable(table) {
      if (this.nodesPool) {
        this.nodesPool.setRootDocument(table.rootDocument);
      }

      this.table = table;
    }
    /**
     * Adjusts the number of rendered nodes.
     */

  }, {
    key: "adjust",
    value: function adjust() {}
    /**
     * Renders the contents to the elements.
     */

  }, {
    key: "render",
    value: function render() {}
  }]);

  return BaseRenderer;
}();

exports.default = BaseRenderer;