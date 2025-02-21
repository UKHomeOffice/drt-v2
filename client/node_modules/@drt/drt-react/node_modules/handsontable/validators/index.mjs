import { autocompleteValidator, VALIDATOR_TYPE as AUTOCOMPLETE_VALIDATOR } from "./autocompleteValidator/index.mjs";
import { dateValidator, VALIDATOR_TYPE as DATE_VALIDATOR } from "./dateValidator/index.mjs";
import { dropdownValidator, VALIDATOR_TYPE as DROPDOWN_VALIDATOR } from "./dropdownValidator/index.mjs";
import { numericValidator, VALIDATOR_TYPE as NUMERIC_VALIDATOR } from "./numericValidator/index.mjs";
import { timeValidator, VALIDATOR_TYPE as TIME_VALIDATOR } from "./timeValidator/index.mjs";
import { registerValidator } from "./registry.mjs";
/**
 * Registers all available validators.
 */
export function registerAllValidators() {
  registerValidator(autocompleteValidator);
  registerValidator(dropdownValidator);
  registerValidator(dateValidator);
  registerValidator(numericValidator);
  registerValidator(timeValidator);
}
export { autocompleteValidator, AUTOCOMPLETE_VALIDATOR, dropdownValidator, DROPDOWN_VALIDATOR, dateValidator, DATE_VALIDATOR, numericValidator, NUMERIC_VALIDATOR, timeValidator, TIME_VALIDATOR };
export { getRegisteredValidatorNames, getRegisteredValidators, getValidator, hasValidator, registerValidator } from "./registry.mjs";