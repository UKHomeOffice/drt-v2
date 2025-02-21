"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.splice");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.keys");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _object = require("../../helpers/object");

var _array = require("../../helpers/array");

var _number = require("../../helpers/number");

var _console = require("../../helpers/console");

var _element = require("../../helpers/dom/element");

var _eventManager = _interopRequireDefault(require("../../eventManager"));

var _plugins = require("../../plugins");

var _event = require("../../helpers/dom/event");

var _base = _interopRequireDefault(require("../_base"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * @plugin CollapsibleColumns
 * @dependencies NestedHeaders HiddenColumns
 *
 * @description
 * The {@link CollapsibleColumns} plugin allows collapsing of columns, covered by a header with the `colspan` property defined.
 *
 * Clicking the "collapse/expand" button collapses (or expands) all "child" headers except the first one.
 *
 * Setting the {@link Options#collapsibleColumns} property to `true` will display a "collapse/expand" button in every header
 * with a defined `colspan` property.
 *
 * To limit this functionality to a smaller group of headers, define the `collapsibleColumns` property as an array
 * of objects, as in the example below.
 *
 * @example
 * ```js
 * const container = document.getElementById('example');
 * const hot = new Handsontable(container, {
 *   data: generateDataObj(),
 *   colHeaders: true,
 *   rowHeaders: true,
 *   // enable plugin
 *   collapsibleColumns: true,
 * });
 *
 * // or
 * const hot = new Handsontable(container, {
 *   data: generateDataObj(),
 *   colHeaders: true,
 *   rowHeaders: true,
 *   // enable and configure which columns can be collapsed
 *   collapsibleColumns: [
 *     {row: -4, col: 1, collapsible: true},
 *     {row: -3, col: 5, collapsible: true}
 *   ],
 * });
 * ```
 */
var CollapsibleColumns =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(CollapsibleColumns, _BasePlugin);

  function CollapsibleColumns(hotInstance) {
    var _this;

    _classCallCheck(this, CollapsibleColumns);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(CollapsibleColumns).call(this, hotInstance));
    /**
     * Cached plugin settings.
     *
     * @private
     * @type {Boolean|Array}
     */

    _this.settings = null;
    /**
     * Object listing headers with buttons enabled.
     *
     * @private
     * @type {Object}
     */

    _this.buttonEnabledList = {};
    /**
     * Cached reference to the HiddenColumns plugin.
     *
     * @private
     * @type {Object}
     */

    _this.hiddenColumnsPlugin = null;
    /**
     * Cached reference to the NestedHeaders plugin.
     *
     * @private
     * @type {Object}
     */

    _this.nestedHeadersPlugin = null;
    /**
     * Object listing the currently collapsed sections.
     *
     * @private
     * @type {Object}
     */

    _this.collapsedSections = {};
    /**
     * Number of column header levels.
     *
     * @private
     * @type {Number}
     */

    _this.columnHeaderLevelCount = null;
    /**
     * Event manager instance reference.
     *
     * @private
     * @type {EventManager}
     */

    _this.eventManager = null;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link CollapsibleColumns#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(CollapsibleColumns, [{
    key: "isEnabled",
    value: function isEnabled() {
      return !!this.hot.getSettings().collapsibleColumns;
    }
    /**
     * Enables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "enablePlugin",
    value: function enablePlugin() {
      var _this2 = this;

      if (this.enabled) {
        return;
      }

      this.settings = this.hot.getSettings().collapsibleColumns;

      if (typeof this.settings !== 'boolean') {
        this.parseSettings();
      }

      this.hiddenColumnsPlugin = this.hot.getPlugin('hiddenColumns');
      this.nestedHeadersPlugin = this.hot.getPlugin('nestedHeaders');
      this.checkDependencies();
      this.addHook('afterRender', function () {
        return _this2.onAfterRender();
      });
      this.addHook('afterInit', function () {
        return _this2.onAfterInit();
      });
      this.addHook('afterGetColHeader', function (col, TH) {
        return _this2.onAfterGetColHeader(col, TH);
      });
      this.addHook('beforeOnCellMouseDown', function (event, coords, TD) {
        return _this2.onBeforeOnCellMouseDown(event, coords, TD);
      });
      this.eventManager = new _eventManager.default(this.hot);

      _get(_getPrototypeOf(CollapsibleColumns.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      this.settings = null;
      this.buttonEnabledList = {};
      this.hiddenColumnsPlugin = null;
      this.collapsedSections = {};
      this.clearButtons();

      _get(_getPrototypeOf(CollapsibleColumns.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Clears the expand/collapse buttons.
     *
     * @private
     */

  }, {
    key: "clearButtons",
    value: function clearButtons() {
      if (!this.hot.view) {
        return;
      }

      var headerLevels = this.hot.view.wt.getSetting('columnHeaders').length;
      var mainHeaders = this.hot.view.wt.wtTable.THEAD;
      var topHeaders = this.hot.view.wt.wtOverlays.topOverlay.clone.wtTable.THEAD;
      var topLeftCornerHeaders = this.hot.view.wt.wtOverlays.topLeftCornerOverlay ? this.hot.view.wt.wtOverlays.topLeftCornerOverlay.clone.wtTable.THEAD : null;

      var removeButton = function removeButton(button) {
        if (button) {
          button.parentNode.removeChild(button);
        }
      };

      (0, _number.rangeEach)(0, headerLevels - 1, function (i) {
        var masterLevel = mainHeaders.childNodes[i];
        var topLevel = topHeaders.childNodes[i];
        var topLeftCornerLevel = topLeftCornerHeaders ? topLeftCornerHeaders.childNodes[i] : null;
        (0, _number.rangeEach)(0, masterLevel.childNodes.length - 1, function (j) {
          var button = masterLevel.childNodes[j].querySelector('.collapsibleIndicator');
          removeButton(button);

          if (topLevel && topLevel.childNodes[j]) {
            button = topLevel.childNodes[j].querySelector('.collapsibleIndicator');
            removeButton(button);
          }

          if (topLeftCornerHeaders && topLeftCornerLevel && topLeftCornerLevel.childNodes[j]) {
            button = topLeftCornerLevel.childNodes[j].querySelector('.collapsibleIndicator');
            removeButton(button);
          }
        });
      }, true);
    }
    /**
     * Parses the plugin settings and create a button configuration array.
     *
     * @private
     */

  }, {
    key: "parseSettings",
    value: function parseSettings() {
      var _this3 = this;

      (0, _object.objectEach)(this.settings, function (val) {
        if (!_this3.buttonEnabledList[val.row]) {
          _this3.buttonEnabledList[val.row] = {};
        }

        _this3.buttonEnabledList[val.row][val.col] = val.collapsible;
      });
    }
    /**
     * Checks if plugin dependencies are met.
     *
     * @private
     * @returns {Boolean}
     */

  }, {
    key: "meetsDependencies",
    value: function meetsDependencies() {
      var settings = this.hot.getSettings();
      return settings.nestedHeaders && settings.hiddenColumns;
    }
    /**
     * Checks if all the required dependencies are enabled.
     *
     * @private
     */

  }, {
    key: "checkDependencies",
    value: function checkDependencies() {
      var settings = this.hot.getSettings();

      if (this.meetsDependencies()) {
        return;
      }

      if (!settings.nestedHeaders) {
        (0, _console.warn)('You need to configure the Nested Headers plugin in order to use collapsible headers.');
      }

      if (!settings.hiddenColumns) {
        (0, _console.warn)('You need to configure the Hidden Columns plugin in order to use collapsible headers.');
      }
    }
    /**
     * Generates the indicator element.
     *
     * @private
     * @param {Number} column Column index.
     * @param {HTMLElement} TH TH Element.
     * @returns {HTMLElement}
     */

  }, {
    key: "generateIndicator",
    value: function generateIndicator(column, TH) {
      var TR = TH.parentNode;
      var THEAD = TR.parentNode;
      var row = -1 * THEAD.childNodes.length + Array.prototype.indexOf.call(THEAD.childNodes, TR);

      if (Object.keys(this.buttonEnabledList).length > 0 && (!this.buttonEnabledList[row] || !this.buttonEnabledList[row][column])) {
        return null;
      }

      var divEl = this.hot.rootDocument.createElement('DIV');
      (0, _element.addClass)(divEl, 'collapsibleIndicator');

      if (this.collapsedSections[row] && this.collapsedSections[row][column] === true) {
        (0, _element.addClass)(divEl, 'collapsed');
        (0, _element.fastInnerText)(divEl, '+');
      } else {
        (0, _element.addClass)(divEl, 'expanded');
        (0, _element.fastInnerText)(divEl, '-');
      }

      return divEl;
    }
    /**
     * Marks (internally) a section as 'collapsed' or 'expanded' (optionally, also mark the 'child' headers).
     *
     * @private
     * @param {String} state State ('collapsed' or 'expanded').
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @param {Boolean} recursive If `true`, it will also attempt to mark the child sections.
     */

  }, {
    key: "markSectionAs",
    value: function markSectionAs(state, row, column, recursive) {
      if (!this.collapsedSections[row]) {
        this.collapsedSections[row] = {};
      }

      switch (state) {
        case 'collapsed':
          this.collapsedSections[row][column] = true;
          break;

        case 'expanded':
          this.collapsedSections[row][column] = void 0;
          break;

        default:
          break;
      }

      if (recursive) {
        var nestedHeadersColspans = this.nestedHeadersPlugin.colspanArray;
        var level = this.nestedHeadersPlugin.rowCoordsToLevel(row);
        var childHeaders = this.nestedHeadersPlugin.getChildHeaders(row, column);
        var childColspanLevel = nestedHeadersColspans[level + 1];

        for (var i = 1; i < childHeaders.length; i++) {
          if (childColspanLevel && childColspanLevel[childHeaders[i]].colspan > 1) {
            this.markSectionAs(state, row + 1, childHeaders[i], true);
          }
        }
      }
    }
    /**
     * Expands section at the provided coords.
     *
     * @param {Object} coords Contains coordinates information. (`coords.row`, `coords.col`)
     */

  }, {
    key: "expandSection",
    value: function expandSection(coords) {
      this.markSectionAs('expanded', coords.row, coords.col, true);
      this.toggleCollapsibleSection(coords, 'expand');
    }
    /**
     * Collapses section at the provided coords.
     *
     * @param {Object} coords Contains coordinates information. (`coords.row`, `coords.col`)
     */

  }, {
    key: "collapseSection",
    value: function collapseSection(coords) {
      this.markSectionAs('collapsed', coords.row, coords.col, true);
      this.toggleCollapsibleSection(coords, 'collapse');
    }
    /**
     * Collapses or expand all collapsible sections, depending on the action parameter.
     *
     * @param {String} action 'collapse' or 'expand'.
     */

  }, {
    key: "toggleAllCollapsibleSections",
    value: function toggleAllCollapsibleSections(action) {
      var _this4 = this;

      var nestedHeadersColspanArray = this.nestedHeadersPlugin.colspanArray;

      if (this.settings === true) {
        (0, _array.arrayEach)(nestedHeadersColspanArray, function (headerLevel, i) {
          (0, _array.arrayEach)(headerLevel, function (header, j) {
            if (header.colspan > 1) {
              var row = _this4.nestedHeadersPlugin.levelToRowCoords(parseInt(i, 10));

              var col = parseInt(j, 10);

              _this4.markSectionAs(action === 'collapse' ? 'collapsed' : 'expanded', row, col, true);

              _this4.toggleCollapsibleSection({
                row: row,
                col: col
              }, action);
            }
          });
        });
      } else {
        (0, _object.objectEach)(this.buttonEnabledList, function (headerRow, i) {
          (0, _object.objectEach)(headerRow, function (header, j) {
            var rowIndex = parseInt(i, 10);
            var columnIndex = parseInt(j, 10);

            _this4.markSectionAs(action === 'collapse' ? 'collapsed' : 'expanded', rowIndex, columnIndex, true);

            _this4.toggleCollapsibleSection({
              row: rowIndex,
              col: columnIndex
            }, action);
          });
        });
      }
    }
    /**
     * Collapses all collapsible sections.
     */

  }, {
    key: "collapseAll",
    value: function collapseAll() {
      this.toggleAllCollapsibleSections('collapse');
    }
    /**
     * Expands all collapsible sections.
     */

  }, {
    key: "expandAll",
    value: function expandAll() {
      this.toggleAllCollapsibleSections('expand');
    }
    /**
     * Collapses/Expands a section.
     *
     * @param {Object} coords Section coordinates.
     * @param {String} action Action definition ('collapse' or 'expand').
     */

  }, {
    key: "toggleCollapsibleSection",
    value: function toggleCollapsibleSection(coords, action) {
      var _this5 = this;

      if (coords.row) {
        coords.row = parseInt(coords.row, 10);
      }

      if (coords.col) {
        coords.col = parseInt(coords.col, 10);
      }

      var hiddenColumns = this.hiddenColumnsPlugin.hiddenColumns;
      var colspanArray = this.nestedHeadersPlugin.colspanArray;
      var level = this.nestedHeadersPlugin.rowCoordsToLevel(coords.row);
      var currentHeaderColspan = colspanArray[level][coords.col].colspan;
      var childHeaders = this.nestedHeadersPlugin.getChildHeaders(coords.row, coords.col);
      var nextLevel = level + 1;
      var childColspanLevel = colspanArray[nextLevel];
      var firstChildColspan = childColspanLevel ? childColspanLevel[childHeaders[0]].colspan || 1 : 1;

      while (firstChildColspan === currentHeaderColspan && nextLevel < this.columnHeaderLevelCount) {
        nextLevel += 1;
        childColspanLevel = colspanArray[nextLevel];
        firstChildColspan = childColspanLevel ? childColspanLevel[childHeaders[0]].colspan || 1 : 1;
      }

      (0, _number.rangeEach)(firstChildColspan, currentHeaderColspan - 1, function (i) {
        var colToHide = coords.col + i;

        switch (action) {
          case 'collapse':
            if (!_this5.hiddenColumnsPlugin.isHidden(colToHide)) {
              hiddenColumns.push(colToHide);
            }

            break;

          case 'expand':
            if (_this5.hiddenColumnsPlugin.isHidden(colToHide)) {
              hiddenColumns.splice(hiddenColumns.indexOf(colToHide), 1);
            }

            break;

          default:
            break;
        }
      });
      this.hot.render();
      this.hot.view.wt.wtOverlays.adjustElementsSize(true);
    }
    /**
     * Adds the indicator to the headers.
     *
     * @private
     * @param {Number} column Column index.
     * @param {HTMLElement} TH TH element.
     */

  }, {
    key: "onAfterGetColHeader",
    value: function onAfterGetColHeader(column, TH) {
      if (TH.hasAttribute('colspan') && TH.getAttribute('colspan') > 1 && column >= this.hot.getSettings().fixedColumnsLeft) {
        var button = this.generateIndicator(column, TH);

        if (button !== null) {
          TH.querySelector('div:first-child').appendChild(button);
        }
      }
    }
    /**
     * Indicator mouse event callback.
     *
     * @private
     * @param {Object} event Mouse event.
     * @param {Object} coords Event coordinates.
     */

  }, {
    key: "onBeforeOnCellMouseDown",
    value: function onBeforeOnCellMouseDown(event, coords) {
      if ((0, _element.hasClass)(event.target, 'collapsibleIndicator')) {
        if ((0, _element.hasClass)(event.target, 'expanded')) {
          // mark section as collapsed
          if (!this.collapsedSections[coords.row]) {
            this.collapsedSections[coords.row] = [];
          }

          this.markSectionAs('collapsed', coords.row, coords.col, true);
          this.eventManager.fireEvent(event.target, 'mouseup');
          this.toggleCollapsibleSection(coords, 'collapse');
        } else if ((0, _element.hasClass)(event.target, 'collapsed')) {
          this.markSectionAs('expanded', coords.row, coords.col, true);
          this.eventManager.fireEvent(event.target, 'mouseup');
          this.toggleCollapsibleSection(coords, 'expand');
        }

        (0, _event.stopImmediatePropagation)(event);
        return false;
      }
    }
    /**
     * AfterInit hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterInit",
    value: function onAfterInit() {
      this.columnHeaderLevelCount = this.hot.view.wt.getSetting('columnHeaders').length;
    }
    /**
     * AfterRender hook callback.
     *
     * @private
     */

  }, {
    key: "onAfterRender",
    value: function onAfterRender() {
      if (!this.nestedHeadersPlugin.enabled || !this.hiddenColumnsPlugin.enabled) {
        this.disablePlugin();
      }
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.settings = null;
      this.buttonEnabledList = null;
      this.hiddenColumnsPlugin = null;
      this.nestedHeadersPlugin = null;
      this.collapsedSections = null;
      this.columnHeaderLevelCount = null;

      _get(_getPrototypeOf(CollapsibleColumns.prototype), "destroy", this).call(this);
    }
  }]);

  return CollapsibleColumns;
}(_base.default);

(0, _plugins.registerPlugin)('collapsibleColumns', CollapsibleColumns);
var _default = CollapsibleColumns;
exports.default = _default;