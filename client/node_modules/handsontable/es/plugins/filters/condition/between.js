import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.iterator";
import "core-js/modules/web.dom-collections.iterator";

function _slicedToArray(arr, i) { return _arrayWithHoles(arr) || _iterableToArrayLimit(arr, i) || _nonIterableRest(); }

function _nonIterableRest() { throw new TypeError("Invalid attempt to destructure non-iterable instance"); }

function _iterableToArrayLimit(arr, i) { if (!(Symbol.iterator in Object(arr) || Object.prototype.toString.call(arr) === "[object Arguments]")) { return; } var _arr = []; var _n = true; var _d = false; var _e = undefined; try { for (var _i = arr[Symbol.iterator](), _s; !(_n = (_s = _i.next()).done); _n = true) { _arr.push(_s.value); if (i && _arr.length === i) break; } } catch (err) { _d = true; _e = err; } finally { try { if (!_n && _i["return"] != null) _i["return"](); } finally { if (_d) throw _e; } } return _arr; }

function _arrayWithHoles(arr) { if (Array.isArray(arr)) return arr; }

import * as C from '../../../i18n/constants';
import { registerCondition, getCondition } from '../conditionRegisterer';
import { CONDITION_NAME as CONDITION_DATE_AFTER } from './date/after';
import { CONDITION_NAME as CONDITION_DATE_BEFORE } from './date/before';
export var CONDITION_NAME = 'between';
export function condition(dataRow, _ref) {
  var _ref2 = _slicedToArray(_ref, 2),
      from = _ref2[0],
      to = _ref2[1];

  var fromValue = from;
  var toValue = to;

  if (dataRow.meta.type === 'numeric') {
    var _from = parseFloat(fromValue, 10);

    var _to = parseFloat(toValue, 10);

    fromValue = Math.min(_from, _to);
    toValue = Math.max(_from, _to);
  } else if (dataRow.meta.type === 'date') {
    var dateBefore = getCondition(CONDITION_DATE_BEFORE, [toValue]);
    var dateAfter = getCondition(CONDITION_DATE_AFTER, [fromValue]);
    return dateBefore(dataRow) && dateAfter(dataRow);
  }

  return dataRow.value >= fromValue && dataRow.value <= toValue;
}
registerCondition(CONDITION_NAME, condition, {
  name: C.FILTERS_CONDITIONS_BETWEEN,
  inputsCount: 2,
  showOperators: true
});