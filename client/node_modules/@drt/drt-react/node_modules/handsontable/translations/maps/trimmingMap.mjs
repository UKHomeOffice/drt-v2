import "core-js/modules/es.array.push.js";
import { PhysicalIndexToValueMap } from "./physicalIndexToValueMap.mjs";
import { arrayReduce } from "../../helpers/array.mjs";
/**
 * Map for storing mappings from an physical index to a boolean value. It stores information whether physical index is
 * NOT included in a dataset and skipped in the process of rendering.
 *
 * @class TrimmingMap
 */
export class TrimmingMap extends PhysicalIndexToValueMap {
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
    return arrayReduce(this.getValues(), (indexesList, isTrimmed, physicalIndex) => {
      if (isTrimmed) {
        indexesList.push(physicalIndex);
      }
      return indexesList;
    }, []);
  }
}