import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { MapCollection } from "./mapCollection.mjs";
import { arrayMap } from "../../helpers/array.mjs";
import { isDefined } from "../../helpers/mixed.mjs";
/**
 * Collection of maps. This collection aggregate maps with the same type of values. Values from the registered maps
 * can be used to calculate a single result for particular index.
 */
export class AggregatedCollection extends MapCollection {
  constructor(aggregationFunction, fallbackValue) {
    super();
    /**
     * List of merged values. Value for each index is calculated using values inside registered maps.
     *
     * @type {Array}
     */
    _defineProperty(this, "mergedValuesCache", []);
    /**
     * Function which do aggregation on the values for particular index.
     */
    _defineProperty(this, "aggregationFunction", void 0);
    /**
     * Fallback value when there is no calculated value for particular index.
     */
    _defineProperty(this, "fallbackValue", void 0);
    this.aggregationFunction = aggregationFunction;
    this.fallbackValue = fallbackValue;
  }

  /**
   * Get merged values for all indexes.
   *
   * @param {boolean} [readFromCache=true] Determine if read results from the cache.
   * @returns {Array}
   */
  getMergedValues() {
    let readFromCache = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : true;
    if (readFromCache === true) {
      return this.mergedValuesCache;
    }
    if (this.getLength() === 0) {
      return [];
    }

    // Below variable stores values for every particular map. Example describing situation when we have 2 registered maps,
    // with length equal to 5.
    //
    // +---------+---------------------------------------------+
    // |         |                  indexes                    |
    // +---------+---------------------------------------------+
    // |   maps  |     0    |   1   |    2  |   3   |    4     |
    // +---------+----------+-------+-------+-------+----------+
    // |    0    | [[ value,  value,  value,  value,  value ], |
    // |    1    | [  value,  value,  value,  value,  value ]] |
    // +---------+----------+-------+-------+-------+----------+
    const mapsValuesMatrix = arrayMap(this.get(), map => map.getValues());
    // Below variable stores values for every particular index. Example describing situation when we have 2 registered maps,
    // with length equal to 5.
    //
    // +---------+---------------------+
    // |         |         maps        |
    // +---------+---------------------+
    // | indexes |     0    |    1     |
    // +---------+----------+----------+
    // |    0    | [[ value,  value ], |
    // |    1    | [  value,  value ], |
    // |    2    | [  value,  value ], |
    // |    3    | [  value,  value ], |
    // |    4    | [  value,  value ]] |
    // +---------+----------+----------+
    const indexesValuesMatrix = [];
    const mapsLength = isDefined(mapsValuesMatrix[0]) && mapsValuesMatrix[0].length || 0;
    for (let index = 0; index < mapsLength; index += 1) {
      const valuesForIndex = [];
      for (let mapIndex = 0; mapIndex < this.getLength(); mapIndex += 1) {
        valuesForIndex.push(mapsValuesMatrix[mapIndex][index]);
      }
      indexesValuesMatrix.push(valuesForIndex);
    }
    return arrayMap(indexesValuesMatrix, this.aggregationFunction);
  }

  /**
   * Get merged value for particular index.
   *
   * @param {number} index Index for which we calculate single result.
   * @param {boolean} [readFromCache=true] Determine if read results from the cache.
   * @returns {*}
   */
  getMergedValueAtIndex(index, readFromCache) {
    const valueAtIndex = this.getMergedValues(readFromCache)[index];
    return isDefined(valueAtIndex) ? valueAtIndex : this.fallbackValue;
  }

  /**
   * Rebuild cache for the collection.
   */
  updateCache() {
    this.mergedValuesCache = this.getMergedValues(false);
  }
}