"use strict";

exports.__esModule = true;
exports.curry = curry;
exports.curryRight = curryRight;
exports.debounce = debounce;
exports.fastCall = fastCall;
exports.isFunction = isFunction;
exports.partial = partial;
exports.pipe = pipe;
exports.throttle = throttle;
exports.throttleAfterHits = throttleAfterHits;
var _array = require("./array");
var _mixed = require("./mixed");
/**
 * Checks if given variable is function.
 *
 * @param {*} func Variable to check.
 * @returns {boolean}
 */
function isFunction(func) {
  return typeof func === 'function';
}

/**
 * Creates throttle function that enforces a maximum number of times a function (`func`) can be called over time (`wait`).
 *
 * @param {Function} func Function to invoke.
 * @param {number} wait Delay in miliseconds.
 * @returns {Function}
 */
function throttle(func) {
  let wait = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 200;
  let lastCalled = 0;
  const result = {
    lastCallThrottled: true
  };
  let lastTimer = null;

  /**
   * @param {...*} args The list of arguments passed during the function invocation.
   * @returns {object}
   */
  function _throttle() {
    for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
      args[_key] = arguments[_key];
    }
    const stamp = Date.now();
    let needCall = false;
    result.lastCallThrottled = true;
    if (!lastCalled) {
      lastCalled = stamp;
      needCall = true;
    }
    const remaining = wait - (stamp - lastCalled);
    if (needCall) {
      result.lastCallThrottled = false;
      func.apply(this, args);
    } else {
      if (lastTimer) {
        clearTimeout(lastTimer);
      }
      lastTimer = setTimeout(() => {
        result.lastCallThrottled = false;
        func.apply(this, args);
        lastCalled = 0;
        lastTimer = undefined;
      }, remaining);
    }
    return result;
  }
  return _throttle;
}

/**
 * Creates throttle function that enforces a maximum number of times a function (`func`) can be called over
 * time (`wait`) after specified hits.
 *
 * @param {Function} func Function to invoke.
 * @param {number} wait Delay in miliseconds.
 * @param {number} hits Number of hits after throttling will be applied.
 * @returns {Function}
 */
function throttleAfterHits(func) {
  let wait = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 200;
  let hits = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : 10;
  const funcThrottle = throttle(func, wait);
  let remainHits = hits;

  /**
   *
   */
  function _clearHits() {
    remainHits = hits;
  }
  /**
   * @param {*} args The list of arguments passed during the function invocation.
   * @returns {*}
   */
  function _throttleAfterHits() {
    for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
      args[_key2] = arguments[_key2];
    }
    if (remainHits) {
      remainHits -= 1;
      return func.apply(this, args);
    }
    return funcThrottle.apply(this, args);
  }
  _throttleAfterHits.clearHits = _clearHits;
  return _throttleAfterHits;
}

/**
 * Creates debounce function that enforces a function (`func`) not be called again until a certain amount of time (`wait`)
 * has passed without it being called.
 *
 * @param {Function} func Function to invoke.
 * @param {number} wait Delay in milliseconds.
 * @returns {Function}
 */
function debounce(func) {
  let wait = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 200;
  let lastTimer = null;
  let result;

  /**
   * @param {*} args The list of arguments passed during the function invocation.
   * @returns {*}
   */
  function _debounce() {
    for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
      args[_key3] = arguments[_key3];
    }
    if (lastTimer) {
      clearTimeout(lastTimer);
    }
    lastTimer = setTimeout(() => {
      result = func.apply(this, args);
    }, wait);
    return result;
  }
  return _debounce;
}

/**
 * Creates the function that returns the result of calling the given functions. Result of the first function is passed to
 * the second as an argument and so on. Only first function in the chain can handle multiple arguments.
 *
 * @param {Function} functions Functions to compose.
 * @returns {Function}
 */
function pipe() {
  for (var _len4 = arguments.length, functions = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
    functions[_key4] = arguments[_key4];
  }
  const [firstFunc, ...restFunc] = functions;
  return function _pipe() {
    for (var _len5 = arguments.length, args = new Array(_len5), _key5 = 0; _key5 < _len5; _key5++) {
      args[_key5] = arguments[_key5];
    }
    return (0, _array.arrayReduce)(restFunc, (acc, fn) => fn(acc), firstFunc.apply(this, args));
  };
}

/**
 * Creates the function that returns the function with cached arguments.
 *
 * @param {Function} func Function to partialization.
 * @param {Array} params Function arguments to cache.
 * @returns {Function}
 */
