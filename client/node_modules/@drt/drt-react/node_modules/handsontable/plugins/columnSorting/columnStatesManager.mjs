import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.map.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { isObject, objectEach } from "../../helpers/object.mjs";
import { LinkedPhysicalIndexToValueMap as IndexToValueMap } from "../../translations/index.mjs";
import { isDefined } from "../../helpers/mixed.mjs";
const inheritedColumnProperties = ['sortEmptyCells', 'indicator', 'headerAction', 'compareFunctionFactory'];
const SORT_EMPTY_CELLS_DEFAULT = false;
const SHOW_SORT_INDICATOR_DEFAULT = true;
const HEADER_ACTION_DEFAULT = true;

/**
 * Store and manages states of sorted columns.
 *
 * @private
 * @class ColumnStatesManager
 */
export class ColumnStatesManager {
  constructor(hot, mapName) {
    /**
     * Handsontable instance.
     *
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * Index map storing sorting states for every column. ColumnStatesManager write and read to/from this element.
     *
     * @type {LinkedPhysicalIndexToValueMap}
     */
    _defineProperty(this, "sortingStates", new IndexToValueMap());
    /**
     * Determines whether we should sort empty cells.
     *
     * @type {boolean}
     */
    _defineProperty(this, "sortEmptyCells", SORT_EMPTY_CELLS_DEFAULT);
    /**
     * Determines whether indicator should be visible (for sorted columns).
     *
     * @type {boolean}
     */
    _defineProperty(this, "indicator", SHOW_SORT_INDICATOR_DEFAULT);
    /**
     * Determines whether click on the header perform sorting.
     *
     * @type {boolean}
     */
    _defineProperty(this, "headerAction", HEADER_ACTION_DEFAULT);
    /**
     * Determines compare function factory. Method get as parameters `sortOder` and `columnMeta` and return compare function.
     */
    _defineProperty(this, "compareFunctionFactory", void 0);
    /**
     * Name of map storing sorting states. Required for unique name (PR #7440 introduced it). It's needed as
     * both ColumnSorting and MultiColumnSorting plugins create state manager and as a consequence register maps.
     * Objects are destroyed in strange order as the updateSettings doesn't work well.
     */
    _defineProperty(this, "mapName", void 0);
    this.hot = hot;
    this.mapName = mapName;
    this.hot.columnIndexMapper.registerMap(mapName, this.sortingStates);
  }

  /**
   * Update column properties which affect the sorting result.
   *
   * **Note**: All column properties can be overwritten by {@link Options#columns} option.
   *
   * @param {object} allSortSettings Column sorting plugin's configuration object.
   */
  updateAllColumnsProperties(allSortSettings) {
    if (!isObject(allSortSettings)) {
      return;
    }
    objectEach(allSortSettings, (newValue, propertyName) => {
      if (inheritedColumnProperties.includes(propertyName)) {
        this[propertyName] = newValue;
      }
    });
  }

  /**
   * Get all column properties which affect the sorting result.
   *
   * @returns {object}
   */
  getAllColumnsProperties() {
    const columnProperties = {
      sortEmptyCells: this.sortEmptyCells,
      indicator: this.indicator,
      headerAction: this.headerAction
    };
    if (typeof this.compareFunctionFactory === 'function') {
      columnProperties.compareFunctionFactory = this.compareFunctionFactory;
    }
    return columnProperties;
  }

  /**
   * Get sort order of column.
   *
   * @param {number} searchedColumn Visual column index.
   * @returns {string|undefined} Sort order (`asc` for ascending, `desc` for descending and undefined for not sorted).
   */
  getSortOrderOfColumn(searchedColumn) {
    var _this$sortingStates$g;
    return (_this$sortingStates$g = this.sortingStates.getValueAtIndex(this.hot.toPhysicalColumn(searchedColumn))) === null || _this$sortingStates$g === void 0 ? void 0 : _this$sortingStates$g.sortOrder;
  }

  /**
   * Get order of particular column in the states queue.
   *
   * @param {number} column Visual column index.
   * @returns {number}
   */
  getIndexOfColumnInSortQueue(column) {
    column = this.hot.toPhysicalColumn(column);
    return this.sortingStates.getEntries().findIndex(_ref => {
      let [physicalColumn] = _ref;
      return physicalColumn === column;
    });
  }

  /**
   * Get number of sorted columns.
   *
   * @returns {number}
   */
  getNumberOfSortedColumns() {
    return this.sortingStates.getLength();
  }

  /**
   * Get if list of sorted columns is empty.
   *
   * @returns {boolean}
   */
  isListOfSortedColumnsEmpty() {
    return this.getNumberOfSortedColumns() === 0;
  }

  /**
   * Get if particular column is sorted.
   *
   * @param {number} column Visual column index.
   * @returns {boolean}
   */
  isColumnSorted(column) {
    return isObject(this.sortingStates.getValueAtIndex(this.hot.toPhysicalColumn(column)));
  }

  /**
   * Queue of sort states containing sorted columns and their orders (Array of objects containing `column` and `sortOrder` properties).
   *
   * **Note**: Please keep in mind that returned objects expose **visual** column index under the `column` key.
   *
   * @returns {Array<object>}
   */
  getSortStates() {
    if (this.sortingStates === null) {
      return [];
    }
    const sortingStatesQueue = this.sortingStates.getEntries();
    return sortingStatesQueue.map(_ref2 => {
      let [physicalColumn, value] = _ref2;
      return {
        column: this.hot.toVisualColumn(physicalColumn),
        ...value
      };
    });
  }

  /**
   * Get sort state for particular column. Object contains `column` and `sortOrder` properties.
   *
   * **Note**: Please keep in mind that returned objects expose **visual** column index under the `column` key.
   *
   * @param {number} column Visual column index.
   * @returns {object|undefined}
   */
  getColumnSortState(column) {
    const sortOrder = this.getSortOrderOfColumn(column);
    if (isDefined(sortOrder)) {
      return {
        column,
        sortOrder
      };
    }
  }

  /**
   * Set all column states.
   *
   * @param {Array} sortStates Sort states.
   */
  setSortStates(sortStates) {
    this.sortingStates.clear();
    for (let i = 0; i < sortStates.length; i += 1) {
      this.sortingStates.setValueAtIndex(this.hot.toPhysicalColumn(sortStates[i].column), {
        sortOrder: sortStates[i].sortOrder
      });
    }
  }

  /**
   * Destroy the state manager.
   */
  destroy() {
    this.hot.columnIndexMapper.unregisterMap(this.mapName);
    this.sortingStates = null;
  }
}