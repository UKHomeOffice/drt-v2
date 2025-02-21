import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.number.to-fixed";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { arrayEach } from '../../helpers/array';
import { warn } from '../../helpers/console';
import { getTranslator } from '../../utils/recordTranslator';
/**
 * Class used to make all endpoint-related operations.
 *
 * @class Endpoints
 * @plugin ColumnSummary
 */

var Endpoints =
/*#__PURE__*/
function () {
  function Endpoints(plugin, settings) {
    _classCallCheck(this, Endpoints);

    /**
     * The main plugin instance.
     */
    this.plugin = plugin;
    /**
     * Handsontable instance.
     *
     * @type {Object}
     */

    this.hot = this.plugin.hot;
    /**
     * Array of declared plugin endpoints (calculation destination points).
     *
     * @type {Array}
     * @default {Array} Empty array.
     */

    this.endpoints = [];
    /**
     * The plugin settings, taken from Handsontable configuration.
     *
     * @type {Object|Function}
     * @default null
     */

    this.settings = settings;
    /**
     * Settings type. Can be either 'array' or 'function.
     *
     * @type {string}
     * @default {'array'}
     */

    this.settingsType = 'array';
    /**
     * The current endpoint (calculation destination point) in question.
     *
     * @type {Object}
     * @default null
     */

    this.currentEndpoint = null;
    /**
     * Array containing a list of changes to be applied.
     *
     * @private
     * @type {Array}
     * @default {[]}
     */

    this.cellsToSetCache = [];
    /**
     * A `recordTranslator` instance.
     * @private
     * @type {Object}
     */

    this.recordTranslator = getTranslator(this.hot);
  }
  /**
   * Get a single endpoint object.
   *
   * @param {Number} index Index of the endpoint.
   * @returns {Object}
   */


  _createClass(Endpoints, [{
    key: "getEndpoint",
    value: function getEndpoint(index) {
      if (this.settingsType === 'function') {
        return this.fillMissingEndpointData(this.settings)[index];
      }

      return this.endpoints[index];
    }
    /**
     * Get an array with all the endpoints.
     *
     * @returns {Array}
     */

  }, {
    key: "getAllEndpoints",
    value: function getAllEndpoints() {
      if (this.settingsType === 'function') {
        return this.fillMissingEndpointData(this.settings);
      }

      return this.endpoints;
    }
    /**
     * Used to fill the blanks in the endpoint data provided by a settings function.
     *
     * @private
     * @param {Function} func Function provided in the HOT settings.
     * @returns {Array} An array of endpoints.
     */

  }, {
    key: "fillMissingEndpointData",
    value: function fillMissingEndpointData(func) {
      return this.parseSettings(func.call(this));
    }
    /**
     * Parse plugin's settings.
     *
     * @param {Array} settings The settings array.
     */

  }, {
    key: "parseSettings",
    value: function parseSettings(settings) {
      var _this = this;

      var endpointsArray = [];
      var settingsArray = settings;

      if (!settingsArray && typeof this.settings === 'function') {
        this.settingsType = 'function';
        return;
      }

      if (!settingsArray) {
        settingsArray = this.settings;
      }

      arrayEach(settingsArray, function (val) {
        var newEndpoint = {};

        _this.assignSetting(val, newEndpoint, 'ranges', [[0, _this.hot.countRows() - 1]]);

        _this.assignSetting(val, newEndpoint, 'reversedRowCoords', false);

        _this.assignSetting(val, newEndpoint, 'destinationRow', new Error("\n        You must provide a destination row for the Column Summary plugin in order to work properly!\n      "));

        _this.assignSetting(val, newEndpoint, 'destinationColumn', new Error("\n        You must provide a destination column for the Column Summary plugin in order to work properly!\n      "));

        _this.assignSetting(val, newEndpoint, 'sourceColumn', val.destinationColumn);

        _this.assignSetting(val, newEndpoint, 'type', 'sum');

        _this.assignSetting(val, newEndpoint, 'forceNumeric', false);

        _this.assignSetting(val, newEndpoint, 'suppressDataTypeErrors', true);

        _this.assignSetting(val, newEndpoint, 'suppressDataTypeErrors', true);

        _this.assignSetting(val, newEndpoint, 'customFunction', null);

        _this.assignSetting(val, newEndpoint, 'readOnly', true);

        _this.assignSetting(val, newEndpoint, 'roundFloat', false);

        endpointsArray.push(newEndpoint);
      });
      return endpointsArray;
    }
    /**
     * Setter for the internal setting objects.
     *
     * @param {Object} settings Object with the settings.
     * @param {Object} endpoint Contains information about the endpoint for the the calculation.
     * @param {String} name Settings name.
     * @param defaultValue Default value for the settings.
     */

  }, {
    key: "assignSetting",
    value: function assignSetting(settings, endpoint, name, defaultValue) {
      if (name === 'ranges' && settings[name] === void 0) {
        endpoint[name] = defaultValue;
        return;
      } else if (name === 'ranges' && settings[name].length === 0) {
        return;
      }

      if (settings[name] === void 0) {
        if (defaultValue instanceof Error) {
          throw defaultValue;
        }

        endpoint[name] = defaultValue;
      } else {
        /* eslint-disable no-lonely-if */
        if (name === 'destinationRow' && endpoint.reversedRowCoords) {
          endpoint[name] = this.hot.countRows() - settings[name] - 1;
        } else {
          endpoint[name] = settings[name];
        }
      }
    }
    /**
     * Resets the endpoint setup before the structure alteration (like inserting or removing rows/columns). Used for settings provided as a function.
     *
     * @private
     * @param {String} action Type of the action performed.
     * @param {Number} index Row/column index.
     * @param {Number} number Number of rows/columns added/removed.
     */

  }, {
    key: "resetSetupBeforeStructureAlteration",
    value: function resetSetupBeforeStructureAlteration(action, index, number) {
      if (this.settingsType !== 'function') {
        return;
      }

      var type = action.indexOf('row') > -1 ? 'row' : 'col';
      var endpoints = this.getAllEndpoints();
      arrayEach(endpoints, function (val) {
        if (type === 'row' && val.destinationRow >= index) {
          if (action === 'insert_row') {
            val.alterRowOffset = number;
          } else if (action === 'remove_row') {
            val.alterRowOffset = -1 * number;
          }
        }

        if (type === 'col' && val.destinationColumn >= index) {
          if (action === 'insert_col') {
            val.alterColumnOffset = number;
          } else if (action === 'remove_col') {
            val.alterColumnOffset = -1 * number;
          }
        }
      });
      this.resetAllEndpoints(endpoints, false);
    }
    /**
     * afterCreateRow/afterCreateRow/afterRemoveRow/afterRemoveCol hook callback. Reset and reenables the summary functionality
     * after changing the table structure.
     *
     * @private
     * @param {String} action Type of the action performed.
     * @param {Number} index Row/column index.
     * @param {Number} number Number of rows/columns added/removed.
     * @param {Array} [logicRows] Array of the logical indexes.
     * @param {String} [source] Source of change.
     * @param {Boolean} [forceRefresh] `true` of the endpoints should refresh after completing the function.
     */

  }, {
    key: "resetSetupAfterStructureAlteration",
    value: function resetSetupAfterStructureAlteration(action, index, number, logicRows, source) {
      var _this2 = this;

      var forceRefresh = arguments.length > 5 && arguments[5] !== undefined ? arguments[5] : true;

      if (this.settingsType === 'function') {
        // We need to run it on a next avaiable hook, because the TrimRows' `afterCreateRow` hook triggers after this one,
        // and it needs to be run to properly calculate the endpoint value.
        var beforeRenderCallback = function beforeRenderCallback() {
          _this2.hot.removeHook('beforeRender', beforeRenderCallback);

          return _this2.refreshAllEndpoints();
        };

        this.hot.addHookOnce('beforeRender', beforeRenderCallback);
        return;
      }

      var type = action.indexOf('row') > -1 ? 'row' : 'col';
      var multiplier = action.indexOf('remove') > -1 ? -1 : 1;
      var endpoints = this.getAllEndpoints();
      var rowMoving = action.indexOf('move_row') === 0;
      var placeOfAlteration = index;
      arrayEach(endpoints, function (val) {
        if (type === 'row' && val.destinationRow >= placeOfAlteration) {
          val.alterRowOffset = multiplier * number;
        }

        if (type === 'col' && val.destinationColumn >= placeOfAlteration) {
          val.alterColumnOffset = multiplier * number;
        }
      });
      this.resetAllEndpoints(endpoints, !rowMoving);

      if (rowMoving) {
        arrayEach(endpoints, function (endpoint) {
          _this2.extendEndpointRanges(endpoint, placeOfAlteration, logicRows[0], logicRows.length);

          _this2.recreatePhysicalRanges(endpoint);

          _this2.clearOffsetInformation(endpoint);
        });
      } else {
        arrayEach(endpoints, function (endpoint) {
          _this2.shiftEndpointCoordinates(endpoint, placeOfAlteration);
        });
      }

      if (forceRefresh) {
        this.refreshAllEndpoints();
      }
    }
    /**
     * Clear the offset information from the endpoint object.
     *
     * @private
     * @param {Object} endpoint And endpoint object.
     */

  }, {
    key: "clearOffsetInformation",
    value: function clearOffsetInformation(endpoint) {
      endpoint.alterRowOffset = void 0;
      endpoint.alterColumnOffset = void 0;
    }
    /**
     * Extend the row ranges for the provided endpoint.
     *
     * @private
     * @param {Object} endpoint The endpoint object.
     * @param {Number} placeOfAlteration Index of the row where the alteration takes place.
     * @param {Number} previousPosition Previous endpoint result position.
     * @param {Number} offset Offset generated by the alteration.
     */

  }, {
    key: "extendEndpointRanges",
    value: function extendEndpointRanges(endpoint, placeOfAlteration, previousPosition, offset) {
      arrayEach(endpoint.ranges, function (range) {
        // is a range, not a single row
        if (range[1]) {
          if (placeOfAlteration >= range[0] && placeOfAlteration <= range[1]) {
            if (previousPosition > range[1]) {
              range[1] += offset;
            } else if (previousPosition < range[0]) {
              range[0] -= offset;
            }
          } else if (previousPosition >= range[0] && previousPosition <= range[1]) {
            range[1] -= offset;

            if (placeOfAlteration <= range[0]) {
              range[0] += 1;
              range[1] += 1;
            }
          }
        }
      });
    }
    /**
     * Recreate the physical ranges for the provided endpoint. Used (for example) when a row gets moved and extends an existing range.
     *
     * @private
     * @param {Object} endpoint An endpoint object.
     */

  }, {
    key: "recreatePhysicalRanges",
    value: function recreatePhysicalRanges(endpoint) {
      var _this3 = this;

      var ranges = endpoint.ranges;
      var newRanges = [];
      var allIndexes = [];
      arrayEach(ranges, function (range) {
        var newRange = [];

        if (range[1]) {
          for (var i = range[0]; i <= range[1]; i++) {
            newRange.push(_this3.recordTranslator.toPhysicalRow(i));
          }
        } else {
          newRange.push(_this3.recordTranslator.toPhysicalRow(range[0]));
        }

        allIndexes.push(newRange);
      });
      arrayEach(allIndexes, function (range) {
        var newRange = [];
        arrayEach(range, function (coord, index) {
          if (index === 0) {
            newRange.push(coord);
          } else if (range[index] !== range[index - 1] + 1) {
            newRange.push(range[index - 1]);
            newRanges.push(newRange);
            newRange = [];
            newRange.push(coord);
          }

          if (index === range.length - 1) {
            newRange.push(coord);
            newRanges.push(newRange);
          }
        });
      });
      endpoint.ranges = newRanges;
    }
    /**
     * Shifts the endpoint coordinates by the defined offset.
     *
     * @private
     * @param {Object} endpoint Endpoint object.
     * @param {Number} offsetStartIndex Index of the performed change (if the change is located after the endpoint, nothing about the endpoint has to be changed.
     */

  }, {
    key: "shiftEndpointCoordinates",
    value: function shiftEndpointCoordinates(endpoint, offsetStartIndex) {
      if (endpoint.alterRowOffset && endpoint.alterRowOffset !== 0) {
        endpoint.destinationRow += endpoint.alterRowOffset || 0;
        arrayEach(endpoint.ranges, function (element) {
          arrayEach(element, function (subElement, j) {
            if (subElement >= offsetStartIndex) {
              element[j] += endpoint.alterRowOffset || 0;
            }
          });
        });
      } else if (endpoint.alterColumnOffset && endpoint.alterColumnOffset !== 0) {
        endpoint.destinationColumn += endpoint.alterColumnOffset || 0;
        endpoint.sourceColumn += endpoint.alterColumnOffset || 0;
      }
    }
    /**
     * Resets (removes) the endpoints from the table.
     *
     * @param {Array} endpoints Array containing the endpoints.
     * @param {Boolean} [useOffset=true] Use the cell offset value.
     */

  }, {
    key: "resetAllEndpoints",
    value: function resetAllEndpoints(endpoints) {
      var _this4 = this;

      var useOffset = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var endpointsArray = endpoints;
      this.cellsToSetCache = [];

      if (!endpointsArray) {
        endpointsArray = this.getAllEndpoints();
      }

      arrayEach(endpointsArray, function (value) {
        _this4.resetEndpointValue(value, useOffset);
      });
      this.hot.setDataAtCell(this.cellsToSetCache, 'ColumnSummary.reset');
      this.cellsToSetCache = [];
    }
    /**
     * Calculate and refresh all defined endpoints.
     */

  }, {
    key: "refreshAllEndpoints",
    value: function refreshAllEndpoints() {
      var _this5 = this;

      this.cellsToSetCache = [];
      arrayEach(this.getAllEndpoints(), function (value) {
        _this5.currentEndpoint = value;

        _this5.plugin.calculate(value);

        _this5.setEndpointValue(value, 'init');
      });
      this.currentEndpoint = null;
      this.hot.setDataAtCell(this.cellsToSetCache, 'ColumnSummary.reset');
      this.cellsToSetCache = [];
    }
    /**
     * Calculate and refresh endpoints only in the changed columns.
     *
     * @param {Array} changes Array of changes from the `afterChange` hook.
     */

  }, {
    key: "refreshChangedEndpoints",
    value: function refreshChangedEndpoints(changes) {
      var _this6 = this;

      var needToRefresh = [];
      this.cellsToSetCache = [];
      arrayEach(changes, function (value, key, changesObj) {
        // if nothing changed, dont update anything
        if ("".concat(value[2] || '') === "".concat(value[3])) {
          return;
        }

        arrayEach(_this6.getAllEndpoints(), function (endpoint, j) {
          if (_this6.hot.propToCol(changesObj[key][1]) === endpoint.sourceColumn && needToRefresh.indexOf(j) === -1) {
            needToRefresh.push(j);
          }
        });
      });
      arrayEach(needToRefresh, function (value) {
        _this6.refreshEndpoint(_this6.getEndpoint(value));
      });
      this.hot.setDataAtCell(this.cellsToSetCache, 'ColumnSummary.reset');
      this.cellsToSetCache = [];
    }
    /**
     * Calculate and refresh a single endpoint.
     *
     * @param {Object} endpoint Contains the endpoint information.
     */

  }, {
    key: "refreshEndpoint",
    value: function refreshEndpoint(endpoint) {
      this.currentEndpoint = endpoint;
      this.plugin.calculate(endpoint);
      this.setEndpointValue(endpoint);
      this.currentEndpoint = null;
    }
    /**
     * Reset the endpoint value.
     *
     * @param {Object} endpoint Contains the endpoint information.
     * @param {Boolean} [useOffset=true] Use the cell offset value.
     */

  }, {
    key: "resetEndpointValue",
    value: function resetEndpointValue(endpoint) {
      var useOffset = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : true;
      var alterRowOffset = endpoint.alterRowOffset || 0;
      var alterColOffset = endpoint.alterColumnOffset || 0;

      var _this$recordTranslato = this.recordTranslator.toVisual(endpoint.destinationRow, endpoint.destinationColumn),
          _this$recordTranslato2 = _slicedToArray(_this$recordTranslato, 2),
          visualRowIndex = _this$recordTranslato2[0],
          visualColumnIndex = _this$recordTranslato2[1]; // Clear the meta on the "old" indexes


      var cellMeta = this.hot.getCellMeta(visualRowIndex, visualColumnIndex);
      cellMeta.readOnly = false;
      cellMeta.className = '';
      this.cellsToSetCache.push([this.recordTranslator.toVisualRow(endpoint.destinationRow + (useOffset ? alterRowOffset : 0)), this.recordTranslator.toVisualColumn(endpoint.destinationColumn + (useOffset ? alterColOffset : 0)), '']);
    }
    /**
     * Set the endpoint value.
     *
     * @param {Object} endpoint Contains the endpoint information.
     * @param {String} [source] Source of the call information.
     * @param {Boolean} [render=false] `true` if it needs to render the table afterwards.
     */

  }, {
    key: "setEndpointValue",
    value: function setEndpointValue(endpoint, source) {
      var render = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : false;
      // We'll need the reversed offset values, because cellMeta will be shifted AGAIN afterwards.
      var reverseRowOffset = -1 * endpoint.alterRowOffset || 0;
      var reverseColOffset = -1 * endpoint.alterColumnOffset || 0;
      var visualEndpointRowIndex = this.getVisualRowIndex(endpoint.destinationRow);
      var cellMeta = this.hot.getCellMeta(this.getVisualRowIndex(endpoint.destinationRow + reverseRowOffset), endpoint.destinationColumn + reverseColOffset);

      if (visualEndpointRowIndex > this.hot.countRows() || endpoint.destinationColumn > this.hot.countCols()) {
        this.throwOutOfBoundsWarning();
        return;
      }

      if (source === 'init' || cellMeta.readOnly !== endpoint.readOnly) {
        cellMeta.readOnly = endpoint.readOnly;
        cellMeta.className = 'columnSummaryResult';
      }

      if (endpoint.roundFloat && !isNaN(endpoint.result)) {
        endpoint.result = endpoint.result.toFixed(endpoint.roundFloat);
      }

      if (render) {
        this.hot.setDataAtCell(visualEndpointRowIndex, endpoint.destinationColumn, endpoint.result, 'ColumnSummary.set');
      } else {
        this.cellsToSetCache.push([visualEndpointRowIndex, endpoint.destinationColumn, endpoint.result]);
      }

      endpoint.alterRowOffset = void 0;
      endpoint.alterColumnOffset = void 0;
    }
    /**
     * Get the visual row index for the provided row. Uses the `umodifyRow` hook.
     *
     * @private
     * @param {Number} row Row index.
     * @returns {Number}
     */

  }, {
    key: "getVisualRowIndex",
    value: function getVisualRowIndex(row) {
      return this.hot.runHooks('unmodifyRow', row, 'columnSummary');
    }
    /**
     * Get the visual column index for the provided column. Uses the `umodifyColumn` hook.
     *
     * @private
     * @param {Number} column Column index.
     * @returns {Number}
     */

  }, {
    key: "getVisualColumnIndex",
    value: function getVisualColumnIndex(column) {
      return this.hot.runHooks('unmodifyCol', column, 'columnSummary');
    }
    /**
     * Throw an error for the calculation range being out of boundaries.
     *
     * @private
     */

  }, {
    key: "throwOutOfBoundsWarning",
    value: function throwOutOfBoundsWarning() {
      warn('One of the  Column Summary plugins\' destination points you provided is beyond the table boundaries!');
    }
  }]);

  return Endpoints;
}();

export default Endpoints;