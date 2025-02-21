function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import RowHeadersRenderer from './rowHeaders';
import ColumnHeadersRenderer from './columnHeaders';
import ColGroupRenderer from './colGroup';
import RowsRenderer from './rows';
import CellsRenderer from './cells';
import TableRenderer from './table';
/**
 * Content renderer.
 *
 * @class Renderer
 */

var Renderer =
/*#__PURE__*/
function () {
  function Renderer() {
    var _ref = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {},
        TABLE = _ref.TABLE,
        THEAD = _ref.THEAD,
        COLGROUP = _ref.COLGROUP,
        TBODY = _ref.TBODY,
        rowUtils = _ref.rowUtils,
        columnUtils = _ref.columnUtils,
        cellRenderer = _ref.cellRenderer;

    _classCallCheck(this, Renderer);

    /**
     * General renderer class used to render Walkontable content on screen.
     *
     * @type {TableRenderer}
     */
    this.renderer = new TableRenderer(TABLE, {
      cellRenderer: cellRenderer
    });
    this.renderer.setRenderers({
      rowHeaders: new RowHeadersRenderer(),
      columnHeaders: new ColumnHeadersRenderer(THEAD),
      colGroup: new ColGroupRenderer(COLGROUP),
      rows: new RowsRenderer(TBODY),
      cells: new CellsRenderer()
    });
    this.renderer.setAxisUtils(rowUtils, columnUtils);
  }
  /**
   * Sets filter calculators for newly calculated row and column position. The filters are used to transform visual
   * indexes (0 to N) to source indexes provided by Handsontable.
   *
   * @param {RowFilter} rowFilter
   * @param {ColumnFilter} columnFilter
   * @returns {Renderer}
   */


  _createClass(Renderer, [{
    key: "setFilters",
    value: function setFilters(rowFilter, columnFilter) {
      this.renderer.setFilters(rowFilter, columnFilter);
      return this;
    }
    /**
     * Sets the viewport size of the rendered table.
     *
     * @param {Number} rowsCount An amount of rows to render.
     * @param {Number} columnsCount An amount of columns to render.
     * @return {Renderer}
     */

  }, {
    key: "setViewportSize",
    value: function setViewportSize(rowsCount, columnsCount) {
      this.renderer.setViewportSize(rowsCount, columnsCount);
      return this;
    }
    /**
     * Sets row and column header functions.
     *
     * @param {Function[]} rowHeaders Row header functions. Factories for creating content for row headers.
     * @param {Function[]} columnHeaders Column header functions. Factories for creating content for column headers.
     * @returns {Renderer}
     */

  }, {
    key: "setHeaderContentRenderers",
    value: function setHeaderContentRenderers(rowHeaders, columnHeaders) {
      this.renderer.setHeaderContentRenderers(rowHeaders, columnHeaders);
      return this;
    }
    /**
     * Adjusts the table (preparing for render).
     */

  }, {
    key: "adjust",
    value: function adjust() {
      this.renderer.adjust();
    }
    /**
     * Renders the table.
     */

  }, {
    key: "render",
    value: function render() {
      this.renderer.render();
    }
  }]);

  return Renderer;
}();

export { RowHeadersRenderer, ColumnHeadersRenderer, ColGroupRenderer, RowsRenderer, CellsRenderer, TableRenderer, Renderer };