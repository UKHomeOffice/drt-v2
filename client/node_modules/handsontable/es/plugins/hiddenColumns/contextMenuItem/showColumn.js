import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

import { rangeEach } from '../../../helpers/number';
import * as C from '../../../i18n/constants';
export default function showColumnItem(hiddenColumnsPlugin) {
  var beforeHiddenColumns = [];
  var afterHiddenColumns = [];
  return {
    key: 'hidden_columns_show',
    name: function name() {
      var selection = this.getSelectedLast();
      var pluralForm = 0;

      if (Array.isArray(selection)) {
        var _selection = _slicedToArray(selection, 4),
            fromColumn = _selection[1],
            toColumn = _selection[3];

        if (fromColumn > toColumn) {
          var _ref = [toColumn, fromColumn];
          fromColumn = _ref[0];
          toColumn = _ref[1];
        }

        var hiddenColumns = 0;

        if (fromColumn === toColumn) {
          hiddenColumns = beforeHiddenColumns.length + afterHiddenColumns.length;
        } else {
          rangeEach(fromColumn, toColumn, function (column) {
            if (hiddenColumnsPlugin.isHidden(column)) {
              hiddenColumns += 1;
            }
          });
        }

        pluralForm = hiddenColumns <= 1 ? 0 : 1;
      }

      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_SHOW_COLUMN, pluralForm);
    },
    callback: function callback() {
      var _this$getSelectedRang = this.getSelectedRangeLast(),
          from = _this$getSelectedRang.from,
          to = _this$getSelectedRang.to;

      var start = Math.min(from.col, to.col);
      var end = Math.max(from.col, to.col);

      if (start === end) {
        if (beforeHiddenColumns.length === start) {
          hiddenColumnsPlugin.showColumns(beforeHiddenColumns);
          beforeHiddenColumns.length = 0;
        }

        if (afterHiddenColumns.length === this.countSourceCols() - (start + 1)) {
          hiddenColumnsPlugin.showColumns(afterHiddenColumns);
          afterHiddenColumns.length = 0;
        }
      } else {
        rangeEach(start, end, function (column) {
          return hiddenColumnsPlugin.showColumn(column);
        });
      }

      this.render();
      this.view.wt.wtOverlays.adjustElementsSize(true);
    },
    disabled: false,
    hidden: function hidden() {
      if (!hiddenColumnsPlugin.hiddenColumns.length || !this.selection.isSelectedByColumnHeader()) {
        return true;
      }

      beforeHiddenColumns.length = 0;
      afterHiddenColumns.length = 0;

      var _this$getSelectedRang2 = this.getSelectedRangeLast(),
          from = _this$getSelectedRang2.from,
          to = _this$getSelectedRang2.to;

      var start = Math.min(from.col, to.col);
      var end = Math.max(from.col, to.col);
      var hiddenInSelection = false;

      if (start === end) {
        var totalColumnLength = this.countSourceCols();
        rangeEach(0, totalColumnLength, function (column) {
          var partedHiddenLength = beforeHiddenColumns.length + afterHiddenColumns.length;

          if (partedHiddenLength === hiddenColumnsPlugin.hiddenColumns.length) {
            return false;
          }

          if (column < start && hiddenColumnsPlugin.isHidden(column)) {
            beforeHiddenColumns.push(column);
          } else if (hiddenColumnsPlugin.isHidden(column)) {
            afterHiddenColumns.push(column);
          }
        });
        totalColumnLength -= 1;

        if (beforeHiddenColumns.length === start && start > 0 || afterHiddenColumns.length === totalColumnLength - start && start < totalColumnLength) {
          hiddenInSelection = true;
        }
      } else {
        rangeEach(start, end, function (column) {
          if (hiddenColumnsPlugin.isHidden(column)) {
            hiddenInSelection = true;
            return false;
          }
        });
      }

      return !hiddenInSelection;
    }
  };
}