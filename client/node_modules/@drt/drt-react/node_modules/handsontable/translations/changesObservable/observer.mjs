import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { mixin } from "../../helpers/object.mjs";
import localHooks from "../../mixins/localHooks.mjs";
/**
 * The ChangesObserver module is an object that represents a disposable resource
 * provided by the ChangesObservable module.
 *
 * @class ChangesObserver
 */
var _currentInitialChanges = /*#__PURE__*/new WeakMap();
export class ChangesObserver {
  constructor() {
    /**
     * The field holds initial changes that will be used to notify the callbacks added using
     * subscribe method. Regardless of the moment of listening for changes, the subscriber
     * will be notified once with all changes made before subscribing.
     *
     * @type {Array}
     */
    _classPrivateFieldInitSpec(this, _currentInitialChanges, []);
  }
  /**
   * Subscribes to the observer.
   *
   * @param {Function} callback A function that will be called when the new changes will appear.
   * @returns {ChangesObserver}
   */
  subscribe(callback) {
    this.addLocalHook('change', callback);
    this._write(_classPrivateFieldGet(_currentInitialChanges, this));
    return this;
  }

  /**
   * Unsubscribes all subscriptions. After the method call, the observer would not produce
   * any new events.
   *
   * @returns {ChangesObserver}
   */
  unsubscribe() {
    this.runLocalHooks('unsubscribe');
    this.clearLocalHooks();
    return this;
  }

  /**
   * The write method is executed by the ChangesObservable module. The module produces all
   * changes events that are distributed further by the observer.
   *
   * @private
   * @param {object} changes The chunk of changes produced by the ChangesObservable module.
   * @returns {ChangesObserver}
   */
  _write(changes) {
    if (changes.length > 0) {
      this.runLocalHooks('change', changes);
    }
    return this;
  }

  /**
   * The write method is executed by the ChangesObservable module. The module produces initial
   * changes that will be used to notify new subscribers.
   *
   * @private
   * @param {object} initialChanges The chunk of changes produced by the ChangesObservable module.
   */
  _writeInitialChanges(initialChanges) {
    _classPrivateFieldSet(_currentInitialChanges, this, initialChanges);
  }
}
mixin(ChangesObserver, localHooks);