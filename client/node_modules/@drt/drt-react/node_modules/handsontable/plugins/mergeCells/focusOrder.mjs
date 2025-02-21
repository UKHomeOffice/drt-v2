import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import LinkedList from "../../utils/dataStructures/linkedList.mjs";
/**
 * Class responsible for providing the correct focus order (vertical and horizontal) within a selection that
 * contains merged cells.
 *
 * @private
 */
var _cellsHorizontalOrder = /*#__PURE__*/new WeakMap();
var _cellsVerticalOrder = /*#__PURE__*/new WeakMap();
var _currentHorizontalLinkedNode = /*#__PURE__*/new WeakMap();
var _currentVerticalLinkedNode = /*#__PURE__*/new WeakMap();
var _mergedCellsGetter = /*#__PURE__*/new WeakMap();
var _rowIndexMapper = /*#__PURE__*/new WeakMap();
var _columnIndexMapper = /*#__PURE__*/new WeakMap();
var _FocusOrder_brand = /*#__PURE__*/new WeakSet();
export class FocusOrder {
  constructor(_ref) {
    let {
      mergedCellsGetter,
      rowIndexMapper,
      columnIndexMapper
    } = _ref;
    /**
     * Pushes a new node to the provided list order.
     *
     * @param {CellRange} selectedRange The selected range to build the focus order for.
     * @param {LinkedList} listOrder The list order to push the node to.
     * @param {WeakSet} mergeCellsVisitor The set of visited cells.
     * @param {number} row The visual row index.
     * @param {number} column The visual column index.
     * @returns {NodeStructure | null}
     */
    _classPrivateMethodInitSpec(this, _FocusOrder_brand);
    /**
     * The linked list of the all cells within the current selection in horizontal order. The list is
     * recreated every time the selection is changed.
     *
     * @type {LinkedList}
     */
    _classPrivateFieldInitSpec(this, _cellsHorizontalOrder, new LinkedList());
    /**
     * The linked list of the all cells within the current selection in horizontal order. The list is
     * recreated every time the selection is changed.
     *
     * @type {LinkedList}
     */
    _classPrivateFieldInitSpec(this, _cellsVerticalOrder, new LinkedList());
    /**
     * The currently highlighted cell within the horizontal linked list.
     *
     * @type {NodeStructure | null}
     */
    _classPrivateFieldInitSpec(this, _currentHorizontalLinkedNode, null);
    /**
     * The currently highlighted cell within the vertical linked list.
     *
     * @type {NodeStructure | null}
     */
    _classPrivateFieldInitSpec(this, _currentVerticalLinkedNode, null);
    /**
     * The merged cells getter function.
     *
     * @type {function(): {row: number, col: number, rowspan: number, colspan: number} | null}}
     */
    _classPrivateFieldInitSpec(this, _mergedCellsGetter, null);
    /**
     * The row index mapper.
     *
     * @type {IndexMapper}
     */
    _classPrivateFieldInitSpec(this, _rowIndexMapper, null);
    /**
     * The column index mapper.
     *
     * @type {IndexMapper}
     */
    _classPrivateFieldInitSpec(this, _columnIndexMapper, null);
    _classPrivateFieldSet(_mergedCellsGetter, this, mergedCellsGetter);
    _classPrivateFieldSet(_rowIndexMapper, this, rowIndexMapper);
    _classPrivateFieldSet(_columnIndexMapper, this, columnIndexMapper);
  }

  /**
   * Gets the currently selected node data from the vertical focus order list.
   *
   * @returns {NodeStructure}
   */
  getCurrentVerticalNode() {
    return _classPrivateFieldGet(_currentVerticalLinkedNode, this).data;
  }

  /**
   * Gets the first node data from the vertical focus order list.
   *
   * @returns {NodeStructure}
   */
  getFirstVerticalNode() {
    return _classPrivateFieldGet(_cellsVerticalOrder, this).first.data;
  }

