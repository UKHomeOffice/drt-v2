import "core-js/modules/es.error.cause.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { DEFAULT_COLUMN_WIDTH } from "../../3rdparty/walkontable/src/index.mjs";
import { getScrollbarWidth } from "../../helpers/dom/element.mjs";
import { StretchAllStrategy } from "./strategies/all.mjs";
import { StretchLastStrategy } from "./strategies/last.mjs";
const STRETCH_WIDTH_MAP_NAME = 'stretchColumns';

/**
 * The class responsible for calculating the column widths based on the specified column stretching strategy.
 *
 * @private
 * @class StretchCalculator
 */
var _hot = /*#__PURE__*/new WeakMap();
var _widthsMap = /*#__PURE__*/new WeakMap();
var _stretchStrategies = /*#__PURE__*/new WeakMap();
var _activeStrategy = /*#__PURE__*/new WeakMap();
var _StretchCalculator_brand = /*#__PURE__*/new WeakSet();
export class StretchCalculator {
  constructor(hotInstance) {
    /**
     * Checks if the vertical scrollbar will appear. Based on the current data and viewport size
     * the method calculates if the vertical scrollbar will appear after the table is rendered.
     * The method is a workaround for the issue in the Walkontable that returns unstable viewport
     * size.
     *
     * @returns {boolean}
     */
    _classPrivateMethodInitSpec(this, _StretchCalculator_brand);
    /**
     * The Handsontable instance.
     *
     * @type {Core}
     */
    _classPrivateFieldInitSpec(this, _hot, void 0);
    /**
     * The map that stores the calculated column widths.
     *
     * @type {IndexToValueMap}
     */
    _classPrivateFieldInitSpec(this, _widthsMap, void 0);
    /**
     * The map that stores the available stretch strategies.
     *
     * @type {Map<string, StretchAllStrategy | StretchLastStrategy>}
     */
    _classPrivateFieldInitSpec(this, _stretchStrategies, new Map([['all', new StretchAllStrategy(_assertClassBrand(_StretchCalculator_brand, this, _overwriteColumnWidthFn).bind(this))], ['last', new StretchLastStrategy(_assertClassBrand(_StretchCalculator_brand, this, _overwriteColumnWidthFn).bind(this))]]));
    /**
     * The active stretch mode.
     *
     * @type {'all' | 'last' | 'none'}
     */
    _classPrivateFieldInitSpec(this, _activeStrategy, 'none');
    _classPrivateFieldSet(_hot, this, hotInstance);
    _classPrivateFieldSet(_widthsMap, this, _classPrivateFieldGet(_hot, this).columnIndexMapper.createAndRegisterIndexMap(STRETCH_WIDTH_MAP_NAME, 'physicalIndexToValue'));
  }

  /**
   * Sets the active stretch strategy.
   *
   * @param {'all' | 'last' | 'none'} strategyName The stretch strategy to use.
   */
  useStrategy(strategyName) {
    _classPrivateFieldSet(_activeStrategy, this, _classPrivateFieldGet(_stretchStrategies, this).has(strategyName) ? strategyName : 'none');
  }

  /**
   * Recalculates the column widths.
   */
  refreshStretching() {
    if (_classPrivateFieldGet(_activeStrategy, this) === 'none') {
      _classPrivateFieldGet(_widthsMap, this).clear();
      return;
    }
    _classPrivateFieldGet(_hot, this).batchExecution(() => {
      _classPrivateFieldGet(_widthsMap, this).clear();
      const stretchStrategy = _classPrivateFieldGet(_stretchStrategies, this).get(_classPrivateFieldGet(_activeStrategy, this));
      const view = _classPrivateFieldGet(_hot, this).view;
      let viewportWidth = view.getViewportWidth();
      if (_assertClassBrand(_StretchCalculator_brand, this, _willVerticalScrollAppear).call(this)) {
        viewportWidth -= getScrollbarWidth(_classPrivateFieldGet(_hot, this).rootDocument);
      }
      stretchStrategy.prepare({
        viewportWidth
      });
      for (let columnIndex = 0; columnIndex < _classPrivateFieldGet(_hot, this).countCols(); columnIndex++) {
        if (!_classPrivateFieldGet(_hot, this).columnIndexMapper.isHidden(_classPrivateFieldGet(_hot, this).toPhysicalColumn(columnIndex))) {
          stretchStrategy.setColumnBaseWidth(columnIndex, _assertClassBrand(_StretchCalculator_brand, this, _getWidthWithoutStretching).call(this, columnIndex));
        }
      }
      stretchStrategy.calculate();
      stretchStrategy.getWidths().forEach(_ref => {
        let [columnIndex, width] = _ref;
        _classPrivateFieldGet(_widthsMap, this).setValueAtIndex(_classPrivateFieldGet(_hot, this).toPhysicalColumn(columnIndex), width);
      });
    }, true);
  }

  /**
   * Gets the calculated column width.
   *
   * @param {number} columnVisualIndex Column visual index.
   * @returns {number | null}
   */
  getStretchedWidth(columnVisualIndex) {
    return _classPrivateFieldGet(_widthsMap, this).getValueAtIndex(_classPrivateFieldGet(_hot, this).toPhysicalColumn(columnVisualIndex));
  }
}
function _willVerticalScrollAppear() {
  const {
    view
  } = _classPrivateFieldGet(_hot, this);
  if (view.isVerticallyScrollableByWindow()) {
    return false;
  }
  const viewportHeight = view.getViewportHeight();
  const totalRows = _classPrivateFieldGet(_hot, this).countRows();
  const defaultRowHeight = view.getStylesHandler().getDefaultRowHeight();
  let totalHeight = 0;
  let hasVerticalScroll = false;
  for (let row = 0; row < totalRows; row++) {
    var _classPrivateFieldGet2;
    totalHeight += ((_classPrivateFieldGet2 = _classPrivateFieldGet(_hot, this).getRowHeight(row)) !== null && _classPrivateFieldGet2 !== void 0 ? _classPrivateFieldGet2 : defaultRowHeight) + (row === 0 ? 1 : 0);
    if (totalHeight > viewportHeight) {
      hasVerticalScroll = true;
      break;
    }
  }
  return hasVerticalScroll;
}
/**
 * Gets the column width from the Handsontable API without logic related to stretching.
 *
 * @param {number} columnVisualIndex Column visual index.
 * @returns {number}
 */
function _getWidthWithoutStretching(columnVisualIndex) {
  var _classPrivateFieldGet3;
  return (_classPrivateFieldGet3 = _classPrivateFieldGet(_hot, this).getColWidth(columnVisualIndex, 'StretchColumns')) !== null && _classPrivateFieldGet3 !== void 0 ? _classPrivateFieldGet3 : DEFAULT_COLUMN_WIDTH;
}
/**
 * Executes the hook that allows to overwrite the column width.
 *
 * @param {number} columnWidth The column width.
 * @param {number} columnVisualIndex Column visual index.
 * @returns {number}
 */
function _overwriteColumnWidthFn(columnWidth, columnVisualIndex) {
  return _classPrivateFieldGet(_hot, this).runHooks('beforeStretchingColumnWidth', columnWidth, columnVisualIndex);
}