import "core-js/modules/es.array.push.js";
import { PhysicalIndexToValueMap } from "./physicalIndexToValueMap.mjs";
import { arrayReduce } from "../../helpers/array.mjs";
/**
 * Map for storing mappings from an physical index to a boolean value. It stores information whether physical index is
 * included in a dataset, but skipped in the process of rendering.
 *
 * @class HidingMap
 */
export class HidingMap extends PhysicalIndexToValueMap {
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
    return arrayReduce(this.getValues(), (indexesList, isHidden, physicalIndex) => {
      if (isHidden) {
        indexesList.push(physicalIndex);
      }
      return indexesList;
    }, []);
  }
}