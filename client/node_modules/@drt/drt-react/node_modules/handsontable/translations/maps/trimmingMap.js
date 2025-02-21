"use strict";

exports.__esModule = true;
require("core-js/modules/es.array.push.js");
var _physicalIndexToValueMap = require("./physicalIndexToValueMap");
var _array = require("../../helpers/array");
/**
 * Map for storing mappings from an physical index to a boolean value. It stores information whether physical index is
 * NOT included in a dataset and skipped in the process of rendering.
 *
 * @class TrimmingMap
 */
class TrimmingMap extends _physicalIndexToValueMap.PhysicalIndexToValueMap {
  constructor() {
    let initValueOrFn = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : false;
    super(initValueOrFn);
  }

  /**
   * Get physical indexes which are trimmed.
   *
   * Note: Indexes marked as trimmed aren't included in a {@link DataMap} and aren't rendered.
   *
   * @returns {Array}
   */
  getTrimmedIndexes() {
    return (0, _array.arrayReduce)(this.getValues(), (indexesList, isTrimmed, physicalIndex) => {
      if (isTrimmed) {
        indexesList.push(physicalIndex);
      }
      return indexesList;
    }, []);
  }
}
exports.TrimmingMap = TrimmingMap;