  /**
   * Gets the next selected node data from the vertical focus order list.
   *
   * @returns {NodeStructure}
   */
  getNextVerticalNode() {
    return _classPrivateFieldGet(_currentVerticalLinkedNode, this).next.data;
  }

  /**
   * Gets the previous selected node data from the vertical focus order list.
   *
   * @returns {NodeStructure}
   */
  getPrevVerticalNode() {
    return _classPrivateFieldGet(_currentVerticalLinkedNode, this).prev.data;
  }

  /**
   * Gets the currently selected node data from the horizontal focus order list.
   *
   * @returns {NodeStructure}
   */
  getCurrentHorizontalNode() {
    return _classPrivateFieldGet(_currentHorizontalLinkedNode, this).data;
  }

  /**
   * Gets the first node data from the horizontal focus order list.
   *
   * @returns {NodeStructure}
   */
  getFirstHorizontalNode() {
    return _classPrivateFieldGet(_cellsHorizontalOrder, this).first.data;
  }

  /**
   * Gets the next selected node data from the horizontal focus order list.
   *
   * @returns {NodeStructure}
   */
  getNextHorizontalNode() {
    return _classPrivateFieldGet(_currentHorizontalLinkedNode, this).next.data;
  }

  /**
   * Gets the previous selected node data from the horizontal focus order list.
   *
   * @returns {NodeStructure}
   */
  getPrevHorizontalNode() {
    return _classPrivateFieldGet(_currentHorizontalLinkedNode, this).prev.data;
  }

  /**
   * Sets the previous node from the vertical focus order list as active.
   */
  setPrevNodeAsActive() {
    _classPrivateFieldSet(_currentVerticalLinkedNode, this, _classPrivateFieldGet(_currentVerticalLinkedNode, this).prev);
    _classPrivateFieldSet(_currentHorizontalLinkedNode, this, _classPrivateFieldGet(_currentHorizontalLinkedNode, this).prev);
  }

  /**
   * Sets the previous node from the horizontal focus order list as active.
   */
  setNextNodeAsActive() {
    _classPrivateFieldSet(_currentVerticalLinkedNode, this, _classPrivateFieldGet(_currentVerticalLinkedNode, this).next);
    _classPrivateFieldSet(_currentHorizontalLinkedNode, this, _classPrivateFieldGet(_currentHorizontalLinkedNode, this).next);
  }

