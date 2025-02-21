import "core-js/modules/es.error.cause.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { mixin, createObjectPropListener } from "../helpers/object.mjs";
import localHooks from "./../mixins/localHooks.mjs";
/**
 * The Transformation class implements algorithms for transforming coordinates based on current settings
 * passed to the Handsontable. The class performs the calculations based on the renderable indexes.
 *
 * Transformation is always applied relative to the current selection.
 *
 * The class operates on a table size defined by the renderable indexes. If the `navigableHeaders`
 * option is enabled, the table size is increased by the number of row and/or column headers.
 * Because the headers are treated as cells as part of the table size (indexes always go from 0 to N),
 * the algorithm can be written as simply as possible (without new if's that distinguish the headers
 * logic).
 *
 * @class Transformation
 * @util
 */
var _range = /*#__PURE__*/new WeakMap();
var _options = /*#__PURE__*/new WeakMap();
var _offset = /*#__PURE__*/new WeakMap();
var _Transformation_brand = /*#__PURE__*/new WeakSet();
class Transformation {
  constructor(range, options) {
    /**
     * Clamps the coords to make sure they points to the cell (or header) in the table range.
     *
     * @param {CellCoords} zeroBasedCoords The coords object to clamp.
     * @returns {{rowDir: 1|0|-1, colDir: 1|0|-1}}
     */
    _classPrivateMethodInitSpec(this, _Transformation_brand);
    /**
     * Instance of the SelectionRange, holder for visual coordinates applied to the table.
     *
     * @type {SelectionRange}
     */
    _classPrivateFieldInitSpec(this, _range, void 0);
    /**
     * Additional options which define the state of the settings which can infer transformation and
     * give the possibility to translate indexes.
     *
     * @type {object}
     */
    _classPrivateFieldInitSpec(this, _options, void 0);
    /**
     * Increases the table size by applying the offsets. The option is used by the `navigableHeaders`
     * option.
     *
     * @type {{ x: number, y: number }}
     */
    _classPrivateFieldInitSpec(this, _offset, {
      x: 0,
      y: 0
    });
    _classPrivateFieldSet(_range, this, range);
    _classPrivateFieldSet(_options, this, options);
  }

