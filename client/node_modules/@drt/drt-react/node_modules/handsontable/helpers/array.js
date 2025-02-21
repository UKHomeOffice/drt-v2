"use strict";

exports.__esModule = true;
exports.arrayAvg = arrayAvg;
exports.arrayEach = arrayEach;
exports.arrayFilter = arrayFilter;
exports.arrayFlatten = arrayFlatten;
exports.arrayMap = arrayMap;
exports.arrayMax = arrayMax;
exports.arrayMin = arrayMin;
exports.arrayReduce = arrayReduce;
exports.arraySum = arraySum;
exports.arrayUnique = arrayUnique;
exports.extendArray = extendArray;
exports.getDifferenceOfArrays = getDifferenceOfArrays;
exports.getIntersectionOfArrays = getIntersectionOfArrays;
exports.getUnionOfArrays = getUnionOfArrays;
exports.pivot = pivot;
exports.stringToArray = stringToArray;
exports.to2dArray = to2dArray;
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.filter.js");
/**
 * @param {Array} arr An array to process.
 */
function to2dArray(arr) {
  const ilen = arr.length;
  let i = 0;
  while (i < ilen) {
    arr[i] = [arr[i]];
    i += 1;
  }
}

/**
 * @param {Array} arr An array to extend.
 * @param {Array} extension The data to extend from.
 */
function extendArray(arr, extension) {
  const ilen = extension.length;
  let i = 0;
  while (i < ilen) {
    arr.push(extension[i]);
    i += 1;
  }
}

/**
 * @param {Array} arr An array to pivot.
 * @returns {Array}
 */
function pivot(arr) {
  const pivotedArr = [];
  if (!arr || arr.length === 0 || !arr[0] || arr[0].length === 0) {
    return pivotedArr;
  }
  const rowCount = arr.length;
  const colCount = arr[0].length;
  for (let i = 0; i < rowCount; i++) {
    for (let j = 0; j < colCount; j++) {
      if (!pivotedArr[j]) {
        pivotedArr[j] = [];
      }
      pivotedArr[j][i] = arr[i][j];
    }
  }
  return pivotedArr;
}

/**
 * A specialized version of `.reduce` for arrays without support for callback
 * shorthands and `this` binding.
 *
 * {@link https://github.com/lodash/lodash/blob/master/lodash.js}.
 *
 * @param {Array} array The array to iterate over.
 * @param {Function} iteratee The function invoked per iteration.
 * @param {*} [accumulator] The initial value.
 * @param {boolean} [initFromArray] Specify using the first element of `array` as the initial value.
 * @returns {*} Returns the accumulated value.
 */
function arrayReduce(array, iteratee, accumulator, initFromArray) {
  let index = -1;
  let iterable = array;
  let result = accumulator;
  if (!Array.isArray(array)) {
    iterable = Array.from(array);
  }
  const length = iterable.length;
  if (initFromArray && length) {
    index += 1;
    result = iterable[index];
  }
  index += 1;
  while (index < length) {
    result = iteratee(result, iterable[index], index, iterable);
    index += 1;
  }
  return result;
}

/**
 * A specialized version of `.filter` for arrays without support for callback
 * shorthands and `this` binding.
 *
 * {@link https://github.com/lodash/lodash/blob/master/lodash.js}.
 *
 * @param {Array} array The array to iterate over.
 * @param {Function} predicate The function invoked per iteration.
 * @returns {Array} Returns the new filtered array.
 */
function arrayFilter(array, predicate) {
  let index = 0;
  let iterable = array;
  if (!Array.isArray(array)) {
    iterable = Array.from(array);
  }
  const length = iterable.length;
  const result = [];
  let resIndex = -1;
  while (index < length) {
    const value = iterable[index];
    if (predicate(value, index, iterable)) {
      resIndex += 1;
      result[resIndex] = value;
    }
    index += 1;
  }
  return result;
}

/**
 * A specialized version of `.map` for arrays without support for callback
 * shorthands and `this` binding.
 *
 * @param {Array} array The array to iterate over.
 * @param {Function} iteratee The function invoked per iteration.
 * @returns {Array} Returns the new filtered array.
 */
function arrayMap(array, iteratee) {
  let index = 0;
  let iterable = array;
  if (!Array.isArray(array)) {
    iterable = Array.from(array);
  }
  const length = iterable.length;
  const result = [];
  let resIndex = -1;
  while (index < length) {
    const value = iterable[index];
    resIndex += 1;
    result[resIndex] = iteratee(value, index, iterable);
    index += 1;
  }
  return result;
}