  /**
   * Rebuilds the focus order list based on the provided selection.
   *
   * @param {CellRange} selectedRange The selected range to build the focus order for.
   */
  buildFocusOrder(selectedRange) {
    const topStart = selectedRange.getTopStartCorner();
    const bottomEnd = selectedRange.getBottomEndCorner();
    const visitedHorizontalCells = new WeakSet();
    _classPrivateFieldSet(_cellsHorizontalOrder, this, new LinkedList());
    for (let r = topStart.row; r <= bottomEnd.row; r++) {
      if (_classPrivateFieldGet(_rowIndexMapper, this).isHidden(r)) {
        // eslint-disable-next-line no-continue
        continue;
      }
      for (let c = topStart.col; c <= bottomEnd.col; c++) {
        if (_classPrivateFieldGet(_columnIndexMapper, this).isHidden(c)) {
          // eslint-disable-next-line no-continue
          continue;
        }
        const node = _assertClassBrand(_FocusOrder_brand, this, _pushOrderNode).call(this, selectedRange, _classPrivateFieldGet(_cellsHorizontalOrder, this), visitedHorizontalCells, r, c);
        if (node) {
          _classPrivateFieldSet(_currentHorizontalLinkedNode, this, node);
        }
      }
    }

    // create circular linked list
    if (_classPrivateFieldGet(_cellsHorizontalOrder, this).first) {
      _classPrivateFieldGet(_cellsHorizontalOrder, this).first.prev = _classPrivateFieldGet(_cellsHorizontalOrder, this).last;
      _classPrivateFieldGet(_cellsHorizontalOrder, this).last.next = _classPrivateFieldGet(_cellsHorizontalOrder, this).first;
    }
    const visitedVerticalCells = new WeakSet();
    _classPrivateFieldSet(_cellsVerticalOrder, this, new LinkedList());
    for (let c = topStart.col; c <= bottomEnd.col; c++) {
      if (_classPrivateFieldGet(_columnIndexMapper, this).isHidden(c)) {
        // eslint-disable-next-line no-continue
        continue;
      }
      for (let r = topStart.row; r <= bottomEnd.row; r++) {
        if (_classPrivateFieldGet(_rowIndexMapper, this).isHidden(r)) {
          // eslint-disable-next-line no-continue
          continue;
        }
        const node = _assertClassBrand(_FocusOrder_brand, this, _pushOrderNode).call(this, selectedRange, _classPrivateFieldGet(_cellsVerticalOrder, this), visitedVerticalCells, r, c);
        if (node) {
          _classPrivateFieldSet(_currentVerticalLinkedNode, this, node);
        }
      }
    }

    // create circular linked list
    if (_classPrivateFieldGet(_cellsVerticalOrder, this).first) {
      _classPrivateFieldGet(_cellsVerticalOrder, this).first.prev = _classPrivateFieldGet(_cellsVerticalOrder, this).last;
      _classPrivateFieldGet(_cellsVerticalOrder, this).last.next = _classPrivateFieldGet(_cellsVerticalOrder, this).first;
    }
  }
  /**
   * Sets the active node based on the provided row and column.
   *
   * @param {number} row The visual row index.
   * @param {number} column The visual column index.
   * @returns {FocusOrder}
   */
  setActiveNode(row, column) {
    _classPrivateFieldGet(_cellsHorizontalOrder, this).inorder(node => {
      const {
        rowStart,
        rowEnd,
        colStart,
        colEnd
      } = node.data;
      if (row >= rowStart && row <= rowEnd && column >= colStart && column <= colEnd) {
        _classPrivateFieldSet(_currentHorizontalLinkedNode, this, node);
        return false;
      }
    });
    _classPrivateFieldGet(_cellsVerticalOrder, this).inorder(node => {
      const {
        rowStart,
        rowEnd,
        colStart,
        colEnd
      } = node.data;
      if (row >= rowStart && row <= rowEnd && column >= colStart && column <= colEnd) {
        _classPrivateFieldSet(_currentVerticalLinkedNode, this, node);
        return false;
      }
    });
    return this;
  }
}
function _pushOrderNode(selectedRange, listOrder, mergeCellsVisitor, row, column) {
  const topStart = selectedRange.getTopStartCorner();
  const bottomEnd = selectedRange.getBottomEndCorner();
  const highlight = selectedRange.highlight.clone().normalize();
  const mergeParent = _classPrivateFieldGet(_mergedCellsGetter, this).call(this, row, column);
  if (mergeParent && mergeCellsVisitor.has(mergeParent)) {
    return null;
  }
  const node = {
    colStart: column,
    colEnd: column,
    rowStart: row,
    rowEnd: row
  };
  if (mergeParent) {
    mergeCellsVisitor.add(mergeParent);
    if (mergeParent.row < topStart.row || mergeParent.row + mergeParent.rowspan - 1 > bottomEnd.row || mergeParent.col < topStart.col || mergeParent.col + mergeParent.colspan - 1 > bottomEnd.col) {
      return null;
    }
    node.colStart = mergeParent.col;
    node.colEnd = mergeParent.col + mergeParent.colspan - 1;
    node.rowStart = mergeParent.row;
    node.rowEnd = mergeParent.row + mergeParent.rowspan - 1;
  }
  const linkedNode = listOrder.push(node);
  if (row === highlight.row && column === highlight.col || mergeParent && highlight.row >= mergeParent.row && highlight.row <= mergeParent.row + mergeParent.rowspan - 1 && highlight.col >= mergeParent.col && highlight.col <= mergeParent.col + mergeParent.colspan - 1) {
    return linkedNode;
  }
  return null;
}