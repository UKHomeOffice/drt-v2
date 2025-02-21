"use strict";

exports.__esModule = true;
exports.Renderer = void 0;

var _rowHeaders = _interopRequireDefault(require("./rowHeaders"));

exports.RowHeadersRenderer = _rowHeaders.default;

var _columnHeaders = _interopRequireDefault(require("./columnHeaders"));

exports.ColumnHeadersRenderer = _columnHeaders.default;

var _colGroup = _interopRequireDefault(require("./colGroup"));

exports.ColGroupRenderer = _colGroup.default;

var _rows = _interopRequireDefault(require("./rows"));

exports.RowsRenderer = _rows.default;

var _cells = _interopRequireDefault(require("./cells"));

exports.CellsRenderer = _cells.default;

var _table = _interopRequireDefault(require("./table"));

exports.TableRenderer = _table.default;

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

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
    this.renderer = new _table.default(TABLE, {
      cellRenderer: cellRenderer
    });
    this.renderer.setRenderers({
      rowHeaders: new _rowHeaders.default(),
      columnHeaders: new _columnHeaders.default(THEAD),
      colGroup: new _colGroup.default(COLGROUP),
      rows: new _rows.default(TBODY),
      cells: new _cells.default()
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

exports.Renderer = Renderer;