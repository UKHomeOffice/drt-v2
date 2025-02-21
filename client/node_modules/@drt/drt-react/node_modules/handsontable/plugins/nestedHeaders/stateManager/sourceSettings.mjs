import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { extend, isObject } from "../../../helpers/object.mjs";
import { arrayEach } from "../../../helpers/array.mjs";
import { normalizeSettings } from "./settingsNormalizer.mjs";
/**
 * List of properties which are configurable. That properties can be changed using public API.
 *
 * @type {string[]}
 */
export const HEADER_CONFIGURABLE_PROPS = ['label', 'collapsible'];

/**
 * The class manages and normalizes settings passed by the developer
 * into the nested headers plugin. The SourceSettings class is a
 * source of truth for tree builder (HeaderTree) module.
 *
 * @private
 * @class SourceSettings
 */
var _data = /*#__PURE__*/new WeakMap();
var _dataLength = /*#__PURE__*/new WeakMap();
var _columnsLimit = /*#__PURE__*/new WeakMap();
export default class SourceSettings {
  constructor() {
    /**
     * The normalized source data (normalized user-defined settings for nested headers).
     *
     * @private
     * @type {Array[]}
     */
    _classPrivateFieldInitSpec(this, _data, []);
    /**
     * The total length of the nested header layers.
     *
     * @private
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _dataLength, 0);
    /**
     * Columns count limit value trims source settings to that value. If columns
     * count limit intersects nested header, the header's colspan value is reduced
     * to keep the whole structure stable (trimmed precisely where the limit is set).
     *
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _columnsLimit, Infinity);
  }
  /**
   * Sets columns limit to the source settings will be trimmed. All headers which
   * overlap the column limit will be reduced to keep the structure solid.
   *
   * @param {number} columnsCount The number of columns to limit to.
   */
  setColumnsLimit(columnsCount) {
    _classPrivateFieldSet(_columnsLimit, this, columnsCount);
  }

  /**
   * Sets a new nested header configuration.
   *
   * @param {Array[]} [nestedHeadersSettings=[]] The user-defined nested headers settings.
   */
  setData() {
    let nestedHeadersSettings = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : [];
    _classPrivateFieldSet(_data, this, normalizeSettings(nestedHeadersSettings, _classPrivateFieldGet(_columnsLimit, this)));
    _classPrivateFieldSet(_dataLength, this, _classPrivateFieldGet(_data, this).length);
  }

  /**
   * Gets normalized source settings.
   *
   * @returns {Array[]}
   */
  getData() {
    return _classPrivateFieldGet(_data, this);
  }

  /**
   * Merges settings with current source settings.
   *
   * @param {object[]} additionalSettings An array of objects with `row`, `col` and additional
   *                                      properties to merge with current source settings.
   */
  mergeWith(additionalSettings) {
    arrayEach(additionalSettings, _ref => {
      let {
        row,
        col,
        ...rest
      } = _ref;
      const headerSettings = this.getHeaderSettings(row, col);
      if (headerSettings !== null) {
        extend(headerSettings, rest, HEADER_CONFIGURABLE_PROPS);
      }
    });
  }

  /**
   * Maps the current state with a callback. For each source settings the callback function
   * is called. If the function returns value that value is merged with the source settings.
   *
   * @param {Function} callback A function that is called for every header settings.
   *                            Each time the callback is called, the returned value extends
   *                            header settings.
   */
  map(callback) {
    arrayEach(_classPrivateFieldGet(_data, this), header => {
      arrayEach(header, headerSettings => {
        const propsToExtend = callback({
          ...headerSettings
        });
        if (isObject(propsToExtend)) {
          extend(headerSettings, propsToExtend, HEADER_CONFIGURABLE_PROPS);
        }
      });
    });
  }

  /**
   * Gets source column header settings for a specified header. The returned
   * object contains information about the header label, its colspan length,
   * or if it is hidden in the header renderers.
   *
   * @param {number} headerLevel Header level (0 = most distant to the table).
   * @param {number} columnIndex A visual column index.
   * @returns {object|null}
   */
  getHeaderSettings(headerLevel, columnIndex) {
    var _headersSettings$colu;
    if (headerLevel >= _classPrivateFieldGet(_dataLength, this) || headerLevel < 0) {
      return null;
    }
    const headersSettings = _classPrivateFieldGet(_data, this)[headerLevel];
    if (Array.isArray(headersSettings) === false || columnIndex >= headersSettings.length) {
      return null;
    }
    return (_headersSettings$colu = headersSettings[columnIndex]) !== null && _headersSettings$colu !== void 0 ? _headersSettings$colu : null;
  }

  /**
   * Gets source of column headers settings for specified headers. If the retrieved column
   * settings overlap the range "box" determined by "columnIndex" and "columnsLength"
   * the exception will be thrown.
   *
   * @param {number} headerLevel Header level (0 = most distant to the table).
   * @param {number} columnIndex A visual column index from which the settings will be extracted.
   * @param {number} [columnsLength=1] The number of columns involved in the extraction of settings.
   * @returns {object}
   */
  getHeadersSettings(headerLevel, columnIndex) {
    let columnsLength = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : 1;
    const headersSettingsChunks = [];
    if (headerLevel >= _classPrivateFieldGet(_dataLength, this) || headerLevel < 0) {
      return headersSettingsChunks;
    }
    const headersSettings = _classPrivateFieldGet(_data, this)[headerLevel];
    let currentLength = 0;
    for (let i = columnIndex; i < headersSettings.length; i++) {
      const headerSettings = headersSettings[i];
      if (headerSettings.isPlaceholder) {
        throw new Error('The first column settings cannot overlap the other header layers');
      }
      currentLength += headerSettings.colspan;
      headersSettingsChunks.push(headerSettings);
      if (headerSettings.colspan > 1) {
        i += headerSettings.colspan - 1;
      }

      // We met the current sum of the child colspans
      if (currentLength === columnsLength) {
        break;
      }
      // We exceeds the current sum of the child colspans, the last columns colspan overlaps the "columnsLength" length.
      if (currentLength > columnsLength) {
        throw new Error('The last column settings cannot overlap the other header layers');
      }
    }
    return headersSettingsChunks;
  }

  /**
   * Gets a total number of headers levels.
   *
   * @returns {number}
   */
  getLayersCount() {
    return _classPrivateFieldGet(_dataLength, this);
  }

  /**
   * Gets a total number of columns count.
   *
   * @returns {number}
   */
  getColumnsCount() {
    return _classPrivateFieldGet(_dataLength, this) > 0 ? _classPrivateFieldGet(_data, this)[0].length : 0;
  }

  /**
   * Clears the data.
   */
  clear() {
    _classPrivateFieldSet(_data, this, []);
    _classPrivateFieldSet(_dataLength, this, 0);
  }
}