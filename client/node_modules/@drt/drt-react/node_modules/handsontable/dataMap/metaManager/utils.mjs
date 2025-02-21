import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.set.difference.v2.js";
import "core-js/modules/es.set.intersection.v2.js";
import "core-js/modules/es.set.is-disjoint-from.v2.js";
import "core-js/modules/es.set.is-subset-of.v2.js";
import "core-js/modules/es.set.is-superset-of.v2.js";
import "core-js/modules/es.set.symmetric-difference.v2.js";
import "core-js/modules/es.set.union.v2.js";
import { hasOwnProperty, isObject, objectEach, inherit, extend } from "../../helpers/object.mjs";
import { getCellType } from "../../cellTypes/registry.mjs";
/**
 * Checks if the given property can be overwritten.
 *
 * @param {string} propertyName The property name to check.
 * @param {object} metaObject The current object meta settings.
 * @returns {boolean}
 */
function canBeOverwritten(propertyName, metaObject) {
  var _metaObject$_automati;
  if (propertyName === 'CELL_TYPE') {
    return false;
  }
  return ((_metaObject$_automati = metaObject._automaticallyAssignedMetaProps) === null || _metaObject$_automati === void 0 ? void 0 : _metaObject$_automati.has(propertyName)) || !hasOwnProperty(metaObject, propertyName);
}

/**
 * Expands "type" property of the meta object to single values. For example `type: 'numeric'` sets
 * "renderer", "editor", "validator" properties to specific functions designed for numeric values.
 * If "type" is passed as an object that object will be returned, excluding properties that
 * already exist in the "metaObject".
 *
 * The function utilizes `_automaticallyAssignedMetaProps` meta property that allows tracking what
 * properties are changed by the "type" expanding feature. That properties can be always overwritten by
 * the user.
 *
 * @param {object} metaObject The meta object.
 * @param {object} settings The settings object with the "type" setting.
 * @param {object} settingsToCompareWith The object to compare which properties need to be updated.
 */
export function extendByMetaType(metaObject, settings) {
  let settingsToCompareWith = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : metaObject;
  const validType = typeof settings.type === 'string' ? getCellType(settings.type) : settings.type;
  if (metaObject._automaticallyAssignedMetaProps) {
    objectEach(settings, (value, key) => void metaObject._automaticallyAssignedMetaProps.delete(key));
  }
  if (!isObject(validType)) {
    return;
  }
  if (settingsToCompareWith === metaObject && !metaObject._automaticallyAssignedMetaProps) {
    metaObject._automaticallyAssignedMetaProps = new Set();
  }
  const expandedType = {};
  objectEach(validType, (value, property) => {
    if (canBeOverwritten(property, settingsToCompareWith)) {
      var _metaObject$_automati2;
      expandedType[property] = value;
      (_metaObject$_automati2 = metaObject._automaticallyAssignedMetaProps) === null || _metaObject$_automati2 === void 0 || _metaObject$_automati2.add(property);
    }
  });
  extend(metaObject, expandedType);
}

/**
 * Creates new class which extends properties from TableMeta layer class.
 *
 * @param {TableMeta} TableMeta The TableMeta which the new ColumnMeta is created from.
 * @param {string[]} [conflictList] List of the properties which are conflicted with the column meta layer.
 *                                  Conflicted properties are overwritten by `undefined` value, to separate them
 *                                  from the TableMeta layer.
 * @returns {ColumnMeta} Returns constructor ready to initialize with `new` operator.
 */
export function columnFactory(TableMeta) {
  let conflictList = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
  // Do not use ES6 "class extends" syntax here. It seems that the babel produces code
  // which drastically decreases the performance of the ColumnMeta class creation.

  /**
   * Base "class" for column meta.
   */
  function ColumnMeta() {}
  inherit(ColumnMeta, TableMeta);

  // Clear conflict settings
  for (let i = 0; i < conflictList.length; i++) {
    ColumnMeta.prototype[conflictList[i]] = undefined;
  }
  return ColumnMeta;
}

/**
 * Helper which checks if the provided argument is an unsigned number.
 *
 * @param {*} value Value to check.
 * @returns {boolean}
 */
export function isUnsignedNumber(value) {
  return Number.isInteger(value) && value >= 0;
}

/**
 * Function which makes assertion by custom condition. Function throws an error when assertion doesn't meet the spec.
 *
 * @param {Function} condition Function with custom logic. The condition has to return boolean values.
 * @param {string} errorMessage String which describes assertion error.
 */
export function assert(condition, errorMessage) {
  if (!condition()) {
    throw new Error(`Assertion failed: ${errorMessage}`);
  }
}

/**
 * Check if given variable is null or undefined.
 *
 * @param {*} variable Variable to check.
 * @returns {boolean}
 */
export function isNullish(variable) {
  return variable === null || variable === undefined;
}