  /**
   * Selects cell relative to the current cell (if possible).
   *
   * @param {number} rowDelta Rows number to move, value can be passed as negative number.
   * @param {number} colDelta Columns number to move, value can be passed as negative number.
   * @param {boolean} [createMissingRecords=false] If `true` the new rows/columns will be created if necessary. Otherwise, row/column will
   *                        be created according to `minSpareRows/minSpareCols` settings of Handsontable.
   * @returns {CellCoords} Visual coordinates after transformation.
   */
  transformStart(rowDelta, colDelta) {
    let createMissingRecords = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : false;
    const delta = _classPrivateFieldGet(_options, this).createCellCoords(rowDelta, colDelta);
    let visualCoords = _classPrivateFieldGet(_range, this).current().highlight;
    const highlightRenderableCoords = _classPrivateFieldGet(_options, this).visualToRenderableCoords(visualCoords);
    let rowTransformDir = 0;
    let colTransformDir = 0;
    this.runLocalHooks('beforeTransformStart', delta);
    if (highlightRenderableCoords.row !== null && highlightRenderableCoords.col !== null) {
      const {
        width,
        height
      } = _assertClassBrand(_Transformation_brand, this, _getTableSize).call(this);
      const {
        row,
        col
      } = _assertClassBrand(_Transformation_brand, this, _visualToZeroBasedCoords).call(this, visualCoords);
      const fixedRowsBottom = _classPrivateFieldGet(_options, this).fixedRowsBottom();
      const minSpareRows = _classPrivateFieldGet(_options, this).minSpareRows();
      const minSpareCols = _classPrivateFieldGet(_options, this).minSpareCols();
      const autoWrapRow = _classPrivateFieldGet(_options, this).autoWrapRow();
      const autoWrapCol = _classPrivateFieldGet(_options, this).autoWrapCol();
      const zeroBasedCoords = _classPrivateFieldGet(_options, this).createCellCoords(row + delta.row, col + delta.col);
      if (zeroBasedCoords.row >= height) {
        const isActionInterrupted = createObjectPropListener(createMissingRecords && minSpareRows > 0 && fixedRowsBottom === 0);
        const nextColumn = zeroBasedCoords.col + 1;
        const newCoords = _classPrivateFieldGet(_options, this).createCellCoords(zeroBasedCoords.row - height, nextColumn >= width ? nextColumn - width : nextColumn);
        this.runLocalHooks('beforeColumnWrap', isActionInterrupted, _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, newCoords), nextColumn >= width);
        if (isActionInterrupted.value) {
          this.runLocalHooks('insertRowRequire', _classPrivateFieldGet(_options, this).countRenderableRows());
        } else if (autoWrapCol) {
          zeroBasedCoords.assign(newCoords);
        }
      } else if (zeroBasedCoords.row < 0) {
        const isActionInterrupted = createObjectPropListener(autoWrapCol);
        const previousColumn = zeroBasedCoords.col - 1;
        const newCoords = _classPrivateFieldGet(_options, this).createCellCoords(height + zeroBasedCoords.row, previousColumn < 0 ? width + previousColumn : previousColumn);
        this.runLocalHooks('beforeColumnWrap', isActionInterrupted, _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, newCoords), previousColumn < 0);
        if (autoWrapCol) {
          zeroBasedCoords.assign(newCoords);
        }
      }
      if (zeroBasedCoords.col >= width) {
        const isActionInterrupted = createObjectPropListener(createMissingRecords && minSpareCols > 0);
        const nextRow = zeroBasedCoords.row + 1;
        const newCoords = _classPrivateFieldGet(_options, this).createCellCoords(nextRow >= height ? nextRow - height : nextRow, zeroBasedCoords.col - width);
        this.runLocalHooks('beforeRowWrap', isActionInterrupted, _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, newCoords), nextRow >= height);
        if (isActionInterrupted.value) {
          this.runLocalHooks('insertColRequire', _classPrivateFieldGet(_options, this).countRenderableColumns());
        } else if (autoWrapRow) {
          zeroBasedCoords.assign(newCoords);
        }
      } else if (zeroBasedCoords.col < 0) {
        const isActionInterrupted = createObjectPropListener(autoWrapRow);
        const previousRow = zeroBasedCoords.row - 1;
        const newCoords = _classPrivateFieldGet(_options, this).createCellCoords(previousRow < 0 ? height + previousRow : previousRow, width + zeroBasedCoords.col);
        this.runLocalHooks('beforeRowWrap', isActionInterrupted, _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, newCoords), previousRow < 0);
        if (autoWrapRow) {
          zeroBasedCoords.assign(newCoords);
        }
      }
      const {
        rowDir,
        colDir
      } = _assertClassBrand(_Transformation_brand, this, _clampCoords).call(this, zeroBasedCoords);
      rowTransformDir = rowDir;
      colTransformDir = colDir;
      visualCoords = _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, zeroBasedCoords);
    }
    this.runLocalHooks('afterTransformStart', visualCoords, rowTransformDir, colTransformDir);
    return visualCoords;
  }

  /**
   * Sets selection end cell relative to the current selection end cell (if possible).
   *
   * @param {number} rowDelta Rows number to move, value can be passed as negative number.
   * @param {number} colDelta Columns number to move, value can be passed as negative number.
   * @returns {CellCoords} Visual coordinates after transformation.
   */
  transformEnd(rowDelta, colDelta) {
    const delta = _classPrivateFieldGet(_options, this).createCellCoords(rowDelta, colDelta);
    const cellRange = _classPrivateFieldGet(_range, this).current();
    const highlightRenderableCoords = _classPrivateFieldGet(_options, this).visualToRenderableCoords(cellRange.highlight);
    const toRow = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedRow).call(this, cellRange.to.row, cellRange.from.row);
    const toColumn = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedColumn).call(this, cellRange.to.col, cellRange.from.col);
    const visualCoords = cellRange.to.clone();
    let rowTransformDir = 0;
    let colTransformDir = 0;
    this.runLocalHooks('beforeTransformEnd', delta);
    if (highlightRenderableCoords.row !== null && highlightRenderableCoords.col !== null && toRow !== null && toColumn !== null) {
      const {
        row: highlightRow,
        col: highlightColumn
      } = _assertClassBrand(_Transformation_brand, this, _visualToZeroBasedCoords).call(this, cellRange.highlight);
      const coords = _classPrivateFieldGet(_options, this).createCellCoords(toRow + delta.row, toColumn + delta.col);
      const topStartCorner = cellRange.getTopStartCorner();
      const topEndCorner = cellRange.getTopEndCorner();
      const bottomEndCorner = cellRange.getBottomEndCorner();
      if (delta.col < 0 && toColumn >= highlightColumn && coords.col < highlightColumn) {
        const columnRestDelta = coords.col - highlightColumn;
        coords.col = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedColumn).call(this, topStartCorner.col, topEndCorner.col) + columnRestDelta;
      } else if (delta.col > 0 && toColumn <= highlightColumn && coords.col > highlightColumn) {
        const endColumnIndex = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedColumn).call(this, topEndCorner.col, topStartCorner.col);
        const columnRestDelta = Math.max(coords.col - endColumnIndex, 1);
        coords.col = endColumnIndex + columnRestDelta;
      }
      if (delta.row < 0 && toRow >= highlightRow && coords.row < highlightRow) {
        const rowRestDelta = coords.row - highlightRow;
        coords.row = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedRow).call(this, topStartCorner.row, bottomEndCorner.row) + rowRestDelta;
      } else if (delta.row > 0 && toRow <= highlightRow && coords.row > highlightRow) {
        const bottomRowIndex = _assertClassBrand(_Transformation_brand, this, _findFirstNonHiddenZeroBasedRow).call(this, bottomEndCorner.row, topStartCorner.row);
        const rowRestDelta = Math.max(coords.row - bottomRowIndex, 1);
        coords.row = bottomRowIndex + rowRestDelta;
      }
      const {
        rowDir,
        colDir
      } = _assertClassBrand(_Transformation_brand, this, _clampCoords).call(this, coords);
      rowTransformDir = rowDir;
      colTransformDir = colDir;
      const newVisualCoords = _assertClassBrand(_Transformation_brand, this, _zeroBasedToVisualCoords).call(this, coords);
      if (delta.row === 0 && delta.col !== 0) {
        visualCoords.col = newVisualCoords.col;
      } else if (delta.row !== 0 && delta.col === 0) {
        visualCoords.row = newVisualCoords.row;
      } else {
        visualCoords.row = newVisualCoords.row;
        visualCoords.col = newVisualCoords.col;
      }
    }
    this.runLocalHooks('afterTransformEnd', visualCoords, rowTransformDir, colTransformDir);
    return visualCoords;
  }

  /**
   * Sets the additional offset in table size that may occur when the `navigableHeaders` option
   * is enabled.
   *
   * @param {{x: number, y: number}} offset Offset as x and y properties.
   */
  setOffsetSize(_ref) {
    let {
      x,
      y
    } = _ref;
    _classPrivateFieldSet(_offset, this, {
      x,
      y
    });
  }

  /**
   * Resets the offset size to the default values.
   */
  resetOffsetSize() {
    _classPrivateFieldSet(_offset, this, {
      x: 0,
      y: 0
    });
  }
}
function _clampCoords(zeroBasedCoords) {
  const {
    width,
    height
  } = _assertClassBrand(_Transformation_brand, this, _getTableSize).call(this);
  let rowDir = 0;
  let colDir = 0;
  if (zeroBasedCoords.row < 0) {
    rowDir = -1;
    zeroBasedCoords.row = 0;
  } else if (zeroBasedCoords.row > 0 && zeroBasedCoords.row >= height) {
    rowDir = 1;
    zeroBasedCoords.row = height - 1;
  }
  if (zeroBasedCoords.col < 0) {
    colDir = -1;
    zeroBasedCoords.col = 0;
  } else if (zeroBasedCoords.col > 0 && zeroBasedCoords.col >= width) {
    colDir = 1;
    zeroBasedCoords.col = width - 1;
  }
  return {
    rowDir,
    colDir
  };
}
/**
 * Gets the table size in number of rows with headers as "height" and number of columns with
 * headers as "width".
 *
 * @returns {{width: number, height: number}}
 */
