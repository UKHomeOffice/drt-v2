"use strict";

exports.__esModule = true;
var _array = require("../../../helpers/array");
var _number = require("../../../helpers/number");
var _element = require("../../../helpers/dom/element");
var _base = _interopRequireDefault(require("./_base"));
var _a11y = require("../../../helpers/a11y");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Class responsible for the UI in the Nested Rows' row headers.
 *
 * @private
 * @class HeadersUI
 * @augments BaseUI
 */
class HeadersUI extends _base.default {
  /**
   * CSS classes used in the row headers.
   *
   * @type {object}
   */
  static get CSS_CLASSES() {
    return {
      indicatorContainer: 'ht_nestingLevels',
      parent: 'ht_nestingParent',
      indicator: 'ht_nestingLevel',
      emptyIndicator: 'ht_nestingLevel_empty',
      button: 'ht_nestingButton',
      expandButton: 'ht_nestingExpand',
      collapseButton: 'ht_nestingCollapse'
    };
  }
  constructor(nestedRowsPlugin, hotInstance) {
    super(nestedRowsPlugin, hotInstance);
    /**
     * Reference to the DataManager instance connected with the Nested Rows plugin.
     *
     * @type {DataManager}
     */
    this.dataManager = this.plugin.dataManager;
    // /**
    //  * Level cache array.
    //  *
    //  * @type {Array}
    //  */
    // this.levelCache = this.dataManager.cache.levels;
    /**
     * Reference to the CollapsingUI instance connected with the Nested Rows plugin.
     *
     * @type {CollapsingUI}
     */
    this.collapsingUI = this.plugin.collapsingUI;
    /**
     * Cache for the row headers width.
     *
     * @type {null|number}
     */
    this.rowHeaderWidthCache = null;
  }

  /**
   * Append nesting indicators and buttons to the row headers.
   *
   * @private
   * @param {number} row Row index.
   * @param {HTMLElement} TH TH 3element.
   */
  appendLevelIndicators(row, TH) {
    const rowIndex = this.hot.toPhysicalRow(row);
    const rowLevel = this.dataManager.getRowLevel(rowIndex);
    const rowObject = this.dataManager.getDataObject(rowIndex);
    const innerDiv = TH.getElementsByTagName('DIV')[0];
    const innerSpan = innerDiv.querySelector('span.rowHeader');
    const previousIndicators = innerDiv.querySelectorAll('[class^="ht_nesting"]');
    const ariaEnabled = this.hot.getSettings().ariaTags;
    (0, _array.arrayEach)(previousIndicators, elem => {
      if (elem) {
        innerDiv.removeChild(elem);
      }
    });
    (0, _element.addClass)(TH, HeadersUI.CSS_CLASSES.indicatorContainer);
    if (rowLevel) {
      const {
        rootDocument
      } = this.hot;
      const initialContent = innerSpan.cloneNode(true);
      innerDiv.innerHTML = '';
      (0, _number.rangeEach)(0, rowLevel - 1, () => {
        const levelIndicator = rootDocument.createElement('SPAN');
        (0, _element.addClass)(levelIndicator, HeadersUI.CSS_CLASSES.emptyIndicator);
        innerDiv.appendChild(levelIndicator);
      });
      innerDiv.appendChild(initialContent);
    }
    if (this.dataManager.hasChildren(rowObject)) {
      const buttonsContainer = this.hot.rootDocument.createElement('DIV');
      if (ariaEnabled) {
        (0, _element.setAttribute)(buttonsContainer, [(0, _a11y.A11Y_HIDDEN)()]);
      }
      (0, _element.addClass)(TH, HeadersUI.CSS_CLASSES.parent);
      if (this.collapsingUI.areChildrenCollapsed(rowIndex)) {
        (0, _element.addClass)(buttonsContainer, `${HeadersUI.CSS_CLASSES.button} ${HeadersUI.CSS_CLASSES.expandButton}`);
        if (ariaEnabled) {
          (0, _element.setAttribute)(TH, [(0, _a11y.A11Y_EXPANDED)(false)]);
        }
      } else {
        (0, _element.addClass)(buttonsContainer, `${HeadersUI.CSS_CLASSES.button} ${HeadersUI.CSS_CLASSES.collapseButton}`);
        if (ariaEnabled) {
          (0, _element.setAttribute)(TH, [(0, _a11y.A11Y_EXPANDED)(true)]);
        }
      }
      innerDiv.appendChild(buttonsContainer);
    }
  }

  /**
   * Update the row header width according to number of levels in the dataset.
   *
   * @private
   * @param {number} deepestLevel Cached deepest level of nesting.
   */
  updateRowHeaderWidth(deepestLevel) {
    let deepestLevelIndex = deepestLevel;
    if (!deepestLevelIndex) {
      deepestLevelIndex = this.dataManager.cache.levelCount;
    }
    const stylesHandler = this.hot.view.getStylesHandler();
    let completeVerticalPadding = 11;
    if (!stylesHandler.isClassicTheme()) {
      const verticalPadding = stylesHandler.getCSSVariableValue('cell-horizontal-padding');
      completeVerticalPadding = verticalPadding * 2;
    }
    this.rowHeaderWidthCache = Math.max(50, completeVerticalPadding + 10 * deepestLevelIndex + 25);
    this.hot.render();
  }
}
var _default = exports.default = HeadersUI;