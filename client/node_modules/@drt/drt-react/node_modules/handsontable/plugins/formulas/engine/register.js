"use strict";

exports.__esModule = true;
exports.getRegisteredHotInstances = getRegisteredHotInstances;
exports.registerCustomFunctions = registerCustomFunctions;
exports.registerEngine = registerEngine;
exports.registerLanguage = registerLanguage;
exports.registerNamedExpressions = registerNamedExpressions;
exports.setupEngine = setupEngine;
exports.setupSheet = setupSheet;
exports.unregisterEngine = unregisterEngine;
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
require("core-js/modules/esnext.iterator.map.js");
var _staticRegister = _interopRequireDefault(require("../../../utils/staticRegister"));
var _mixed = require("../../../helpers/mixed");
var _templateLiteralTag = require("../../../helpers/templateLiteralTag");
var _console = require("../../../helpers/console");
var _object = require("../../../helpers/object");
var _formulas = require("../formulas");
var _settings = require("./settings");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * Prepares and returns the collection for the engine relationship with the HoT instances.
 *
 * @returns {Map}
 */
function getEngineRelationshipRegistry() {
  const registryKey = 'engine_relationship';
  const pluginStaticRegistry = (0, _staticRegister.default)(_formulas.PLUGIN_KEY);
  if (!pluginStaticRegistry.hasItem(registryKey)) {
    pluginStaticRegistry.register(registryKey, new Map());
  }
  return pluginStaticRegistry.getItem(registryKey);
}

/**
 * Prepares and returns the collection for the engine shared usage.
 *
 * @returns {Map}
 */
function getSharedEngineUsageRegistry() {
  const registryKey = 'shared_engine_usage';
  const pluginStaticRegistry = (0, _staticRegister.default)(_formulas.PLUGIN_KEY);
  if (!pluginStaticRegistry.hasItem(registryKey)) {
    pluginStaticRegistry.register(registryKey, new Map());
  }
  return pluginStaticRegistry.getItem(registryKey);
}

/**
 * Setups the engine instance. It either creates a new (possibly shared) engine instance, or attaches
 * the plugin to an already-existing instance.
 *
 * @param {Handsontable} hotInstance Handsontable instance.
 * @returns {null|object} Returns the engine instance if everything worked right and `null` otherwise.
 */
function setupEngine(hotInstance) {
  const hotSettings = hotInstance.getSettings();
  const pluginSettings = hotSettings[_formulas.PLUGIN_KEY];
  const engineConfigItem = pluginSettings === null || pluginSettings === void 0 ? void 0 : pluginSettings.engine;
  if (pluginSettings === true) {
    return null;
  }
  if ((0, _mixed.isUndefined)(engineConfigItem)) {
    return null;
  }

  // `engine.hyperformula` or `engine` is the engine class
  if (typeof engineConfigItem.hyperformula === 'function' || typeof engineConfigItem === 'function') {
    var _engineConfigItem$hyp;
    return registerEngine((_engineConfigItem$hyp = engineConfigItem.hyperformula) !== null && _engineConfigItem$hyp !== void 0 ? _engineConfigItem$hyp : engineConfigItem, hotSettings, hotInstance);

    // `engine` is the engine instance
  } else if (typeof engineConfigItem === 'object' && (0, _mixed.isUndefined)(engineConfigItem.hyperformula)) {
    const engineRelationship = getEngineRelationshipRegistry();
    const sharedEngineUsage = getSharedEngineUsageRegistry().get(engineConfigItem);
    if (!engineRelationship.has(engineConfigItem)) {
      engineRelationship.set(engineConfigItem, []);
    }
    engineRelationship.get(engineConfigItem).push(hotInstance);
    if (sharedEngineUsage) {
      sharedEngineUsage.push(hotInstance.guid);
    }
    if (!engineConfigItem.getConfig().licenseKey) {
      engineConfigItem.updateConfig({
        licenseKey: _settings.DEFAULT_LICENSE_KEY
      });
    }
    if (engineConfigItem.getConfig().leapYear1900 !== _settings.DEFAULT_SETTINGS.leapYear1900 || (0, _object.isObjectEqual)(engineConfigItem.getConfig().nullDate, _settings.DEFAULT_SETTINGS.nullDate) === false) {
      (0, _console.warn)((0, _templateLiteralTag.toSingleLine)`If you use HyperFormula with Handsontable, keep the default \`leapYear1900\` and \`nullDate\` 
      settings. Otherwise, HyperFormula's dates may not sync correctly with Handsontable's dates.`);
    }
    return engineConfigItem;
  }
  return null;
}

/**
 * Registers the engine in the global register and attaches the needed event listeners.
 *
 * @param {Function} engineClass The engine class.
 * @param {object} hotSettings The Handsontable settings.
 * @param {Handsontable} hotInstance Handsontable instance.
 * @returns {object} Returns the engine instance.
 */
