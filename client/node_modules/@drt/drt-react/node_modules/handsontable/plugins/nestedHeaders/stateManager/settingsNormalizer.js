"use strict";

exports.__esModule = true;
exports.normalizeSettings = normalizeSettings;
require("core-js/modules/es.array.push.js");
var _array = require("../../../helpers/array");
var _object = require("../../../helpers/object");
var _mixed = require("../../../helpers/mixed");
var _utils = require("./utils");
/* eslint-disable jsdoc/require-description-complete-sentence */

/**
 * A function that normalizes user-defined settings into one predictable
 * structure. Currently, the developer can declare nested headers by passing
 * the following unstructured (and sometimes uncompleted) array.
 *   [
 *     [{ label: 'A1', colspan: 2 }],
 *     [{ label: true }, 'B2', 4],
 *     [],
 *   ]
 *
 * The normalization process equalizes the length of columns to each header
 * layers to the same length and generates object settings with a common shape.
 * So the above mentioned example will be normalized into this:
 *   [
 *     [
 *       { label: 'A1', colspan: 2, isHidden: false, ... },
 *       { label: '', colspan: 1, isHidden: true, ... },
 *       { label: '', colspan: 1, isHidden: false, ... },
 *     ],
 *     [
 *       { label: 'true', colspan: 1, isHidden: false, ... },
 *       { label: 'B2', colspan: 1, isHidden: false, ... },
 *       { label: '4', colspan: 1, isHidden: false, ... },
 *     ],
 *     [
 *       { label: '', colspan: 1, isHidden: false, ... },
 *       { label: '', colspan: 1, isHidden: false, ... },
 *       { label: '', colspan: 1, isHidden: false, ... },
 *     ],
 *   ]
 *
 * @param {Array[]} sourceSettings An array with defined nested headers settings.
 * @param {number} [columnsLimit=Infinity] A number of columns to which the structure
 *                                         will be trimmed. While trimming the colspan
 *                                         values are adjusted to preserve the original
 *                                         structure.
 * @returns {Array[]}
 */
function normalizeSettings(sourceSettings) {
  let columnsLimit = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : Infinity;
  const normalizedSettings = [];
  if (columnsLimit === 0) {
    return normalizedSettings;
  }

  // Normalize array items (header settings) into one shape - literal object with default props.
  (0, _array.arrayEach)(sourceSettings, headersSettings => {
    const columns = [];
    let columnIndex = 0;
    normalizedSettings.push(columns);
    (0, _array.arrayEach)(headersSettings, sourceHeaderSettings => {
      const headerSettings = (0, _utils.createDefaultHeaderSettings)();
      if ((0, _object.isObject)(sourceHeaderSettings)) {
        const {
          label,
          colspan,
          headerClassName
        } = sourceHeaderSettings;
        headerSettings.label = (0, _mixed.stringify)(label);
        if (typeof colspan === 'number' && colspan > 1) {
          headerSettings.colspan = colspan;
          headerSettings.origColspan = colspan;
        }
        if (typeof headerClassName === 'string') {
          headerSettings.headerClassNames = [...headerClassName.split(' ')];
        }
      } else {
        headerSettings.label = (0, _mixed.stringify)(sourceHeaderSettings);
      }
      columnIndex += headerSettings.origColspan;
      let cancelProcessing = false;
      if (columnIndex >= columnsLimit) {
        // Adjust the colspan value to not overlap the columns limit.
        headerSettings.colspan = headerSettings.origColspan - (columnIndex - columnsLimit);
        headerSettings.origColspan = headerSettings.colspan;
        cancelProcessing = true;
      }
      columns.push(headerSettings);
      if (headerSettings.colspan > 1) {
        for (let i = 0; i < headerSettings.colspan - 1; i++) {
          columns.push((0, _utils.createPlaceholderHeaderSettings)());
        }
      }
      return !cancelProcessing;
    });
  });
  const columnsLength = Math.max(...(0, _array.arrayMap)(normalizedSettings, headersSettings => headersSettings.length));

  // Normalize the length of each header layer to the same columns length.
  (0, _array.arrayEach)(normalizedSettings, headersSettings => {
    if (headersSettings.length < columnsLength) {
      const defaultSettings = (0, _array.arrayMap)(new Array(columnsLength - headersSettings.length), () => (0, _utils.createDefaultHeaderSettings)());
      headersSettings.splice(headersSettings.length, 0, ...defaultSettings);
    }
  });
  return normalizedSettings;
}