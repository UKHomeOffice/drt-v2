"use strict";

require("core-js/modules/es.string.pad-start");

exports.__esModule = true;
exports.default = void 0;

var _object = require("../../../helpers/object");

var _string = require("../../../helpers/string");

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * @plugin ExportFile
 * @private
 */
var BaseType =
/*#__PURE__*/
function () {
  _createClass(BaseType, null, [{
    key: "DEFAULT_OPTIONS",

    /**
     * Default options.
     *
     * @returns {Object}
     */
    get: function get() {
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
  }]);

  function BaseType(dataProvider, options) {
    _classCallCheck(this, BaseType);

    /**
     * Data provider.
     *
     * @type {DataProvider}
     */
    this.dataProvider = dataProvider;
    /**
     * Format type class options.
     *
     * @type {Object}
     */

    this.options = this._mergeOptions(options);
    this.dataProvider.setOptions(this.options);
  }
  /**
   * Merge options provided by users with defaults.
   *
   * @return {Object} Returns new options object.
   */


  _createClass(BaseType, [{
    key: "_mergeOptions",
    value: function _mergeOptions(options) {
      var _options = (0, _object.clone)(this.constructor.DEFAULT_OPTIONS);

      var date = new Date();
      _options = (0, _object.extend)((0, _object.clone)(BaseType.DEFAULT_OPTIONS), _options);
      _options = (0, _object.extend)(_options, options);
      _options.filename = (0, _string.substitute)(_options.filename, {
        YYYY: date.getFullYear(),
        MM: "".concat(date.getMonth() + 1).padStart(2, '0'),
        DD: "".concat(date.getDate()).padStart(2, '0')
      });
      return _options;
    }
  }]);

  return BaseType;
}();

var _default = BaseType;
exports.default = _default;