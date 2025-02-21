"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _actionsOnIndexes = require("./actionsOnIndexes");
exports.getDecreasedIndexes = _actionsOnIndexes.getDecreasedIndexes;
exports.getIncreasedIndexes = _actionsOnIndexes.getIncreasedIndexes;
var _indexesSequence = require("./indexesSequence");
var _physicallyIndexed = require("./physicallyIndexed");
const alterStrategies = new Map([['indexesSequence', {
  getListWithInsertedItems: _indexesSequence.getListWithInsertedItems,
  getListWithRemovedItems: _indexesSequence.getListWithRemovedItems
}], ['physicallyIndexed', {
  getListWithInsertedItems: _physicallyIndexed.getListWithInsertedItems,
  getListWithRemovedItems: _physicallyIndexed.getListWithRemovedItems
}]]);
const alterUtilsFactory = indexationStrategy => {
  if (alterStrategies.has(indexationStrategy) === false) {
    throw new Error(`Alter strategy with ID '${indexationStrategy}' does not exist.`);
  }
  return alterStrategies.get(indexationStrategy);
};
exports.alterUtilsFactory = alterUtilsFactory;