function _getTableSize() {
  return {
    width: _classPrivateFieldGet(_offset, this).x + _classPrivateFieldGet(_options, this).countRenderableColumns(),
    height: _classPrivateFieldGet(_offset, this).y + _classPrivateFieldGet(_options, this).countRenderableRows()
  };
}
/**
 * Finds the first non-hidden zero-based row in the table range.
 *
 * @param {number} visualRowFrom The visual row from which the search should start.
 * @param {number} visualRowTo The visual row to which the search should end.
 * @returns {number | null}
 */
function _findFirstNonHiddenZeroBasedRow(visualRowFrom, visualRowTo) {
  const row = _classPrivateFieldGet(_options, this).findFirstNonHiddenRenderableRow(visualRowFrom, visualRowTo);
  if (row === null) {
    return null;
  }
  return _classPrivateFieldGet(_offset, this).y + row;
}
/**
 * Finds the first non-hidden zero-based column in the table range.
 *
 * @param {number} visualColumnFrom The visual column from which the search should start.
 * @param {number} visualColumnTo The visual column to which the search should end.
 * @returns {number | null}
 */
function _findFirstNonHiddenZeroBasedColumn(visualColumnFrom, visualColumnTo) {
  const column = _classPrivateFieldGet(_options, this).findFirstNonHiddenRenderableColumn(visualColumnFrom, visualColumnTo);
  if (column === null) {
    return null;
  }
  return _classPrivateFieldGet(_offset, this).x + column;
}
/**
 * Translates the visual coordinates to zero-based ones.
 *
 * @param {CellCoords} visualCoords The visual coords to process.
 * @returns {CellCoords}
 */
function _visualToZeroBasedCoords(visualCoords) {
  const {
    row,
    col
  } = _classPrivateFieldGet(_options, this).visualToRenderableCoords(visualCoords);
  if (row === null || col === null) {
    throw new Error('Renderable coords are not visible.');
  }
  return _classPrivateFieldGet(_options, this).createCellCoords(_classPrivateFieldGet(_offset, this).y + row, _classPrivateFieldGet(_offset, this).x + col);
}
/**
 * Translates the zero-based coordinates to visual ones.
 *
 * @param {CellCoords} zeroBasedCoords The coordinates to process.
 * @returns {CellCoords}
 */
function _zeroBasedToVisualCoords(zeroBasedCoords) {
  const coords = zeroBasedCoords.clone();
  coords.col = zeroBasedCoords.col - _classPrivateFieldGet(_offset, this).x;
  coords.row = zeroBasedCoords.row - _classPrivateFieldGet(_offset, this).y;
  return _classPrivateFieldGet(_options, this).renderableToVisualCoords(coords);
}
mixin(Transformation, localHooks);
export default Transformation;