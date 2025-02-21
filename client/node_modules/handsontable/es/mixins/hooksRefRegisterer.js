import { arrayEach } from './../helpers/array';
import { defineGetter, objectEach } from './../helpers/object';
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

    arrayEach(this._hooksStorage[key] || [], function (callback) {
      _this.hot.removeHook(key, callback);
    });
  },

  /**
   * Clear all added hooks.
   */
  clearHooks: function clearHooks() {
    var _this2 = this;

    objectEach(this._hooksStorage, function (callbacks, name) {
      return _this2.removeHooksByKey(name);
    });
    this._hooksStorage = {};
  }
};
defineGetter(hooksRefRegisterer, 'MIXIN_NAME', MIXIN_NAME, {
  writable: false,
  enumerable: false
});
export default hooksRefRegisterer;