import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { arrayEach } from "../../../helpers/array.mjs";
import TreeNode from "../../../utils/dataStructures/tree.mjs";
/* eslint-disable jsdoc/require-description-complete-sentence */
/**
 * @private
 * @class HeadersTree
 *
 * The header tree class keeps nested header settings in the tree
 * structure for easier node manipulation (e.q collapse or expand column).
 * That trees represent the current state of the nested headers. From the
 * trees, the matrix is generated for nested header renderers.
 *
 * The second role of the module is validation. While building the tree,
 * there is check whether the configuration contains overlapping
 * headers. If true, then the exception is thrown.
 *
 * The tree is static; it means that its column indexing never changes
 * even when a collapsing header is performed. The structure is based
 * on visual column indexes.
 *
 * For example, for that header configuration:
 *   +----+----+----+----+----+
 *   │ A1                │ A2 │
 *   +----+----+----+----+----+
 *   │ B1           │ B2 │ B3 │
 *   +----+----+----+----+----+
 *   │ C1      │ C2 │ C3 │ C4 │
 *   +----+----+----+----+----+
 *
 * The tree structures look like:
 *                (0)                      (4)           // a visual column index
 *                 │                        │
 *        .------(A1)------.              (A2)--.
 *   .--(B1)--.           (B2)--.              (B3)--.
 *  (C1)     (C2)              (C3)                 (C4)
 *
 */
/* eslint-enable jsdoc/require-description-complete-sentence */
var _rootNodes = /*#__PURE__*/new WeakMap();
var _rootsIndex = /*#__PURE__*/new WeakMap();
var _sourceSettings = /*#__PURE__*/new WeakMap();
export default class HeadersTree {
  constructor(sourceSettings) {
    /**
     * The collection of nested headers settings structured into trees. The root trees are stored
     * under the visual column index.
     *
     * @private
     * @type {Map<number, TreeNode>}
     */
    _classPrivateFieldInitSpec(this, _rootNodes, new Map());
    /**
     * A map that translates the visual column indexes that intersect the range
     * defined by the header colspan width to the root index.
     *
     * @private
     * @type {Map<number, number>}
     */
    _classPrivateFieldInitSpec(this, _rootsIndex, new Map());
    /**
     * The instance of the SourceSettings class.
     *
     * @private
     * @type {SourceSettings}
     */
    _classPrivateFieldInitSpec(this, _sourceSettings, null);
    _classPrivateFieldSet(_sourceSettings, this, sourceSettings);
  }

  /**
   * Gets an array of the all root nodes.
   *
   * @returns {TreeNode[]}
   */
  getRoots() {
    return Array.from(_classPrivateFieldGet(_rootNodes, this).values());
  }

  /**
   * Gets a root node by specified visual column index.
   *
   * @param {number} columnIndex A visual column index.
   * @returns {TreeNode|undefined}
   */
  getRootByColumn(columnIndex) {
    let node;
    if (_classPrivateFieldGet(_rootsIndex, this).has(columnIndex)) {
      node = _classPrivateFieldGet(_rootNodes, this).get(_classPrivateFieldGet(_rootsIndex, this).get(columnIndex));
    }
    return node;
  }

  /**
   * Gets a tree node by its position in the grid settings.
   *
   * @param {number} headerLevel Header level index (there is support only for positive values).
   * @param {number} columnIndex A visual column index.
   * @returns {TreeNode|undefined}
   */
  getNode(headerLevel, columnIndex) {
    const rootNode = this.getRootByColumn(columnIndex);
    if (!rootNode) {
      return;
    }

    // Normalize the visual column index to a 0-based system for a specific "box" defined
    // by root node colspan width.
    const normColumnIndex = columnIndex - _classPrivateFieldGet(_rootsIndex, this).get(columnIndex);
    let columnCursor = 0;
    let treeNode;

    // Collect all parent nodes that depend on the collapsed node.
    rootNode.walkDown(node => {
      const {
        data: {
          origColspan,
          headerLevel: nodeHeaderLevel
        }
      } = node;
      if (headerLevel === nodeHeaderLevel) {
        if (normColumnIndex >= columnCursor && normColumnIndex <= columnCursor + origColspan - 1) {
          treeNode = node;
          treeNode.data.isRoot = columnIndex === treeNode.data.columnIndex;
          return false; // Cancel tree traversing.
        }
        columnCursor += origColspan;
      }
    });
    return treeNode;
  }

  /**
   * Builds (or rebuilds if called again) root nodes indexes.
   */
  rebuildTreeIndex() {
    let columnIndex = 0;
    _classPrivateFieldGet(_rootsIndex, this).clear();
    arrayEach(_classPrivateFieldGet(_rootNodes, this), _ref => {
      let [, {
        data: {
          colspan
        }
      }] = _ref;
      // Map tree range (colspan range/width) into visual column index of the root node.
      for (let i = columnIndex; i < columnIndex + colspan; i++) {
        _classPrivateFieldGet(_rootsIndex, this).set(i, columnIndex);
      }
      columnIndex += colspan;
    });
  }

  /**
   * Builds trees based on SourceSettings class. Calling a method causes clearing the tree state built
   * from the previous call.
   */
  buildTree() {
    this.clear();
    const columnsCount = _classPrivateFieldGet(_sourceSettings, this).getColumnsCount();
    let columnIndex = 0;
    while (columnIndex < columnsCount) {
      const columnSettings = _classPrivateFieldGet(_sourceSettings, this).getHeaderSettings(0, columnIndex);
      const rootNode = new TreeNode();
      _classPrivateFieldGet(_rootNodes, this).set(columnIndex, rootNode);
      this.buildLeaves(rootNode, columnIndex, 0, columnSettings.origColspan);
      columnIndex += columnSettings.origColspan;
    }
    this.rebuildTreeIndex();
  }

  /**
   * Builds leaves for specified tree node.
   *
   * @param {TreeNode} parentNode A node to which the leaves applies.
   * @param {number} columnIndex A visual column index.
   * @param {number} headerLevel Currently processed header level.
   * @param {number} [extractionLength=1] Determines column extraction length for node children.
   */
  buildLeaves(parentNode, columnIndex, headerLevel) {
    let extractionLength = arguments.length > 3 && arguments[3] !== undefined ? arguments[3] : 1;
    const columnsSettings = _classPrivateFieldGet(_sourceSettings, this).getHeadersSettings(headerLevel, columnIndex, extractionLength);
    headerLevel += 1;
    arrayEach(columnsSettings, columnSettings => {
      const nodeData = {
        ...columnSettings,
        /**
         * The header level (tree node depth level).
         *
         * @type {number}
         */
        headerLevel: headerLevel - 1,
        /**
         * A visual column index.
         *
         * @type {number}
         */
        columnIndex
      };
      let node;
      if (headerLevel === 1) {
        // fill the root node
        parentNode.data = nodeData;
        node = parentNode;
      } else {
        node = new TreeNode(nodeData);
        parentNode.addChild(node);
      }
      if (headerLevel < _classPrivateFieldGet(_sourceSettings, this).getLayersCount()) {
        this.buildLeaves(node, columnIndex, headerLevel, columnSettings.origColspan);
      }
      columnIndex += columnSettings.origColspan;
    });
  }

  /**
   * Clears the tree to the initial state.
   */
  clear() {
    _classPrivateFieldGet(_rootNodes, this).clear();
    _classPrivateFieldGet(_rootsIndex, this).clear();
  }
}