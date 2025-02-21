"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
var _object = require("../../helpers/object");
var _array = require("../../helpers/array");
var _predefinedItems = require("./predefinedItems");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * Predefined items class factory for menu items.
 *
 * @private
 * @class ItemsFactory
 */
class ItemsFactory {
  constructor(hotInstance) {
    let orderPattern = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : null;
    /**
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * @type {object}
     */
    _defineProperty(this, "predefinedItems", (0, _predefinedItems.predefinedItems)());
    /**
     * @type {Array}
     */
    _defineProperty(this, "defaultOrderPattern", void 0);
    this.hot = hotInstance;
    this.defaultOrderPattern = orderPattern;
  }

  /**
   * Set predefined items.
   *
   * @param {Array} predefinedItemsCollection Array of predefined items.
   */
  setPredefinedItems(predefinedItemsCollection) {
    const items = {};
    this.defaultOrderPattern.length = 0;
    (0, _object.objectEach)(predefinedItemsCollection, (value, key) => {
      let menuItemKey = '';
      if (value.name === _predefinedItems.SEPARATOR) {
        items[_predefinedItems.SEPARATOR] = value;
        menuItemKey = _predefinedItems.SEPARATOR;

        // Menu item added as a property to array
      } else if (isNaN(parseInt(key, 10))) {
        value.key = value.key === undefined ? key : value.key;
        items[key] = value;
        menuItemKey = value.key;
      } else {
        items[value.key] = value;
        menuItemKey = value.key;
      }
      this.defaultOrderPattern.push(menuItemKey);
    });
    this.predefinedItems = items;
  }

  /**
   * Get all menu items based on pattern.
   *
   * @param {Array|object|boolean} pattern Pattern which you can define by displaying menu items order. If `true` default
   *                                       pattern will be used.
   * @returns {Array}
   */
  getItems() {
    let pattern = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : null;
    return getItems(pattern, this.defaultOrderPattern, this.predefinedItems);
  }
}

/**
 * @param {object[]} itemsPattern The user defined menu items collection.
 * @param {object[]} defaultPattern The menu default items collection.
 * @param {object} items Additional options.
 * @returns {object[]} Returns parsed and merged menu items collection ready to render.
 */
exports.ItemsFactory = ItemsFactory;
function getItems() {
  let itemsPattern = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : null;
  let defaultPattern = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
  let items = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : {};
  const result = [];
  let pattern = itemsPattern;
  if (pattern && pattern.items) {
    pattern = pattern.items;
  } else if (!Array.isArray(pattern)) {
    pattern = defaultPattern;
  }
  if ((0, _object.isObject)(pattern)) {
    (0, _object.objectEach)(pattern, (value, key) => {
      let item = items[typeof value === 'string' ? value : key];
      if (!item) {
        item = value;
      }
      if ((0, _object.isObject)(value)) {
        (0, _object.extend)(item, value);
      } else if (typeof item === 'string') {
        item = {
          name: item
        };
      }
      if (item.key === undefined) {
        item.key = key;
      }
      result.push(item);
    });
  } else {
    (0, _array.arrayEach)(pattern, (name, key) => {
      let item = items[name];

      // Item deleted from settings `allowInsertRow: false` etc.
      if (!item && _predefinedItems.ITEMS.indexOf(name) >= 0) {
        return;
      }
      if (!item) {
        item = {
          name,
          key: `${key}`
        };
      }
      if ((0, _object.isObject)(name)) {
        (0, _object.extend)(item, name);
      }
      if (item.key === undefined) {
        item.key = key;
      }
      result.push(item);
    });
  }
  return result;
}