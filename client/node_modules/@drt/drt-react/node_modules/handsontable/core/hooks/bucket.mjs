import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/es.array.to-sorted.js";
import "core-js/modules/es.set.difference.v2.js";
import "core-js/modules/es.set.intersection.v2.js";
import "core-js/modules/es.set.is-disjoint-from.v2.js";
import "core-js/modules/es.set.is-subset-of.v2.js";
import "core-js/modules/es.set.is-superset-of.v2.js";
import "core-js/modules/es.set.symmetric-difference.v2.js";
import "core-js/modules/es.set.union.v2.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.filter.js";
import "core-js/modules/esnext.iterator.find.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { REGISTERED_HOOKS } from "./constants.mjs";
/**
 * @typedef {object} HookEntry
 * @property {Function} callback The callback function.
 * @property {number} orderIndex The order index.
 * @property {boolean} runOnce Indicates if the hook should run only once.
 * @property {boolean} initialHook Indicates if it is an initial hook - which means that the hook
 * always stays at the same index position even after update.
 * @property {boolean} skip Indicates if the hook was removed.
 */
/**
 * The maximum number of hooks that can be skipped before the bucket is cleaned up.
 */
const MAX_SKIPPED_HOOKS_COUNT = 100;

/**
 * The class represents a collection that allows to manage hooks (add, remove).
 *
 * @class HooksBucket
 */
var _hooks = /*#__PURE__*/new WeakMap();
var _skippedHooksCount = /*#__PURE__*/new WeakMap();
var _needsSort = /*#__PURE__*/new WeakMap();
var _HooksBucket_brand = /*#__PURE__*/new WeakSet();
export class HooksBucket {
  constructor() {
    /**
     * Creates a initial collection for the provided hook name.
     *
     * @param {string} hookName The name of the hook.
     */
    _classPrivateMethodInitSpec(this, _HooksBucket_brand);
    /**
     * A map that stores hooks.
     *
     * @type {Map<string, HookEntry>}
     */
    _classPrivateFieldInitSpec(this, _hooks, new Map());
    /**
     * A map that stores the number of skipped hooks.
     */
    _classPrivateFieldInitSpec(this, _skippedHooksCount, new Map());
    /**
     * A set that stores hook names that need to be re-sorted.
     */
    _classPrivateFieldInitSpec(this, _needsSort, new Set());
    REGISTERED_HOOKS.forEach(hookName => _assertClassBrand(_HooksBucket_brand, this, _createHooksCollection).call(this, hookName));
  }

  /**
   * Gets all hooks for the provided hook name.
   *
   * @param {string} hookName The name of the hook.
   * @returns {HookEntry[]}
   */
  getHooks(hookName) {
    var _classPrivateFieldGet2;
    return (_classPrivateFieldGet2 = _classPrivateFieldGet(_hooks, this).get(hookName)) !== null && _classPrivateFieldGet2 !== void 0 ? _classPrivateFieldGet2 : [];
  }

  /**
   * Adds a new hook to the collection.
   *
   * @param {string} hookName The name of the hook.
   * @param {Function} callback The callback function to add.
   * @param {{ orderIndex?: number, runOnce?: boolean, initialHook?: boolean }} options The options object.
   */
  add(hookName, callback) {
    let options = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : {};
    if (!_classPrivateFieldGet(_hooks, this).has(hookName)) {
      _assertClassBrand(_HooksBucket_brand, this, _createHooksCollection).call(this, hookName);
      REGISTERED_HOOKS.push(hookName);
    }
    const hooks = _classPrivateFieldGet(_hooks, this).get(hookName);
    if (hooks.find(hook => hook.callback === callback)) {
      // adding the same hook twice is now silently ignored
      return;
    }
    const orderIndex = Number.isInteger(options.orderIndex) ? options.orderIndex : 0;
    const runOnce = !!options.runOnce;
    const initialHook = !!options.initialHook;
    let foundInitialHook = false;
    if (initialHook) {
      const initialHookEntry = hooks.find(hook => hook.initialHook);
      if (initialHookEntry) {
        initialHookEntry.callback = callback;
        foundInitialHook = true;
      }
    }
    if (!foundInitialHook) {
      hooks.push({
        callback,
        orderIndex,
        runOnce,
        initialHook,
        skip: false
      });
      let needsSort = _classPrivateFieldGet(_needsSort, this).has(hookName);
      if (!needsSort && orderIndex !== 0) {
        needsSort = true;
        _classPrivateFieldGet(_needsSort, this).add(hookName);
      }
      if (needsSort && hooks.length > 1) {
        _classPrivateFieldGet(_hooks, this).set(hookName, hooks.toSorted((a, b) => a.orderIndex - b.orderIndex));
      }
    }
  }

  /**
   * Checks if there are any hooks for the provided hook name.
   *
   * @param {string} hookName The name of the hook.
   * @returns {boolean}
   */
  has(hookName) {
    return _classPrivateFieldGet(_hooks, this).has(hookName) && _classPrivateFieldGet(_hooks, this).get(hookName).length > 0;
  }

  /**
   * Removes a hook from the collection. If the hook was found and removed,
   * the method returns `true`, otherwise `false`.
   *
   * @param {string} hookName The name of the hook.
   * @param {*} callback The callback function to remove.
   * @returns {boolean}
   */
  remove(hookName, callback) {
    if (!_classPrivateFieldGet(_hooks, this).has(hookName)) {
      return false;
    }
    const hooks = _classPrivateFieldGet(_hooks, this).get(hookName);
    const hookEntry = hooks.find(hook => hook.callback === callback);
    if (hookEntry) {
      let skippedHooksCount = _classPrivateFieldGet(_skippedHooksCount, this).get(hookName);
      hookEntry.skip = true;
      skippedHooksCount += 1;
      if (skippedHooksCount > MAX_SKIPPED_HOOKS_COUNT) {
        _classPrivateFieldGet(_hooks, this).set(hookName, hooks.filter(hook => !hook.skip));
        skippedHooksCount = 0;
      }
      _classPrivateFieldGet(_skippedHooksCount, this).set(hookName, skippedHooksCount);
      return true;
    }
    return false;
  }

  /**
   * Destroys the bucket.
   */
  destroy() {
    _classPrivateFieldGet(_hooks, this).clear();
    _classPrivateFieldGet(_skippedHooksCount, this).clear();
    _classPrivateFieldSet(_hooks, this, null);
    _classPrivateFieldSet(_skippedHooksCount, this, null);
  }
}
function _createHooksCollection(hookName) {
  _classPrivateFieldGet(_hooks, this).set(hookName, []);
  _classPrivateFieldGet(_skippedHooksCount, this).set(hookName, 0);
}