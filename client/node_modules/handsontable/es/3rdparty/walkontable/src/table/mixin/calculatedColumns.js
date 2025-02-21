import { defineGetter } from '../../../../../helpers/object';
var MIXIN_NAME = 'calculatedColumns';
/**
 * Mixin for the subclasses of `Table` with implementations of
 * helper methods that are related to columns.
 * This mixin is meant to be applied in the subclasses of `Table`
 * that use virtual rendering in the horizontal axis.
 *
 * @type {Object}
 */

var calculatedColumns = {
  /**
   * Get the source index of the first rendered column. If no columns are rendered, returns an error code: -1.
   *
   * @returns {Number}
   */
  getFirstRenderedColumn: function getFirstRenderedColumn() {
    var startColumn = this.wot.wtViewport.columnsRenderCalculator.startColumn;

    if (startColumn === null) {
      return -1;
    }

    return startColumn;
  },

  /**
   * Get the source index of the first column fully visible in the viewport. If no columns are fully visible, returns an error code: -1.
   *
   * @returns {Number}
   */
  getFirstVisibleColumn: function getFirstVisibleColumn() {
    var startColumn = this.wot.wtViewport.columnsVisibleCalculator.startColumn;

    if (startColumn === null) {
      return -1;
    }

    return startColumn;
  },

  /**
   * Get the source index of the last rendered column. If no columns are rendered, returns an error code: -1.
   *
   * @returns {Number}
   */
  getLastRenderedColumn: function getLastRenderedColumn() {
    var endColumn = this.wot.wtViewport.columnsRenderCalculator.endColumn;

    if (endColumn === null) {
      return -1;
    }

    return endColumn;
  },

  /**
   * Get the source index of the last column fully visible in the viewport. If no columns are fully visible, returns an error code: -1.
   *
   * @returns {Number}
   */
  getLastVisibleColumn: function getLastVisibleColumn() {
    var endColumn = this.wot.wtViewport.columnsVisibleCalculator.endColumn;

    if (endColumn === null) {
      return -1;
    }

    return endColumn;
  },

  /**
   * Get the number of rendered columns.
   *
   * @returns {Number}
   */
  getRenderedColumnsCount: function getRenderedColumnsCount() {
    return this.wot.wtViewport.columnsRenderCalculator.count;
  },

  /**
   * Get the number of fully visible columns in the viewport.
   *
   * @returns {Number}
   */
  getVisibleColumnsCount: function getVisibleColumnsCount() {
    return this.wot.wtViewport.columnsVisibleCalculator.count;
  }
};
defineGetter(calculatedColumns, 'MIXIN_NAME', MIXIN_NAME, {
  writable: false,
  enumerable: false
});
export default calculatedColumns;