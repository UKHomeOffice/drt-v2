"use strict";

exports.__esModule = true;
exports.condition = condition;
var C = _interopRequireWildcard(require("../../../i18n/constants"));
var _conditionRegisterer = require("../conditionRegisterer");
var _after = require("./date/after");
var _before = require("./date/before");
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const CONDITION_NAME = exports.CONDITION_NAME = 'between';

/**
 * @param {object} dataRow The object which holds and describes the single cell value.
 * @param {Array} inputValues An array of values to compare with.
 * @param {number} inputValues."0" The minimum value of the range.
 * @param {number} inputValues."1" The maximum value of the range.
 * @returns {boolean}
 */
function condition(dataRow, _ref) {
  let [from, to] = _ref;
  let fromValue = from;
  let toValue = to;
  if (dataRow.meta.type === 'numeric') {
    const _from = parseFloat(fromValue, 10);
    const _to = parseFloat(toValue, 10);
    fromValue = Math.min(_from, _to);
    toValue = Math.max(_from, _to);
  } else if (dataRow.meta.type === 'date') {
    const dateBefore = (0, _conditionRegisterer.getCondition)(_before.CONDITION_NAME, [toValue]);
    const dateAfter = (0, _conditionRegisterer.getCondition)(_after.CONDITION_NAME, [fromValue]);
    return dateBefore(dataRow) && dateAfter(dataRow);
  }
  return dataRow.value >= fromValue && dataRow.value <= toValue;
}
(0, _conditionRegisterer.registerCondition)(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_BETWEEN,
  inputsCount: 2,
  showOperators: true
});