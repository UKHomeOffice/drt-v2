"use strict";

exports.__esModule = true;
require("core-js/modules/es.array.push.js");
var _physicalIndexToValueMap = require("./physicalIndexToValueMap");
var _array = require("../../helpers/array");
/**
 * Map for storing mappings from an physical index to a boolean value. It stores information whether physical index is
 * included in a dataset, but skipped in the process of rendering.
 *
 * @class HidingMap
 */
class HidingMap extends _physicalIndexToValueMap.PhysicalIndexToValueMap {
  constructor() {
    let initValueOrFn = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : false;
    super(initValueOrFn);
  }

  /**
   * Get physical indexes which are hidden.
   *
   * Note: Indexes marked as hidden are included in a {@link DataMap}, but aren't rendered.
   *
   * @returns {Array}
   */
  getHiddenIndexes() {
    return (0, _array.arrayReduce)(this.getValues(), (indexesList, isHidden, physicalIndex) => {
      if (isHidden) {
        indexesList.push(physicalIndex);
      }
      return indexesList;
    }, []);
  }
}
exports.HidingMap = HidingMap;