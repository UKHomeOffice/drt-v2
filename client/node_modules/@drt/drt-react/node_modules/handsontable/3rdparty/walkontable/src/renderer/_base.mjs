import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { NodesPool } from "../utils/nodesPool.mjs";
/**
 * Base renderer class, abstract logic for specialized renderers.
 *
 * @class BaseRenderer
 */
export class BaseRenderer {
  constructor(nodeType, rootNode) {
    /**
     * Factory for newly created DOM elements.
     *
     * NodePool should be used for each renderer. For the first stage of the refactoring
     * process, only some of the renderers are implemented a new approach.
     *
     * @type {NodesPool|null}
     */
    _defineProperty(this, "nodesPool", null);
    /**
     * Node type which the renderer will manage while building the table (eg. 'TD', 'TR', 'TH').
     *
     * @type {string}
     */
    _defineProperty(this, "nodeType", void 0);
    /**
     * The root node to which newly created elements will be inserted.
     *
     * @type {HTMLElement}
     */
    _defineProperty(this, "rootNode", void 0);
    /**
     * The instance of the Table class, a wrapper for all renderers and holder for properties describe table state.
     *
     * @type {TableRenderer}
     */
    _defineProperty(this, "table", null);
    /**
     * Counter of nodes already added.
     *
     * @type {number}
     */
    _defineProperty(this, "renderedNodes", 0);
    this.nodesPool = typeof nodeType === 'string' ? new NodesPool(nodeType) : null;
    this.nodeType = nodeType;
    this.rootNode = rootNode;
  }

  /**
   * Sets the table renderer instance to the current renderer.
   *
   * @param {TableRenderer} table The TableRenderer instance.
   */
  setTable(table) {
    if (this.nodesPool) {
      this.nodesPool.setRootDocument(table.rootDocument);
    }
    this.table = table;
  }

  /**
   * Adjusts the number of rendered nodes.
   */
  adjust() {}

  /**
   * Renders the contents to the elements.
   */
  render() {}
}