function registerEngine(engineClass, hotSettings, hotInstance) {
  const pluginSettings = hotSettings[_formulas.PLUGIN_KEY];
  const engineSettings = (0, _settings.getEngineSettingsWithDefaultsAndOverrides)(hotSettings);
  const engineRegistry = getEngineRelationshipRegistry();
  const sharedEngineRegistry = getSharedEngineUsageRegistry();
  registerCustomFunctions(engineClass, pluginSettings.functions);
  registerLanguage(engineClass, pluginSettings.language);

  // Create instance
  const engineInstance = engineClass.buildEmpty(engineSettings);

  // Add it to global registry
  engineRegistry.set(engineInstance, [hotInstance]);
  sharedEngineRegistry.set(engineInstance, [hotInstance.guid]);
  registerNamedExpressions(engineInstance, pluginSettings.namedExpressions);

  // Add hooks needed for cross-referencing sheets
  engineInstance.on('sheetAdded', () => {
    engineInstance.rebuildAndRecalculate();
  });
  engineInstance.on('sheetRemoved', () => {
    engineInstance.rebuildAndRecalculate();
  });
  return engineInstance;
}

/**
 * Returns the list of the Handsontable instances linked to the specific engine instance.
 *
 * @param {object} engine The engine instance.
 * @returns {Map<number, Handsontable>} Returns Map with Handsontable instances.
 */
function getRegisteredHotInstances(engine) {
  var _engineRegistry$get;
  const engineRegistry = getEngineRelationshipRegistry();
  const hotInstances = engineRegistry.size === 0 ? [] : Array.from((_engineRegistry$get = engineRegistry.get(engine)) !== null && _engineRegistry$get !== void 0 ? _engineRegistry$get : []);
  return new Map(hotInstances.map(hot => [hot.getPlugin('formulas').sheetId, hot]));
}

/**
 * Removes the HOT instance from the global register's engine usage array, and if there are no HOT instances left,
 * unregisters the engine itself.
 *
 * @param {object} engine The engine instance.
 * @param {string} hotInstance The Handsontable instance.
 */
function unregisterEngine(engine, hotInstance) {
  if (engine) {
    const engineRegistry = getEngineRelationshipRegistry();
    const engineHotRelationship = engineRegistry.get(engine);
    const sharedEngineRegistry = getSharedEngineUsageRegistry();
    const sharedEngineUsage = sharedEngineRegistry.get(engine);
    if (engineHotRelationship && engineHotRelationship.includes(hotInstance)) {
      engineHotRelationship.splice(engineHotRelationship.indexOf(hotInstance), 1);
      if (engineHotRelationship.length === 0) {
        engineRegistry.delete(engine);
      }
    }
    if (sharedEngineUsage && sharedEngineUsage.includes(hotInstance.guid)) {
      sharedEngineUsage.splice(sharedEngineUsage.indexOf(hotInstance.guid), 1);
      if (sharedEngineUsage.length === 0) {
        sharedEngineRegistry.delete(engine);
        engine.destroy();
      }
    }
  }
}

/**
 * Registers the custom functions for the engine.
 *
 * @param {Function} engineClass The engine class.
 * @param {Array} customFunctions The custom functions array.
 */
function registerCustomFunctions(engineClass, customFunctions) {
  if (customFunctions) {
    customFunctions.forEach(func => {
      const {
        name,
        plugin,
        translations
      } = func;
      try {
        engineClass.registerFunction(name, plugin, translations);
      } catch (e) {
        (0, _console.warn)(e.message);
      }
    });
  }
}

/**
 * Registers the provided language for the engine.
 *
 * @param {Function} engineClass The engine class.
 * @param {object} languageSetting The engine's language object.
 */
function registerLanguage(engineClass, languageSetting) {
  if (languageSetting) {
    const {
      langCode
    } = languageSetting;
    try {
      engineClass.registerLanguage(langCode, languageSetting);
    } catch (e) {
      (0, _console.warn)(e.message);
    }
  }
}

/**
 * Registers the provided named expressions in the engine instance.
 *
 * @param {object} engineInstance The engine instance.
 * @param {Array} namedExpressions Array of the named expressions to be registered.
 */
function registerNamedExpressions(engineInstance, namedExpressions) {
  if (namedExpressions) {
    engineInstance.suspendEvaluation();
    namedExpressions.forEach(namedExp => {
      const {
        name,
        expression,
        scope,
        options
      } = namedExp;
      try {
        engineInstance.addNamedExpression(name, expression, scope, options);
      } catch (e) {
        (0, _console.warn)(e.message);
      }
    });
    engineInstance.resumeEvaluation();
  }
}

/**
 * Sets up a new sheet.
 *
 * @param {object} engineInstance The engine instance.
 * @param {string} sheetName The new sheet name.
 * @returns {*}
 */
function setupSheet(engineInstance, sheetName) {
  if ((0, _mixed.isUndefined)(sheetName) || !engineInstance.doesSheetExist(sheetName)) {
    sheetName = engineInstance.addSheet(sheetName);
  }
  return sheetName;
}