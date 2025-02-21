"use strict";

exports.__esModule = true;
exports.registerAllValidators = registerAllValidators;
var _autocompleteValidator = require("./autocompleteValidator");
exports.autocompleteValidator = _autocompleteValidator.autocompleteValidator;
exports.AUTOCOMPLETE_VALIDATOR = _autocompleteValidator.VALIDATOR_TYPE;
var _dateValidator = require("./dateValidator");
exports.dateValidator = _dateValidator.dateValidator;
exports.DATE_VALIDATOR = _dateValidator.VALIDATOR_TYPE;
var _dropdownValidator = require("./dropdownValidator");
exports.dropdownValidator = _dropdownValidator.dropdownValidator;
exports.DROPDOWN_VALIDATOR = _dropdownValidator.VALIDATOR_TYPE;
var _numericValidator = require("./numericValidator");
exports.numericValidator = _numericValidator.numericValidator;
exports.NUMERIC_VALIDATOR = _numericValidator.VALIDATOR_TYPE;
var _timeValidator = require("./timeValidator");
exports.timeValidator = _timeValidator.timeValidator;
exports.TIME_VALIDATOR = _timeValidator.VALIDATOR_TYPE;
var _registry = require("./registry");
exports.registerValidator = _registry.registerValidator;
exports.getRegisteredValidatorNames = _registry.getRegisteredValidatorNames;
exports.getRegisteredValidators = _registry.getRegisteredValidators;
exports.getValidator = _registry.getValidator;
exports.hasValidator = _registry.hasValidator;
/**
 * Registers all available validators.
 */
function registerAllValidators() {
  (0, _registry.registerValidator)(_autocompleteValidator.autocompleteValidator);
  (0, _registry.registerValidator)(_dropdownValidator.dropdownValidator);
  (0, _registry.registerValidator)(_dateValidator.dateValidator);
  (0, _registry.registerValidator)(_numericValidator.numericValidator);
  (0, _registry.registerValidator)(_timeValidator.timeValidator);
}