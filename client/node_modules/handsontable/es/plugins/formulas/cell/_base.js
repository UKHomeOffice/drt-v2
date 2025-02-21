function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { toLabel } from 'hot-formula-parser';
import { isObject } from '../../../helpers/object';
/**
 * @class BaseCell
 * @util
 */

var BaseCell =
/*#__PURE__*/
function () {
  function BaseCell(row, column) {
    _classCallCheck(this, BaseCell);

    var rowObject = isObject(row);
    var columnObject = isObject(column);
    this._row = rowObject ? row.index : row;
    this.rowAbsolute = rowObject ? row.isAbsolute : true;
    this._column = columnObject ? column.index : column;
    this.columnAbsolute = columnObject ? column.isAbsolute : true;
    this.rowOffset = 0;
    this.columnOffset = 0; // TODO: Change syntax to es6 after upgrade tests to newer version of phantom and jasmine.

    Object.defineProperty(this, 'row', {
      get: function get() {
        return this.rowOffset + this._row;
      },
      set: function set(rowIndex) {
        this._row = rowIndex;
      },
      enumerable: true,
      configurable: true
    });
    Object.defineProperty(this, 'column', {
      get: function get() {
        return this.columnOffset + this._column;
      },
      set: function set(columnIndex) {
        this._column = columnIndex;
      },
      enumerable: true,
      configurable: true
    });
  }
  /**
   * Translate cell coordinates.
   *
   * @param {Number} rowOffset Row offset to move.
   * @param {Number} columnOffset Column offset to move.
   */


  _createClass(BaseCell, [{
    key: "translateTo",
    value: function translateTo(rowOffset, columnOffset) {
      this.row = this.row + rowOffset;
      this.column = this.column + columnOffset;
    }
    /**
     * Check if cell is equal to provided one.
     *
     * @param {BaseCell} cell Cell object.
     * @returns {Boolean}
     */

  }, {
    key: "isEqual",
    value: function isEqual(cell) {
      return cell.row === this.row && cell.column === this.column;
    }
    /**
     * Stringify object.
     *
     * @returns {String}
     */

  }, {
    key: "toString",
    value: function toString() {
      return toLabel({
        index: this.row,
        isAbsolute: this.rowAbsolute
      }, {
        index: this.column,
        isAbsolute: this.columnAbsolute
      });
    }
  }]);

  return BaseCell;
}();

export default BaseCell;