"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.index-of");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.array.join");

require("core-js/modules/es.array.map");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.regexp.constructor");

require("core-js/modules/es.regexp.to-string");

require("core-js/modules/es.string.iterator");

require("core-js/modules/es.string.replace");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _array = require("../../../helpers/array");

var _mixed = require("../../../helpers/mixed");

var _base = _interopRequireDefault(require("./_base"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

var CHAR_CARRIAGE_RETURN = String.fromCharCode(13);
var CHAR_DOUBLE_QUOTES = String.fromCharCode(34);
var CHAR_LINE_FEED = String.fromCharCode(10);
/**
 * @plugin ExportFile
 * @private
 */

var Csv =
/*#__PURE__*/
function (_BaseType) {
  _inherits(Csv, _BaseType);

  function Csv() {
    _classCallCheck(this, Csv);

    return _possibleConstructorReturn(this, _getPrototypeOf(Csv).apply(this, arguments));
  }

  _createClass(Csv, [{
    key: "export",

    /**
     * Create string body in desired format.
     *
     * @return {String}
    */
    value: function _export() {
      var _this = this;

      var options = this.options;
      var data = this.dataProvider.getData();
      var columnHeaders = this.dataProvider.getColumnHeaders();
      var hasColumnHeaders = columnHeaders.length > 0;
      var rowHeaders = this.dataProvider.getRowHeaders();
      var hasRowHeaders = rowHeaders.length > 0;
      var result = options.bom ? String.fromCharCode(0xFEFF) : '';

      if (hasColumnHeaders) {
        columnHeaders = (0, _array.arrayMap)(columnHeaders, function (value) {
          return _this._escapeCell(value, true);
        });

        if (hasRowHeaders) {
          result += options.columnDelimiter;
        }

        result += columnHeaders.join(options.columnDelimiter);
        result += options.rowDelimiter;
      }

      (0, _array.arrayEach)(data, function (value, index) {
        if (index > 0) {
          result += options.rowDelimiter;
        }

        if (hasRowHeaders) {
          result += _this._escapeCell(rowHeaders[index]) + options.columnDelimiter;
        }

        result += value.map(function (cellValue) {
          return _this._escapeCell(cellValue);
        }).join(options.columnDelimiter);
      });
      return result;
    }
    /**
     * Escape cell value.
     *
     * @param {*} value Cell value.
     * @param {Boolean} [force=false] Indicates if cell value will be escaped forcefully.
     * @return {String}
     */

  }, {
    key: "_escapeCell",
    value: function _escapeCell(value) {
      var force = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : false;
      var escapedValue = (0, _mixed.stringify)(value);

      if (escapedValue !== '' && (force || escapedValue.indexOf(CHAR_CARRIAGE_RETURN) >= 0 || escapedValue.indexOf(CHAR_DOUBLE_QUOTES) >= 0 || escapedValue.indexOf(CHAR_LINE_FEED) >= 0 || escapedValue.indexOf(this.options.columnDelimiter) >= 0)) {
        escapedValue = escapedValue.replace(new RegExp('"', 'g'), '""');
        escapedValue = "\"".concat(escapedValue, "\"");
      }

      return escapedValue;
    }
  }], [{
    key: "DEFAULT_OPTIONS",

    /**
     * Default options for exporting CSV format.
     *
     * @returns {Object}
     */
    get: function get() {
      return {
        mimeType: 'text/csv',
        fileExtension: 'csv',
        bom: true,
        columnDelimiter: ',',
        rowDelimiter: '\r\n'
      };
    }
  }]);

  return Csv;
}(_base.default);

var _default = Csv;
exports.default = _default;