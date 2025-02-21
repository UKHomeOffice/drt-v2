import { registerCondition } from '../conditionRegisterer';
export var CONDITION_NAME = 'false';
export function condition() {
  return false;
}
registerCondition(CONDITION_NAME, condition, {
  name: 'False'
});