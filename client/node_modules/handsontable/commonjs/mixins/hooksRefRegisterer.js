"use strict";

exports.__esModule = true;
exports.default = void 0;

var _array = require("./../helpers/array");

var _object = require("./../helpers/object");

var MIXIN_NAME = 'hooksRefRegisterer';
/**
 * Mixin object to extend objects functionality for auto registering hooks in an Handsontable instance.
 *
 * @type {Object}
 */

var hooksRefRegisterer = {
  /**
   * Internal hooks storage.
   */
  _hooksStorage: Object.create(null),

  /**
   * Add hook to the collection.
   *
   * @param {String} key Hook name.
   * @param {Function} callback Hook callback
   * @returns {Object}
   */
  addHook: function addHook(key, callback) {
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
   * @param {String} key
   */
  removeHooksByKey: function removeHooksByKey(key) {
    var _this = this;

    (0, _array.arrayEach)(this._hooksStorage[key] || [], function (callback) {
      _this.hot.removeHook(key, callback);
    });
  },

  /**
   * Clear all added hooks.
   */
  clearHooks: function clearHooks() {
    var _this2 = this;

    (0, _object.objectEach)(this._hooksStorage, function (callbacks, name) {
      return _this2.removeHooksByKey(name);
    });
    this._hooksStorage = {};
  }
};
(0, _object.defineGetter)(hooksRefRegisterer, 'MIXIN_NAME', MIXIN_NAME, {
  writable: false,
  enumerable: false
});
var _default = hooksRefRegisterer;
exports.default = _default;