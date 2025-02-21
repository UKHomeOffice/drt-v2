function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { fastInnerHTML } from '../../../helpers/dom/element';
import { clone } from '../../../helpers/object';

var GhostTable =
/*#__PURE__*/
function () {
  function GhostTable(plugin) {
    _classCallCheck(this, GhostTable);

    /**
     * Reference to NestedHeaders plugin.
     *
     * @type {NestedHeaders}
     */
    this.nestedHeaders = plugin;
    /**
     * Temporary element created to get minimal headers widths.
     *
     * @type {*}
     */

    this.container = void 0;
    /**
     * Cached the headers widths.
     *
     * @type {Array}
     */

    this.widthsCache = [];
  }
  /**
   * Build cache of the headers widths.
   *
   * @private
   */


  _createClass(GhostTable, [{
    key: "buildWidthsMapper",
    value: function buildWidthsMapper() {
      this.container = this.nestedHeaders.hot.rootDocument.createElement('div');
      this.buildGhostTable(this.container);
      this.nestedHeaders.hot.rootElement.appendChild(this.container);
      var columns = this.container.querySelectorAll('tr:last-of-type th');
      var maxColumns = columns.length;

      for (var i = 0; i < maxColumns; i++) {
        this.widthsCache.push(columns[i].offsetWidth);
      }

      this.container.parentNode.removeChild(this.container);
      this.container = null;
      this.nestedHeaders.hot.render();
    }
    /**
     * Build temporary table for getting minimal columns widths.
     *
     * @private
     * @param {HTMLElement} container
     */

  }, {
    key: "buildGhostTable",
    value: function buildGhostTable(container) {
      var rootDocument = this.nestedHeaders.hot.rootDocument;
      var fragment = rootDocument.createDocumentFragment();
      var table = rootDocument.createElement('table');
      var lastRowColspan = false;
      var isDropdownEnabled = !!this.nestedHeaders.hot.getSettings().dropdownMenu;
      var maxRows = this.nestedHeaders.colspanArray.length;
      var maxCols = this.nestedHeaders.hot.countCols();
      var lastRowIndex = maxRows - 1;

      for (var row = 0; row < maxRows; row++) {
        var tr = rootDocument.createElement('tr');
        lastRowColspan = false;

        for (var col = 0; col < maxCols; col++) {
          var td = rootDocument.createElement('th');
          var headerObj = clone(this.nestedHeaders.colspanArray[row][col]);

          if (headerObj && !headerObj.hidden) {
            if (row === lastRowIndex) {
              if (headerObj.colspan > 1) {
                lastRowColspan = true;
              }

              if (isDropdownEnabled) {
                headerObj.label += '<button class="changeType"></button>';
              }
            }

            fastInnerHTML(td, headerObj.label);
            td.colSpan = headerObj.colspan;
            tr.appendChild(td);
          }
        }

        table.appendChild(tr);
      } // We have to be sure the last row contains only the single columns.


      if (lastRowColspan) {
        {
          var _tr = rootDocument.createElement('tr');

          for (var _col = 0; _col < maxCols; _col++) {
            var _td = rootDocument.createElement('th');

            _tr.appendChild(_td);
          }

          table.appendChild(_tr);
        }
      }

      fragment.appendChild(table);
      container.appendChild(fragment);
    }
    /**
     * Clear the widths cache.
     */

  }, {
    key: "clear",
    value: function clear() {
      this.container = null;
      this.widthsCache.length = 0;
    }
  }]);

  return GhostTable;
}();

export default GhostTable;