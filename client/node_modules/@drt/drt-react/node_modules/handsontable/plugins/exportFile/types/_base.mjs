import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { extend, clone } from "../../../helpers/object.mjs";
import { substitute } from "../../../helpers/string.mjs";
/**
 * @private
 */
class BaseType {
  /**
   * Default options.
   *
   * @returns {object}
   */
  static get DEFAULT_OPTIONS() {
    return {
      mimeType: 'text/plain',
      fileExtension: 'txt',
      filename: 'Handsontable [YYYY]-[MM]-[DD]',
      encoding: 'utf-8',
      bom: false,
      columnHeaders: false,
      rowHeaders: false,
      exportHiddenColumns: false,
      exportHiddenRows: false,
      range: []
    };
  }

  /**
   * Data provider.
   *
   * @type {DataProvider}
   */

  constructor(dataProvider, options) {
    _defineProperty(this, "dataProvider", void 0);
    /**
     * Format type class options.
     *
     * @type {object}
     */
    _defineProperty(this, "options", void 0);
    this.dataProvider = dataProvider;
    this.options = this._mergeOptions(options);
    this.dataProvider.setOptions(this.options);
  }

  /**
   * Merge options provided by users with defaults.
   *
   * @param {object} options An object with options to merge with.
   * @returns {object} Returns new options object.
   */
  _mergeOptions(options) {
    let _options = clone(this.constructor.DEFAULT_OPTIONS);
    const date = new Date();
    _options = extend(clone(BaseType.DEFAULT_OPTIONS), _options);
    _options = extend(_options, options);
    _options.filename = substitute(_options.filename, {
      YYYY: date.getFullYear(),
      MM: `${date.getMonth() + 1}`.padStart(2, '0'),
      DD: `${date.getDate()}`.padStart(2, '0')
    });
    return _options;
  }
}
export default BaseType;