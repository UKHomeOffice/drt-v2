import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.set.difference.v2.js";
import "core-js/modules/es.set.intersection.v2.js";
import "core-js/modules/es.set.is-disjoint-from.v2.js";
import "core-js/modules/es.set.is-subset-of.v2.js";
import "core-js/modules/es.set.is-superset-of.v2.js";
import "core-js/modules/es.set.symmetric-difference.v2.js";
import "core-js/modules/es.set.union.v2.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { ChangesObserver } from "./observer.mjs";
import { arrayDiff } from "./utils.mjs";
/**
 * The ChangesObservable module is an object that represents a resource that provides
 * the ability to observe the changes that happened in the index map indexes during
 * the code running.
 *
 * @private
 * @class ChangesObservable
 */
var _observers = /*#__PURE__*/new WeakMap();
var _indexMatrix = /*#__PURE__*/new WeakMap();
var _currentIndexState = /*#__PURE__*/new WeakMap();
var _isMatrixIndexesInitialized = /*#__PURE__*/new WeakMap();
var _initialIndexValue = /*#__PURE__*/new WeakMap();
export class ChangesObservable {
  constructor() {
    let {
      initialIndexValue
    } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
    /**
     * The list of registered ChangesObserver instances.
     *
     * @type {ChangesObserver[]}
     */
    _classPrivateFieldInitSpec(this, _observers, new Set());
    /**
     * An array with default values that act as a base array that will be compared with
     * the last saved index state. The changes are generated and immediately send through
     * the newly created ChangesObserver object. Thanks to that, the observer initially has
     * all information about what indexes are currently changed.
     *
     * @type {Array}
     */
    _classPrivateFieldInitSpec(this, _indexMatrix, []);
    /**
     * An array that holds the indexes state that is currently valid. The value is changed on every
     * index mapper cache update.
     *
     * @type {Array}
     */
    _classPrivateFieldInitSpec(this, _currentIndexState, []);
    /**
     * The flag determines if the observable is initialized or not. Not initialized object creates
     * index matrix once while emitting new changes.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isMatrixIndexesInitialized, false);
    /**
     * The initial index value allows control from what value the index matrix array will be created.
     * Changing that value changes how the array diff generates the changes for the initial data
     * sent to the subscribers. For example, the changes can be triggered by detecting the changes
     * from `false` to `true` value or vice versa. Generally, it depends on which index map type
     * the Observable will work with. For "hiding" or "trimming" index types, it will be boolean
     * values. For various index maps, it can be anything, but I suspect that the most appropriate
     * initial value will be "undefined" in that case.
     *
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _initialIndexValue, false);
    _classPrivateFieldSet(_initialIndexValue, this, initialIndexValue !== null && initialIndexValue !== void 0 ? initialIndexValue : false);
  }

  /* eslint-disable jsdoc/require-description-complete-sentence */
  /**
   * Creates and returns a new instance of the ChangesObserver object. The resource
   * allows subscribing to the index changes that during the code running may change.
   * Changes are emitted as an array of the index change. Each change is represented
   * separately as an object with `op`, `index`, `oldValue`, and `newValue` props.
   *
   * For example:
   * ```
   * [
   *   { op: 'replace', index: 1, oldValue: false, newValue: true },
   *   { op: 'replace', index: 3, oldValue: false, newValue: true },
   *   { op: 'insert', index: 4, oldValue: false, newValue: true },
   * ]
   * // or when the new index map changes have less indexes
   * [
   *   { op: 'replace', index: 1, oldValue: false, newValue: true },
   *   { op: 'remove', index: 4, oldValue: false, newValue: true },
   * ]
   * ```
   *
   * @returns {ChangesObserver}
   */
  /* eslint-enable jsdoc/require-description-complete-sentence */
  createObserver() {
    const observer = new ChangesObserver();
    _classPrivateFieldGet(_observers, this).add(observer);
    observer.addLocalHook('unsubscribe', () => {
      _classPrivateFieldGet(_observers, this).delete(observer);
    });
    observer._writeInitialChanges(arrayDiff(_classPrivateFieldGet(_indexMatrix, this), _classPrivateFieldGet(_currentIndexState, this)));
    return observer;
  }

  /**
   * The method is an entry point for triggering new index map changes. Emitting the
   * changes triggers comparing algorithm which compares last saved state with a new
   * state. When there are some differences, the changes are sent to all subscribers.
   *
   * @param {Array} indexesState An array with index map state.
   */
  emit(indexesState) {
    let currentIndexState = _classPrivateFieldGet(_currentIndexState, this);
    if (!_classPrivateFieldGet(_isMatrixIndexesInitialized, this) || _classPrivateFieldGet(_indexMatrix, this).length !== indexesState.length) {
      if (indexesState.length === 0) {
        indexesState = new Array(currentIndexState.length).fill(_classPrivateFieldGet(_initialIndexValue, this));
      } else {
        _classPrivateFieldSet(_indexMatrix, this, new Array(indexesState.length).fill(_classPrivateFieldGet(_initialIndexValue, this)));
      }
      if (!_classPrivateFieldGet(_isMatrixIndexesInitialized, this)) {
        _classPrivateFieldSet(_isMatrixIndexesInitialized, this, true);
        currentIndexState = _classPrivateFieldGet(_indexMatrix, this);
      }
    }
    const changes = arrayDiff(currentIndexState, indexesState);
    _classPrivateFieldGet(_observers, this).forEach(observer => observer._write(changes));
    _classPrivateFieldSet(_currentIndexState, this, indexesState);
  }
}