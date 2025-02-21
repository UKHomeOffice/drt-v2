"use strict";

exports.__esModule = true;
exports.default = typeFactory;
exports.EXPORT_TYPES = exports.TYPE_PDF = exports.TYPE_EXCEL = exports.TYPE_CSV = void 0;

var _csv = _interopRequireDefault(require("./types/csv"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _defineProperty(obj, key, value) { if (key in obj) { Object.defineProperty(obj, key, { value: value, enumerable: true, configurable: true, writable: true }); } else { obj[key] = value; } return obj; }

var TYPE_CSV = 'csv';
exports.TYPE_CSV = TYPE_CSV;
var TYPE_EXCEL = 'excel'; // TODO

exports.TYPE_EXCEL = TYPE_EXCEL;
var TYPE_PDF = 'pdf'; // TODO

exports.TYPE_PDF = TYPE_PDF;

var EXPORT_TYPES = _defineProperty({}, TYPE_CSV, _csv.default);

exports.EXPORT_TYPES = EXPORT_TYPES;

function typeFactory(type, dataProvider, options) {
  if (typeof EXPORT_TYPES[type] === 'function') {
    return new EXPORT_TYPES[type](dataProvider, options);
  }

  return null;
}