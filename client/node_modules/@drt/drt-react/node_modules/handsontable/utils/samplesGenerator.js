"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _object = require("./../helpers/object");
var _number = require("./../helpers/number");
var _mixed = require("./../helpers/mixed");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @class SamplesGenerator
 */
class SamplesGenerator {
  /**
   * Number of samples to take of each value length.
   *
   * @type {number}
   */
  static get SAMPLE_COUNT() {
    return 3;
  }
  /**
   * Samples prepared for calculations.
   *
   * @type {Map}
   * @default {null}
   */

  constructor(dataFactory) {
    _defineProperty(this, "samples", null);
    /**
     * Function which give the data to collect samples.
     *
     * @type {Function}
     */
    _defineProperty(this, "dataFactory", null);
    /**
     * Custom number of samples to take of each value length.
     *
     * @type {number}
     * @default {null}
     */
    _defineProperty(this, "customSampleCount", null);
    /**
     * `true` if duplicate samples collection should be allowed, `false` otherwise.
     *
     * @type {boolean}
     * @default {false}
     */
    _defineProperty(this, "allowDuplicates", false);
    this.dataFactory = dataFactory;
  }

  /**
   * Get the sample count for this instance.
   *
   * @returns {number}
   */
  getSampleCount() {
    if (this.customSampleCount) {
      return this.customSampleCount;
    }
    return SamplesGenerator.SAMPLE_COUNT;
  }

  /**
   * Set the sample count.
   *
   * @param {number} sampleCount Number of samples to be collected.
   */
  setSampleCount(sampleCount) {
    this.customSampleCount = sampleCount;
  }

  /**
   * Set if the generator should accept duplicate values.
   *
   * @param {boolean} allowDuplicates `true` to allow duplicate values.
   */
  setAllowDuplicates(allowDuplicates) {
    this.allowDuplicates = allowDuplicates;
  }

  /**
   * Generate samples for row. You can control which area should be sampled by passing `rowRange` object and `colRange` object.
   *
   * @param {object|number} rowRange The rows range to generate the samples.
   * @param {object} colRange The column range to generate the samples.
   * @returns {object}
   */
  generateRowSamples(rowRange, colRange) {
    return this.generateSamples('row', colRange, rowRange);
  }

  /**
   * Generate samples for column. You can control which area should be sampled by passing `colRange` object and `rowRange` object.
   *
   * @param {object} colRange Column index.
   * @param {object} rowRange Column index.
   * @returns {object}
   */
  generateColumnSamples(colRange, rowRange) {
    return this.generateSamples('col', rowRange, colRange);
  }

  /**
   * Generate collection of samples.
   *
   * @param {string} type Type to generate. Can be `col` or `row`.
   * @param {object} range The range to generate the samples.
   * @param {object|number} specifierRange The range to generate the samples.
   * @returns {Map}
   */
  generateSamples(type, range, specifierRange) {
    const samples = new Map();
    const {
      from,
      to
    } = typeof specifierRange === 'number' ? {
      from: specifierRange,
      to: specifierRange
    } : specifierRange;
    (0, _number.rangeEach)(from, to, index => {
      const sample = this.generateSample(type, range, index);
      samples.set(index, sample);
    });
    return samples;
  }

  /**
   * Generate sample for specified type (`row` or `col`).
   *
   * @param {string} type Samples type `row` or `col`.
   * @param {object} range The range to generate the samples.
   * @param {number} specifierValue The range to generate the samples.
   * @returns {Map}
   */
  generateSample(type, range, specifierValue) {
    if (type !== 'row' && type !== 'col') {
      throw new Error('Unsupported sample type');
    }
    const samples = new Map();
    const computedKey = type === 'row' ? 'col' : 'row';
    const sampledValues = [];
    (0, _number.rangeEach)(range.from, range.to, index => {
      const data = type === 'row' ? this.dataFactory(specifierValue, index) : this.dataFactory(index, specifierValue);
      if (data === false) {
        return;
      }
      const {
        value,
        bundleSeed
      } = data;
      const hasCustomBundleSeed = typeof bundleSeed === 'string' && bundleSeed.length > 0;
      let seed;
      if (hasCustomBundleSeed) {
        seed = bundleSeed;
      } else if ((0, _object.isObject)(value)) {
        seed = `${Object.keys(value).length}`;
      } else if (Array.isArray(value)) {
        seed = `${value.length}`;
      } else {
        seed = `${(0, _mixed.stringify)(value).length}`;
      }
      if (!samples.has(seed)) {
        samples.set(seed, {
          needed: this.getSampleCount(),
          strings: []
        });
      }
      const sample = samples.get(seed);
      if (sample.needed) {
        const duplicate = sampledValues.indexOf(value) > -1;
        if (!duplicate || this.allowDuplicates || hasCustomBundleSeed) {
          sample.strings.push({
            value,
            [computedKey]: index
          });
          sampledValues.push(value);
          sample.needed -= 1;
        }
      }
    });
    return samples;
  }
}
var _default = exports.default = SamplesGenerator;