import "core-js/modules/es.error.cause.js";
import { getDecreasedIndexes, getIncreasedIndexes } from "./actionsOnIndexes.mjs";
import { getListWithInsertedItems as sequenceStrategyInsert, getListWithRemovedItems as sequenceStrategyRemove } from "./indexesSequence.mjs";
import { getListWithInsertedItems as physicalStrategyInsert, getListWithRemovedItems as physicalStrategyRemove } from "./physicallyIndexed.mjs";
const alterStrategies = new Map([['indexesSequence', {
  getListWithInsertedItems: sequenceStrategyInsert,
  getListWithRemovedItems: sequenceStrategyRemove
}], ['physicallyIndexed', {
  getListWithInsertedItems: physicalStrategyInsert,
  getListWithRemovedItems: physicalStrategyRemove
}]]);
const alterUtilsFactory = indexationStrategy => {
  if (alterStrategies.has(indexationStrategy) === false) {
    throw new Error(`Alter strategy with ID '${indexationStrategy}' does not exist.`);
  }
  return alterStrategies.get(indexationStrategy);
};
export { getDecreasedIndexes, getIncreasedIndexes, alterUtilsFactory };