/**
 * A specialized version of `.forEach` for arrays without support for callback
 * shorthands and `this` binding.
 *
 * {@link https://github.com/lodash/lodash/blob/master/lodash.js}.
 *
 * @param {Array|*} array The array to iterate over or an any element with implemented iterator protocol.
 * @param {Function} iteratee The function invoked per iteration.
 * @returns {Array} Returns `array`.
 */
function arrayEach(array, iteratee) {
  let index = 0;
  let iterable = array;
  if (!Array.isArray(array)) {
    iterable = Array.from(array);
  }
  const length = iterable.length;
  while (index < length) {
    if (iteratee(iterable[index], index, iterable) === false) {
      break;
    }
    index += 1;
  }
  return array;
}

/**
 * Calculate sum value for each item of the array.
 *
 * @param {Array} array The array to process.
 * @returns {number} Returns calculated sum value.
 */
function arraySum(array) {
  return arrayReduce(array, (a, b) => a + b, 0);
}

/**
 * Returns the highest value from an array. Can be array of numbers or array of strings.
 * NOTICE: Mixed values is not supported.
 *
 * @param {Array} array The array to process.
 * @returns {number} Returns the highest value from an array.
 */
function arrayMax(array) {
  return arrayReduce(array, (a, b) => a > b ? a : b, Array.isArray(array) ? array[0] : undefined);
}

/**
 * Returns the lowest value from an array. Can be array of numbers or array of strings.
 * NOTICE: Mixed values is not supported.
 *
 * @param {Array} array The array to process.
 * @returns {number} Returns the lowest value from an array.
 */
function arrayMin(array) {
  return arrayReduce(array, (a, b) => a < b ? a : b, Array.isArray(array) ? array[0] : undefined);
}

/**
 * Calculate average value for each item of the array.
 *
 * @param {Array} array The array to process.
 * @returns {number} Returns calculated average value.
 */
function arrayAvg(array) {
  if (!array.length) {
    return 0;
  }
  return arraySum(array) / array.length;
}

/**
 * Flatten multidimensional array.
 *
 * @param {Array} array Array of Arrays.
 * @returns {Array}
 */
function arrayFlatten(array) {
  return arrayReduce(array, (initial, value) => initial.concat(Array.isArray(value) ? arrayFlatten(value) : value), []);
}

/**
 * Unique values in the array.
 *
 * @param {Array} array The array to process.
 * @returns {Array}
 */
function arrayUnique(array) {
  const unique = [];
  arrayEach(array, value => {
    if (unique.indexOf(value) === -1) {
      unique.push(value);
    }
  });
  return unique;
}

/**
 * Differences from two or more arrays.
 *
 * @param {...Array} arrays Array of strings or array of numbers.
 * @returns {Array} Returns the difference between arrays.
 */
function getDifferenceOfArrays() {
  for (var _len = arguments.length, arrays = new Array(_len), _key = 0; _key < _len; _key++) {
    arrays[_key] = arguments[_key];
  }
  const [first, ...rest] = [...arrays];
  let filteredFirstArray = first;
  arrayEach(rest, array => {
    filteredFirstArray = filteredFirstArray.filter(value => !array.includes(value));
  });
  return filteredFirstArray;
}

/**
 * Intersection of two or more arrays.
 *
 * @param {...Array} arrays Array of strings or array of numbers.
 * @returns {Array} Returns elements that exists in every array.
 */
function getIntersectionOfArrays() {
  for (var _len2 = arguments.length, arrays = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
    arrays[_key2] = arguments[_key2];
  }
  const [first, ...rest] = [...arrays];
  let filteredFirstArray = first;
  arrayEach(rest, array => {
    filteredFirstArray = filteredFirstArray.filter(value => array.includes(value));
  });
  return filteredFirstArray;
}

/**
 * Union of two or more arrays.
 *
 * @param {...Array} arrays Array of strings or array of numbers.
 * @returns {Array} Returns the elements that exist in any of the arrays, without duplicates.
 */
function getUnionOfArrays() {
  for (var _len3 = arguments.length, arrays = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
    arrays[_key3] = arguments[_key3];
  }
  const [first, ...rest] = [...arrays];
  const set = new Set(first);
  arrayEach(rest, array => {
    arrayEach(array, value => {
      if (!set.has(value)) {
        set.add(value);
      }
    });
  });
  return Array.from(set);
}

/**
 * Convert a separated strings to an array of strings.
 *
 * @param {string} value A string of class name(s).
 * @param {string|RegExp} delimiter The pattern describing where each split should occur.
 * @returns {string[]} Returns array of string or empty array.
 */
function stringToArray(value) {
  let delimiter = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : ' ';
  return value.split(delimiter);
}