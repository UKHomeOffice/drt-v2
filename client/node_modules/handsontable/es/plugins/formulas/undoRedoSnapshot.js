function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { arrayEach } from '../../helpers/array';
import Stack from '../../utils/dataStructures/stack';
import CellValue from './cell/value';
/**
 * This components is a simple workaround to make Undo/Redo functionality work.
 *
 * @class UndoRedoSnapshot
 * @util
 */

var UndoRedoSnapshot =
/*#__PURE__*/
function () {
  function UndoRedoSnapshot(sheet) {
    _classCallCheck(this, UndoRedoSnapshot);

    /**
     * Instance of {@link Sheet}.
     *
     * @type {Sheet}
     */
    this.sheet = sheet;
    /**
     * Stack instance for collecting undo/redo changes.
     *
     * @type {Stack}
     */

    this.stack = new Stack();
  }
  /**
   * Save snapshot for specified action.
   *
   * @param {String} axis Alter action which triggers snapshot.
   * @param {Number} index Alter operation stared at.
   * @param {Number} amount Amount of items to operate.
   */


  _createClass(UndoRedoSnapshot, [{
    key: "save",
    value: function save(axis, index, amount) {
      var _this$sheet = this.sheet,
          matrix = _this$sheet.matrix,
          dataProvider = _this$sheet.dataProvider;
      var changes = [];
      arrayEach(matrix.data, function (cellValue) {
        var row = cellValue.row,
            column = cellValue.column;

        if (cellValue[axis] < index || cellValue[axis] > index + (amount - 1)) {
          var value = dataProvider.getSourceDataAtCell(row, column);
          changes.push({
            row: row,
            column: column,
            value: value
          });
        }
      });
      this.stack.push({
        axis: axis,
        index: index,
        amount: amount,
        changes: changes
      });
    }
    /**
     * Restore state to the previous one.
     */

  }, {
    key: "restore",
    value: function restore() {
      var _this$sheet2 = this.sheet,
          matrix = _this$sheet2.matrix,
          dataProvider = _this$sheet2.dataProvider;

      var _this$stack$pop = this.stack.pop(),
          axis = _this$stack$pop.axis,
          index = _this$stack$pop.index,
          amount = _this$stack$pop.amount,
          changes = _this$stack$pop.changes;

      if (changes) {
        arrayEach(changes, function (change) {
          if (change[axis] > index + (amount - 1)) {
            change[axis] -= amount;
          }

          var row = change.row,
              column = change.column,
              value = change.value;
          var rawValue = dataProvider.getSourceDataAtCell(row, column);

          if (rawValue !== value) {
            dataProvider.updateSourceData(row, column, value);
            matrix.getCellAt(row, column).setState(CellValue.STATE_OUT_OFF_DATE);
          }
        });
      }
    }
    /**
     * Destroy class.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.sheet = null;
      this.stack = null;
    }
  }]);

  return UndoRedoSnapshot;
}();

export default UndoRedoSnapshot;