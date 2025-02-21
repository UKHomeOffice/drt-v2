import "core-js/modules/es.error.cause.js";
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { requestAnimationFrame, cancelAnimationFrame } from "./../helpers/feature.mjs";
/**
 * @class Interval
 */
var _timer = /*#__PURE__*/new WeakMap();
var _func = /*#__PURE__*/new WeakMap();
var _stopped = /*#__PURE__*/new WeakMap();
var _then = /*#__PURE__*/new WeakMap();
var _callback = /*#__PURE__*/new WeakMap();
var _Interval_brand = /*#__PURE__*/new WeakSet();
class Interval {
  static create(func, delay) {
    return new Interval(func, delay);
  }

  /**
   * Number of milliseconds that function should wait before next call.
   *
   * @type {number}
   */

  constructor(func, delay) {
    /**
     * Loop callback, fired on every animation frame.
     */
    _classPrivateMethodInitSpec(this, _Interval_brand);
    _defineProperty(this, "delay", void 0);
    /**
     * Animation frame request id.
     *
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _timer, null);
    /**
     * Function to invoke repeatedly.
     *
     * @type {Function}
     */
    _classPrivateFieldInitSpec(this, _func, void 0);
    /**
     * Flag which indicates if interval object was stopped.
     *
     * @type {boolean}
     * @default true
     */
    _classPrivateFieldInitSpec(this, _stopped, true);
    /**
     * Interval time (in milliseconds) of the last callback call.
     *
     * @type {number}
     */
    _classPrivateFieldInitSpec(this, _then, null);
    /**
     * Bounded function `func`.
     *
     * @type {Function}
     */
    _classPrivateFieldInitSpec(this, _callback, void 0);
    _classPrivateFieldSet(_func, this, func);
    this.delay = parseDelay(delay);
    _classPrivateFieldSet(_callback, this, () => _assertClassBrand(_Interval_brand, this, _callback2).call(this));
  }

  /**
   * Start loop.
   *
   * @returns {Interval}
   */
  start() {
    if (_classPrivateFieldGet(_stopped, this)) {
      _classPrivateFieldSet(_then, this, Date.now());
      _classPrivateFieldSet(_stopped, this, false);
      _classPrivateFieldSet(_timer, this, requestAnimationFrame(_classPrivateFieldGet(_callback, this)));
    }
    return this;
  }

  /**
   * Stop looping.
   *
   * @returns {Interval}
   */
  stop() {
    if (!_classPrivateFieldGet(_stopped, this)) {
      _classPrivateFieldSet(_stopped, this, true);
      cancelAnimationFrame(_classPrivateFieldGet(_timer, this));
      _classPrivateFieldSet(_timer, this, null);
    }
    return this;
  }
}
function _callback2() {
  _classPrivateFieldSet(_timer, this, requestAnimationFrame(_classPrivateFieldGet(_callback, this)));
  if (this.delay) {
    const now = Date.now();
    const elapsed = now - _classPrivateFieldGet(_then, this);
    if (elapsed > this.delay) {
      _classPrivateFieldSet(_then, this, now - elapsed % this.delay);
      _classPrivateFieldGet(_func, this).call(this);
    }
  } else {
    _classPrivateFieldGet(_func, this).call(this);
  }
}
export default Interval;

/**
 * Convert delay from string format to milliseconds.
 *
 * @param {number|string} delay The delay in FPS (frame per second) or number format.
 * @returns {number}
 */
export function parseDelay(delay) {
  let result = delay;
  if (typeof result === 'string' && /fps$/.test(result)) {
    result = 1000 / parseInt(result.replace('fps', '') || 0, 10);
  }
  return result;
}