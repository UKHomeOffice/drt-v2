"use strict";

exports.__esModule = true;
require("core-js/modules/es.array.push.js");
var _array = require("./../helpers/array");
var _object = require("./../helpers/object");
const MIXIN_NAME = 'hooksRefRegisterer';

/**
 * Mixin object to extend objects functionality for auto registering hooks in an Handsontable instance.
 *
 * @type {object}
 */
const hooksRefRegisterer = {
  /**
   * Internal hooks storage.
   */
  _hooksStorage: Object.create(null),
  /**
   * Add hook to the collection.
   *
   * @param {string} key The hook name.
   * @param {Function} callback The hook callback.
   * @returns {object}
   */
  addHook(key, callback) {
    if (!this._hooksStorage[key]) {
      this._hooksStorage[key] = [];
    }
    this.hot.addHook(key, callback);
    this._hooksStorage[key].push(callback);
    return this;
  },
  /**
   * Remove all hooks listeners by hook name.
   *
   * @param {string} key The hook name.
   */
  removeHooksByKey(key) {
    (0, _array.arrayEach)(this._hooksStorage[key] || [], callback => {
      this.hot.removeHook(key, callback);
    });
  },
  /**
   * Clear all added hooks.
   */
  clearHooks() {
    (0, _object.objectEach)(this._hooksStorage, (callbacks, name) => this.removeHooksByKey(name));
    this._hooksStorage = {};
  }
};
(0, _object.defineGetter)(hooksRefRegisterer, 'MIXIN_NAME', MIXIN_NAME, {
  writable: false,
  enumerable: false
});
var _default = exports.default = hooksRefRegisterer;