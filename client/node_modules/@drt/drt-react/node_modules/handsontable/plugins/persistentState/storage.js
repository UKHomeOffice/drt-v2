"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.json.stringify.js");
var _array = require("../../helpers/array");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * @private
 * @class Storage
 */
class Storage {
  // eslint-disable-next-line no-restricted-globals
  constructor(prefix) {
    let rootWindow = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : window;
    /**
     * Reference to proper window.
     *
     * @type {Window}
     */
    _defineProperty(this, "rootWindow", void 0);
    /**
     * Prefix for key (id element).
     *
     * @type {string}
     */
    _defineProperty(this, "prefix", void 0);
    /**
     * Saved keys.
     *
     * @type {Array}
     */
    _defineProperty(this, "savedKeys", []);
    this.rootWindow = rootWindow;
    this.prefix = prefix;
    this.loadSavedKeys();
  }

  /**
   * Save data to localStorage.
   *
   * @param {string} key Key string.
   * @param {Mixed} value Value to save.
   */
  saveValue(key, value) {
    this.rootWindow.localStorage.setItem(`${this.prefix}_${key}`, JSON.stringify(value));
    if (this.savedKeys.indexOf(key) === -1) {
      this.savedKeys.push(key);
      this.saveSavedKeys();
    }
  }

  /**
   * Load data from localStorage.
   *
   * @param {string} key Key string.
   * @param {object} defaultValue Object containing the loaded data.
   *
   * @returns {object|undefined}
   */
  loadValue(key, defaultValue) {
    const itemKey = typeof key === 'undefined' ? defaultValue : key;
    const value = this.rootWindow.localStorage.getItem(`${this.prefix}_${itemKey}`);
    return value === null ? undefined : JSON.parse(value);
  }

  /**
   * Reset given data from localStorage.
   *
   * @param {string} key Key string.
   */
  reset(key) {
    this.rootWindow.localStorage.removeItem(`${this.prefix}_${key}`);
  }

  /**
   * Reset all data from localStorage.
   *
   */
  resetAll() {
    (0, _array.arrayEach)(this.savedKeys, (value, index) => {
      this.rootWindow.localStorage.removeItem(`${this.prefix}_${this.savedKeys[index]}`);
    });
    this.clearSavedKeys();
  }

  /**
   * Load and save all keys from localStorage.
   *
   * @private
   */
  loadSavedKeys() {
    const keysJSON = this.rootWindow.localStorage.getItem(`${this.prefix}__persistentStateKeys`);
    const keys = typeof keysJSON === 'string' ? JSON.parse(keysJSON) : undefined;
    this.savedKeys = keys || [];
  }

  /**
   * Save saved key in localStorage.
   *
   * @private
   */
  saveSavedKeys() {
    this.rootWindow.localStorage.setItem(`${this.prefix}__persistentStateKeys`, JSON.stringify(this.savedKeys));
  }

  /**
   * Clear saved key from localStorage.
   *
   * @private
   */
  clearSavedKeys() {
    this.savedKeys.length = 0;
    this.saveSavedKeys();
  }
}
var _default = exports.default = Storage;