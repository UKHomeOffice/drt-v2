"use strict";

exports.__esModule = true;
exports.default = typeFactory;
var _csv = _interopRequireDefault(require("./types/csv"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const TYPE_CSV = exports.TYPE_CSV = 'csv';
const TYPE_EXCEL = exports.TYPE_EXCEL = 'excel'; // TODO
const TYPE_PDF = exports.TYPE_PDF = 'pdf'; // TODO

const EXPORT_TYPES = exports.EXPORT_TYPES = {
  [TYPE_CSV]: _csv.default
};

/**
 * @private
 * @param {string} type The exporter type.
 * @param {DataProvider} dataProvider The data provider.
 * @param {object} options Constructor options for exporter class.
 * @returns {BaseType|null}
 */
function typeFactory(type, dataProvider, options) {
  if (typeof EXPORT_TYPES[type] === 'function') {
    return new EXPORT_TYPES[type](dataProvider, options);
  }
  return null;
}