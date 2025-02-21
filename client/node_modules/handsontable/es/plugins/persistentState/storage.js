import "core-js/modules/es.array.concat";
import "core-js/modules/es.array.index-of";

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { arrayEach } from './../../helpers/array';
/**
 * @class Storage
 * @plugin PersistentState
 */

var Storage =
/*#__PURE__*/
function () {
  // eslint-disable-next-line no-restricted-globals
  function Storage(prefix) {
    var rootWindow = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : window;

    _classCallCheck(this, Storage);

    /**
     * Reference to proper window.
     *
     * @type {Window}
     */
    this.rootWindow = rootWindow;
    /**
     * Prefix for key (id element).
     *
     * @type {String}
     */

    this.prefix = prefix;
    /**
     * Saved keys.
     *
     * @type {Array}
     */

    this.savedKeys = [];
    this.loadSavedKeys();
  }
  /**
   * Save data to localStorage.
   *
   * @param {String} key Key string.
   * @param {Mixed} value Value to save.
   */


  _createClass(Storage, [{
    key: "saveValue",
    value: function saveValue(key, value) {
      this.rootWindow.localStorage.setItem("".concat(this.prefix, "_").concat(key), JSON.stringify(value));

      if (this.savedKeys.indexOf(key) === -1) {
        this.savedKeys.push(key);
        this.saveSavedKeys();
      }
    }
    /**
     * Load data from localStorage.
     *
     * @param {String} key Key string.
     * @param {Object} defaultValue Object containing the loaded data.
     *
     * @returns {}
     */

  }, {
    key: "loadValue",
    value: function loadValue(key, defaultValue) {
      var itemKey = typeof key === 'undefined' ? defaultValue : key;
      var value = this.rootWindow.localStorage.getItem("".concat(this.prefix, "_").concat(itemKey));
      return value === null ? void 0 : JSON.parse(value);
    }
    /**
     * Reset given data from localStorage.
     *
     * @param {String} key Key string.
     */

  }, {
    key: "reset",
    value: function reset(key) {
      this.rootWindow.localStorage.removeItem("".concat(this.prefix, "_").concat(key));
    }
    /**
     * Reset all data from localStorage.
     *
     */

  }, {
    key: "resetAll",
    value: function resetAll() {
      var _this = this;

      arrayEach(this.savedKeys, function (value, index) {
        _this.rootWindow.localStorage.removeItem("".concat(_this.prefix, "_").concat(_this.savedKeys[index]));
      });
      this.clearSavedKeys();
    }
    /**
     * Load and save all keys from localStorage.
     *
     * @private
     */

  }, {
    key: "loadSavedKeys",
    value: function loadSavedKeys() {
      var keysJSON = this.rootWindow.localStorage.getItem("".concat(this.prefix, "__persistentStateKeys"));
      var keys = typeof keysJSON === 'string' ? JSON.parse(keysJSON) : void 0;
      this.savedKeys = keys || [];
    }
    /**
     * Save saved key in localStorage.
     *
     * @private
     */

  }, {
    key: "saveSavedKeys",
    value: function saveSavedKeys() {
      this.rootWindow.localStorage.setItem("".concat(this.prefix, "__persistentStateKeys"), JSON.stringify(this.savedKeys));
    }
    /**
     * Clear saved key from localStorage.
     *
     * @private
     */

  }, {
    key: "clearSavedKeys",
    value: function clearSavedKeys() {
      this.savedKeys.length = 0;
      this.saveSavedKeys();
    }
  }]);

  return Storage;
}();

export default Storage;