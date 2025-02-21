"use strict";

require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
exports.__esModule = true;
var _exportNames = {
  IndexMapper: true,
  getRegisteredMapsCounter: true,
  getIncreasedIndexes: true,
  getDecreasedIndexes: true,
  alterUtilsFactory: true
};
var _indexMapper = require("./indexMapper");
exports.IndexMapper = _indexMapper.IndexMapper;
var _mapCollection = require("./mapCollections/mapCollection");
exports.getRegisteredMapsCounter = _mapCollection.getRegisteredMapsCounter;
var _utils = require("./maps/utils");
exports.getIncreasedIndexes = _utils.getIncreasedIndexes;
exports.getDecreasedIndexes = _utils.getDecreasedIndexes;
exports.alterUtilsFactory = _utils.alterUtilsFactory;
var _maps = require("./maps");
Object.keys(_maps).forEach(function (key) {
  if (key === "default" || key === "__esModule") return;
  if (Object.prototype.hasOwnProperty.call(_exportNames, key)) return;
  if (key in exports && exports[key] === _maps[key]) return;
  exports[key] = _maps[key];
});