function partial(func) {
  for (var _len6 = arguments.length, params = new Array(_len6 > 1 ? _len6 - 1 : 0), _key6 = 1; _key6 < _len6; _key6++) {
    params[_key6 - 1] = arguments[_key6];
  }
  return function _partial() {
    for (var _len7 = arguments.length, restParams = new Array(_len7), _key7 = 0; _key7 < _len7; _key7++) {
      restParams[_key7] = arguments[_key7];
    }
    return func.apply(this, params.concat(restParams));
  };
}

/**
 * Creates the functions that returns the function with cached arguments. If count if passed arguments will be matched
 * to the arguments defined in `func` then function will be invoked.
 * Arguments are added to the stack in direction from the left to the right.
 *
 * @example
 * ```
 * var replace = curry(function(find, replace, string) {
 *   return string.replace(find, replace);
 * });
 *
 * // returns function with bounded first argument
 * var replace = replace('foo')
 *
 * // returns replaced string - all arguments was passed so function was invoked
 * replace('bar', 'Some test with foo...');
 *
 * ```
 *
 * @param {Function} func Function to currying.
 * @returns {Function}
 */
function curry(func) {
  const argsLength = func.length;

  /**
   * @param {*} argsSoFar The list of arguments passed during the function invocation.
   * @returns {Function}
   */
  function given(argsSoFar) {
    return function _curry() {
      for (var _len8 = arguments.length, params = new Array(_len8), _key8 = 0; _key8 < _len8; _key8++) {
        params[_key8] = arguments[_key8];
      }
      const passedArgsSoFar = argsSoFar.concat(params);
      let result;
      if (passedArgsSoFar.length >= argsLength) {
        result = func.apply(this, passedArgsSoFar);
      } else {
        result = given(passedArgsSoFar);
      }
      return result;
    };
  }
  return given([]);
}

/**
 * Creates the functions that returns the function with cached arguments. If count if passed arguments will be matched
 * to the arguments defined in `func` then function will be invoked.
 * Arguments are added to the stack in direction from the right to the left.
 *
 * @example
 * ```
 * var replace = curry(function(find, replace, string) {
 *   return string.replace(find, replace);
 * });
 *
 * // returns function with bounded first argument
 * var replace = replace('Some test with foo...')
 *
 * // returns replaced string - all arguments was passed so function was invoked
 * replace('bar', 'foo');
 *
 * ```
 *
 * @param {Function} func Function to currying.
 * @returns {Function}
 */
function curryRight(func) {
  const argsLength = func.length;

  /**
   * @param {*} argsSoFar The list of arguments passed during the function invocation.
   * @returns {Function}
   */
  function given(argsSoFar) {
    return function _curry() {
      for (var _len9 = arguments.length, params = new Array(_len9), _key9 = 0; _key9 < _len9; _key9++) {
        params[_key9] = arguments[_key9];
      }
      const passedArgsSoFar = argsSoFar.concat(params.reverse());
      let result;
      if (passedArgsSoFar.length >= argsLength) {
        result = func.apply(this, passedArgsSoFar);
      } else {
        result = given(passedArgsSoFar);
      }
      return result;
    };
  }
  return given([]);
}

/**
 * Calls a function in the quickest way available.
 *
 * In contrast to the `apply()` method that passes arguments as an array,
 * the `call()` method passes arguments directly, to avoid garbage collection costs.
 *
 * @param {Function} func The function to call.
 * @param {*} context The value to use as `this` when calling the `func` function.
 * @param {*} [arg1] An argument passed to the `func` function.
 * @param {*} [arg2] An argument passed to `func` function.
 * @param {*} [arg3] An argument passed to `func` function.
 * @param {*} [arg4] An argument passed to `func` function.
 * @param {*} [arg5] An argument passed to `func` function.
 * @param {*} [arg6] An argument passed to `func` function.
 * @returns {*}
 */
function fastCall(func, context, arg1, arg2, arg3, arg4, arg5, arg6) {
  if ((0, _mixed.isDefined)(arg6)) {
    return func.call(context, arg1, arg2, arg3, arg4, arg5, arg6);
  } else if ((0, _mixed.isDefined)(arg5)) {
    return func.call(context, arg1, arg2, arg3, arg4, arg5);
  } else if ((0, _mixed.isDefined)(arg4)) {
    return func.call(context, arg1, arg2, arg3, arg4);
  } else if ((0, _mixed.isDefined)(arg3)) {
    return func.call(context, arg1, arg2, arg3);
  } else if ((0, _mixed.isDefined)(arg2)) {
    return func.call(context, arg1, arg2);
  } else if ((0, _mixed.isDefined)(arg1)) {
    return func.call(context, arg1);
  }
  return func.call(context);
}