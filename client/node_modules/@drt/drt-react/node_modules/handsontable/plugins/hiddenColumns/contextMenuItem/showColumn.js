"use strict";

exports.__esModule = true;
exports.default = showColumnItem;
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
var _array = require("../../../helpers/array");
var C = _interopRequireWildcard(require("../../../i18n/constants"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
/**
 * @param {HiddenColumns} hiddenColumnsPlugin The plugin instance.
 * @returns {object}
 */
function showColumnItem(hiddenColumnsPlugin) {
  const columns = [];
  return {
    key: 'hidden_columns_show',
    name() {
      const pluralForm = columns.length > 1 ? 1 : 0;
      return this.getTranslatedPhrase(C.CONTEXTMENU_ITEMS_SHOW_COLUMN, pluralForm);
    },
    callback() {
      var _this$columnIndexMapp, _this$columnIndexMapp2;
      if (columns.length === 0) {
        return;
      }
      let startVisualColumn = columns[0];
      let endVisualColumn = columns[columns.length - 1];

      // Add to the selection one more visual column on the left.
      startVisualColumn = (_this$columnIndexMapp = this.columnIndexMapper.getNearestNotHiddenIndex(startVisualColumn - 1, -1)) !== null && _this$columnIndexMapp !== void 0 ? _this$columnIndexMapp : 0;
      // Add to the selection one more visual column on the right.
      endVisualColumn = (_this$columnIndexMapp2 = this.columnIndexMapper.getNearestNotHiddenIndex(endVisualColumn + 1, 1)) !== null && _this$columnIndexMapp2 !== void 0 ? _this$columnIndexMapp2 : this.countCols() - 1;
      hiddenColumnsPlugin.showColumns(columns);

      // We render columns at first. It was needed for getting fixed columns.
      // Please take a look at #6864 for broader description.
      this.render();
      this.view.adjustElementsSize();
      const allColumnsSelected = endVisualColumn - startVisualColumn + 1 === this.countCols();

      // When all headers needs to be selected then do nothing. The header selection is
      // automatically handled by corner click.
      if (!allColumnsSelected) {
        this.selectColumns(startVisualColumn, endVisualColumn);
      }
    },
    disabled: false,
    hidden() {
      const hiddenPhysicalColumns = (0, _array.arrayMap)(hiddenColumnsPlugin.getHiddenColumns(), visualColumnIndex => {
        return this.toPhysicalColumn(visualColumnIndex);
      });
      if (!(this.selection.isSelectedByColumnHeader() || this.selection.isSelectedByCorner()) || hiddenPhysicalColumns.length < 1) {
        return true;
      }
      columns.length = 0;
      const selectedRangeLast = this.getSelectedRangeLast();
      const visualStartColumn = selectedRangeLast.getTopStartCorner().col;
      const visualEndColumn = selectedRangeLast.getBottomEndCorner().col;
      const columnIndexMapper = this.columnIndexMapper;
      const renderableStartColumn = columnIndexMapper.getRenderableFromVisualIndex(visualStartColumn);
      const renderableEndColumn = columnIndexMapper.getRenderableFromVisualIndex(visualEndColumn);
      const notTrimmedColumnIndexes = columnIndexMapper.getNotTrimmedIndexes();
      const physicalColumnIndexes = [];
      if (visualStartColumn !== visualEndColumn) {
        const visualColumnsInRange = visualEndColumn - visualStartColumn + 1;
        const renderedColumnsInRange = renderableEndColumn - renderableStartColumn + 1;

        // Collect not trimmed columns if there are some hidden columns in the selection range.
        if (visualColumnsInRange > renderedColumnsInRange) {
          const physicalIndexesInRange = notTrimmedColumnIndexes.slice(visualStartColumn, visualEndColumn + 1);
          physicalColumnIndexes.push(...physicalIndexesInRange.filter(physicalIndex => hiddenPhysicalColumns.includes(physicalIndex)));
        }

        // Handled column is the first rendered index and there are some visual indexes before it.
      } else if (renderableStartColumn === 0 && renderableStartColumn < visualStartColumn) {
        // not trimmed indexes -> array of mappings from visual (native array's index) to physical indexes (value).
        physicalColumnIndexes.push(...notTrimmedColumnIndexes.slice(0, visualStartColumn)); // physical indexes

        // When all columns are hidden and the context menu is triggered using top-left corner.
      } else if (renderableStartColumn === null) {
        // Show all hidden columns.
        physicalColumnIndexes.push(...notTrimmedColumnIndexes.slice(0, this.countCols()));
      } else {
        const lastVisualIndex = this.countCols() - 1;
        const lastRenderableIndex = columnIndexMapper.getRenderableFromVisualIndex(columnIndexMapper.getNearestNotHiddenIndex(lastVisualIndex, -1));

        // Handled column is the last rendered index and there are some visual indexes after it.
        if (renderableEndColumn === lastRenderableIndex && lastVisualIndex > visualEndColumn) {
          physicalColumnIndexes.push(...notTrimmedColumnIndexes.slice(visualEndColumn + 1));
        }
      }
      (0, _array.arrayEach)(physicalColumnIndexes, physicalColumnIndex => {
        columns.push(this.toVisualColumn(physicalColumnIndex));
      });
      return columns.length === 0;
    }
  };
}