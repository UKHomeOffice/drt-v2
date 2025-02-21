import "core-js/modules/es.array.push.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.filter.js";
import { arrayEach, arrayMap } from "../../../helpers/array.mjs";
import * as C from "../../../i18n/constants.mjs";
/**
 * @param {HiddenColumns} hiddenColumnsPlugin The plugin instance.
 * @returns {object}
 */
export default function showColumnItem(hiddenColumnsPlugin) {
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
      const hiddenPhysicalColumns = arrayMap(hiddenColumnsPlugin.getHiddenColumns(), visualColumnIndex => {
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
      arrayEach(physicalColumnIndexes, physicalColumnIndex => {
        columns.push(this.toVisualColumn(physicalColumnIndex));
      });
      return columns.length === 0;
    }
  };
}