import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

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

import BasePlugin from '../_base';
import { arrayEach } from '../../helpers/array';
import { isObject, objectEach } from '../../helpers/object';
import EventManager from '../../eventManager';
import { registerPlugin } from '../../plugins';
import { isFormulaExpression, toUpperCaseFormula, isFormulaExpressionEscaped, unescapeFormulaExpression } from './utils';
import Sheet from './sheet';
import DataProvider from './dataProvider';
import UndoRedoSnapshot from './undoRedoSnapshot';
/**
 * The formulas plugin.
 *
 * @plugin Formulas
 * @experimental
 */

var Formulas =
/*#__PURE__*/
function (_BasePlugin) {
  _inherits(Formulas, _BasePlugin);

  function Formulas(hotInstance) {
    var _this;

    _classCallCheck(this, Formulas);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(Formulas).call(this, hotInstance));
    /**
     * Instance of {@link EventManager}.
     *
     * @private
     * @type {EventManager}
     */

    _this.eventManager = new EventManager(_assertThisInitialized(_this));
    /**
     * Instance of {@link DataProvider}.
     *
     * @private
     * @type {DataProvider}
     */

    _this.dataProvider = new DataProvider(_this.hot);
    /**
     * Instance of {@link Sheet}.
     *
     * @private
     * @type {Sheet}
     */

    _this.sheet = new Sheet(_this.hot, _this.dataProvider);
    /**
     * Instance of {@link UndoRedoSnapshot}.
     *
     * @private
     * @type {UndoRedoSnapshot}
     */

    _this.undoRedoSnapshot = new UndoRedoSnapshot(_this.sheet);
    /**
     * Flag which indicates if table should be re-render after sheet recalculations.
     *
     * @type {Boolean}
     * @default false
     * @private
     */

    _this._skipRendering = false;
    return _this;
  }
  /**
   * Checks if the plugin is enabled in the handsontable settings. This method is executed in {@link Hooks#beforeInit}
   * hook and if it returns `true` than the {@link Formulas#enablePlugin} method is called.
   *
   * @returns {Boolean}
   */


  _createClass(Formulas, [{
    key: "isEnabled",
    value: function isEnabled() {
      /* eslint-disable no-unneeded-ternary */
      return this.hot.getSettings().formulas ? true : false;
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

      var settings = this.hot.getSettings().formulas;

      if (isObject(settings)) {
        if (isObject(settings.variables)) {
          objectEach(settings.variables, function (value, name) {
            return _this2.setVariable(name, value);
          });
        }
      }

      this.addHook('afterColumnSort', function () {
        return _this2.onAfterColumnSort.apply(_this2, arguments);
      });
      this.addHook('afterCreateCol', function () {
        return _this2.onAfterCreateCol.apply(_this2, arguments);
      });
      this.addHook('afterCreateRow', function () {
        return _this2.onAfterCreateRow.apply(_this2, arguments);
      });
      this.addHook('afterLoadData', function () {
        return _this2.onAfterLoadData();
      });
      this.addHook('afterRemoveCol', function () {
        return _this2.onAfterRemoveCol.apply(_this2, arguments);
      });
      this.addHook('afterRemoveRow', function () {
        return _this2.onAfterRemoveRow.apply(_this2, arguments);
      });
      this.addHook('afterSetDataAtCell', function () {
        return _this2.onAfterSetDataAtCell.apply(_this2, arguments);
      });
      this.addHook('afterSetDataAtRowProp', function () {
        return _this2.onAfterSetDataAtCell.apply(_this2, arguments);
      });
      this.addHook('beforeColumnSort', function () {
        return _this2.onBeforeColumnSort.apply(_this2, arguments);
      });
      this.addHook('beforeCreateCol', function () {
        return _this2.onBeforeCreateCol.apply(_this2, arguments);
      });
      this.addHook('beforeCreateRow', function () {
        return _this2.onBeforeCreateRow.apply(_this2, arguments);
      });
      this.addHook('beforeRemoveCol', function () {
        return _this2.onBeforeRemoveCol.apply(_this2, arguments);
      });
      this.addHook('beforeRemoveRow', function () {
        return _this2.onBeforeRemoveRow.apply(_this2, arguments);
      });
      this.addHook('beforeValidate', function () {
        return _this2.onBeforeValidate.apply(_this2, arguments);
      });
      this.addHook('beforeValueRender', function () {
        return _this2.onBeforeValueRender.apply(_this2, arguments);
      });
      this.addHook('modifyData', function () {
        return _this2.onModifyData.apply(_this2, arguments);
      });
      this.sheet.addLocalHook('afterRecalculate', function () {
        return _this2.onSheetAfterRecalculate.apply(_this2, arguments);
      });

      _get(_getPrototypeOf(Formulas.prototype), "enablePlugin", this).call(this);
    }
    /**
     * Disables the plugin functionality for this Handsontable instance.
     */

  }, {
    key: "disablePlugin",
    value: function disablePlugin() {
      _get(_getPrototypeOf(Formulas.prototype), "disablePlugin", this).call(this);
    }
    /**
     * Returns cell value (evaluated from formula expression) at specified cell coords.
     *
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @returns {*}
     */

  }, {
    key: "getCellValue",
    value: function getCellValue(row, column) {
      var cell = this.sheet.getCellAt(row, column);
      return cell ? cell.getError() || cell.getValue() : void 0;
    }
    /**
     * Checks if there are any formula evaluations made under specific cell coords.
     *
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @returns {Boolean}
     */

  }, {
    key: "hasComputedCellValue",
    value: function hasComputedCellValue(row, column) {
      return this.sheet.getCellAt(row, column) !== null;
    }
    /**
     * Recalculates all formulas (an algorithm will choose the best method of calculation).
     */

  }, {
    key: "recalculate",
    value: function recalculate() {
      this.sheet.recalculate();
    }
    /**
     * Recalculates all formulas (rebuild dependencies from scratch - slow approach).
     */

  }, {
    key: "recalculateFull",
    value: function recalculateFull() {
      this.sheet.recalculateFull();
    }
    /**
     * Recalculates all formulas (recalculate only changed cells - fast approach).
     */

  }, {
    key: "recalculateOptimized",
    value: function recalculateOptimized() {
      this.sheet.recalculateOptimized();
    }
    /**
     * Sets predefined variable name which can be visible while parsing formula expression.
     *
     * @param {String} name Variable name.
     * @param {*} value Variable value.
     */

  }, {
    key: "setVariable",
    value: function setVariable(name, value) {
      this.sheet.setVariable(name, value);
    }
    /**
     * Returns variable name.
     *
     * @param {String} name Variable name.
     * @returns {*}
     */

  }, {
    key: "getVariable",
    value: function getVariable(name) {
      return this.sheet.getVariable(name);
    }
    /**
     * Local hook listener for after sheet recalculation.
     *
     * @private
     * @param {Array} cells An array of recalculated/changed cells.
     */

  }, {
    key: "onSheetAfterRecalculate",
    value: function onSheetAfterRecalculate(cells) {
      if (this._skipRendering) {
        this._skipRendering = false;
        return;
      }

      var hot = this.hot;
      arrayEach(cells, function (_ref) {
        var row = _ref.row,
            column = _ref.column;
        hot.validateCell(hot.getDataAtCell(row, column), hot.getCellMeta(row, column), function () {});
      });
      hot.render();
    }
    /**
     * On modify row data listener. It overwrites raw values into calculated ones and force upper case all formula expressions.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} column Column index.
     * @param {Object} valueHolder Value holder as an object to change value by reference.
     * @param {String} ioMode IO operation (`get` or `set`).
     * @returns {Array|undefined} Returns modified row data.
     */

  }, {
    key: "onModifyData",
    value: function onModifyData(row, column, valueHolder, ioMode) {
      if (ioMode === 'get' && this.hasComputedCellValue(row, column)) {
        valueHolder.value = this.getCellValue(row, column);
      } else if (ioMode === 'set' && isFormulaExpression(valueHolder.value)) {
        valueHolder.value = toUpperCaseFormula(valueHolder.value);
      }
    }
    /**
     * On before value render listener.
     *
     * @private
     * @param {*} value Value to render.
     * @returns {*}
     */

  }, {
    key: "onBeforeValueRender",
    value: function onBeforeValueRender(value) {
      var renderValue = value;

      if (isFormulaExpressionEscaped(renderValue)) {
        renderValue = unescapeFormulaExpression(renderValue);
      }

      return renderValue;
    }
    /**
     * On before validate listener.
     *
     * @private
     * @param {*} value Value to validate.
     * @param {Number} row Row index.
     * @param {Number} prop Column property.
     */

  }, {
    key: "onBeforeValidate",
    value: function onBeforeValidate(value, row, prop) {
      var column = this.hot.propToCol(prop);
      var validateValue = value;

      if (this.hasComputedCellValue(row, column)) {
        validateValue = this.getCellValue(row, column);
      }

      return validateValue;
    }
    /**
     * `afterSetDataAtCell` listener.
     *
     * @private
     * @param {Array} changes Array of changes.
     * @param {String} [source] Source of changes.
     */

  }, {
    key: "onAfterSetDataAtCell",
    value: function onAfterSetDataAtCell(changes, source) {
      var _this3 = this;

      if (source === 'loadData') {
        return;
      }

      this.dataProvider.clearChanges();
      arrayEach(changes, function (_ref2) {
        var _ref3 = _slicedToArray(_ref2, 4),
            row = _ref3[0],
            column = _ref3[1],
            oldValue = _ref3[2],
            newValue = _ref3[3];

        var physicalColumn = _this3.hot.propToCol(column);

        var physicalRow = _this3.t.toPhysicalRow(row);

        var value = newValue;

        if (isFormulaExpression(value)) {
          value = toUpperCaseFormula(value);
        }

        _this3.dataProvider.collectChanges(physicalRow, physicalColumn, value);

        if (oldValue !== value) {
          _this3.sheet.applyChanges(physicalRow, physicalColumn, value);
        }
      });
      this.recalculate();
    }
    /**
     * On before create row listener.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} amount An amount of removed rows.
     * @param {String} source Source of method call.
     */

  }, {
    key: "onBeforeCreateRow",
    value: function onBeforeCreateRow(row, amount, source) {
      if (source === 'UndoRedo.undo') {
        this.undoRedoSnapshot.restore();
      }
    }
    /**
     * On after create row listener.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} amount An amount of created rows.
     * @param {String} source Source of method call.
     */

  }, {
    key: "onAfterCreateRow",
    value: function onAfterCreateRow(row, amount, source) {
      this.sheet.alterManager.triggerAlter('insert_row', row, amount, source !== 'UndoRedo.undo');
    }
    /**
     * On before remove row listener.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} amount An amount of removed rows.
     */

  }, {
    key: "onBeforeRemoveRow",
    value: function onBeforeRemoveRow(row, amount) {
      this.undoRedoSnapshot.save('row', row, amount);
    }
    /**
     * On after remove row listener.
     *
     * @private
     * @param {Number} row Row index.
     * @param {Number} amount An amount of removed rows.
     */

  }, {
    key: "onAfterRemoveRow",
    value: function onAfterRemoveRow(row, amount) {
      this.sheet.alterManager.triggerAlter('remove_row', row, amount);
    }
    /**
     * On before create column listener.
     *
     * @private
     * @param {Number} column Column index.
     * @param {Number} amount An amount of removed columns.
     * @param {String} source Source of method call.
     */

  }, {
    key: "onBeforeCreateCol",
    value: function onBeforeCreateCol(column, amount, source) {
      if (source === 'UndoRedo.undo') {
        this.undoRedoSnapshot.restore();
      }
    }
    /**
     * On after create column listener.
     *
     * @private
     * @param {Number} column Column index.
     * @param {Number} amount An amount of created columns.
     * @param {String} source Source of method call.
     */

  }, {
    key: "onAfterCreateCol",
    value: function onAfterCreateCol(column, amount, source) {
      this.sheet.alterManager.triggerAlter('insert_column', column, amount, source !== 'UndoRedo.undo');
    }
    /**
     * On before remove column listener.
     *
     * @private
     * @param {Number} column Column index.
     * @param {Number} amount An amount of removed columns.
     */

  }, {
    key: "onBeforeRemoveCol",
    value: function onBeforeRemoveCol(column, amount) {
      this.undoRedoSnapshot.save('column', column, amount);
    }
    /**
     * On after remove column listener.
     *
     * @private
     * @param {Number} column Column index.
     * @param {Number} amount An amount of created columns.
     */

  }, {
    key: "onAfterRemoveCol",
    value: function onAfterRemoveCol(column, amount) {
      this.sheet.alterManager.triggerAlter('remove_column', column, amount);
    }
    /**
     * On before column sorting listener.
     *
     * @private
     * @param {Number} column Sorted column index.
     * @param {Boolean} order Order type.
     */

  }, {
    key: "onBeforeColumnSort",
    value: function onBeforeColumnSort(column, order) {
      this.sheet.alterManager.prepareAlter('column_sorting', column, order);
    }
    /**
     * On after column sorting listener.
     *
     * @private
     * @param {Number} column Sorted column index.
     * @param {Boolean} order Order type.
     */

  }, {
    key: "onAfterColumnSort",
    value: function onAfterColumnSort(column, order) {
      this.sheet.alterManager.triggerAlter('column_sorting', column, order);
    }
    /**
     * On after load data listener.
     *
     * @private
     */

  }, {
    key: "onAfterLoadData",
    value: function onAfterLoadData() {
      this._skipRendering = true;
      this.recalculateFull();
    }
    /**
     * Destroys the plugin instance.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.dataProvider.destroy();
      this.dataProvider = null;
      this.sheet.destroy();
      this.sheet = null;

      _get(_getPrototypeOf(Formulas.prototype), "destroy", this).call(this);
    }
  }]);

  return Formulas;
}(BasePlugin);

registerPlugin('formulas', Formulas);
export default Formulas;