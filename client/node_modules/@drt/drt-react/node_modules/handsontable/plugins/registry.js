"use strict";

exports.__esModule = true;
exports.getPlugin = getPlugin;
exports.getPluginsNames = getPluginsNames;
exports.hasPlugin = hasPlugin;
exports.registerPlugin = registerPlugin;
require("core-js/modules/es.error.cause.js");
var _string = require("../helpers/string");
var _priorityMap = require("../utils/dataStructures/priorityMap");
var _uniqueMap = require("../utils/dataStructures/uniqueMap");
var _uniqueSet = require("../utils/dataStructures/uniqueSet");
/**
 * Utility to register plugins and common namespace for keeping the reference to all plugins classes.
 */

const ERROR_PLUGIN_REGISTERED = pluginName => `There is already registered "${pluginName}" plugin.`;
const ERROR_PRIORITY_REGISTERED = priority => `There is already registered plugin on priority "${priority}".`;
const ERROR_PRIORITY_NAN = priority => `The priority "${priority}" is not a number.`;

/**
 * Stores plugins' names' queue with their priorities.
 */
const priorityPluginsQueue = (0, _priorityMap.createPriorityMap)({
  errorPriorityExists: ERROR_PRIORITY_REGISTERED,
  errorPriorityNaN: ERROR_PRIORITY_NAN
});
/**
 * Stores plugins names' queue by registration order.
 */
const uniquePluginsQueue = (0, _uniqueSet.createUniqueSet)({
  errorItemExists: ERROR_PLUGIN_REGISTERED
});
/**
 * Stores plugins references between their name and class.
 */
const uniquePluginsList = (0, _uniqueMap.createUniqueMap)({
  errorIdExists: ERROR_PLUGIN_REGISTERED
});

/**
 * Gets registered plugins' names in the following order:
 * 1) Plugins registered with a defined priority attribute, in the ascending order of priority.
 * 2) Plugins registered without a defined priority attribute, in the registration order.
 *
 * @returns {string[]}
 */
function getPluginsNames() {
  return [...priorityPluginsQueue.getItems(), ...uniquePluginsQueue.getItems()];
}

/**
 * Gets registered plugin's class based on the given name.
 *
 * @param {string} pluginName Plugin's name.
 * @returns {BasePlugin}
 */
function getPlugin(pluginName) {
  const unifiedPluginName = (0, _string.toUpperCaseFirst)(pluginName);
  return uniquePluginsList.getItem(unifiedPluginName);
}

/**
 * Checks if the plugin under the name is already registered.
 *
 * @param {string} pluginName Plugin's name.
 * @returns {boolean}
 */
function hasPlugin(pluginName) {
  /* eslint-disable no-unneeded-ternary */
  return getPlugin(pluginName) ? true : false;
}

/**
 * Registers plugin under the given name only once.
 *
 * @param {string|Function} pluginName The plugin name or plugin class.
 * @param {Function} [pluginClass] The plugin class.
 * @param {number} [priority] The plugin priority.
 */
function registerPlugin(pluginName, pluginClass, priority) {
  [pluginName, pluginClass, priority] = unifyPluginArguments(pluginName, pluginClass, priority);
  if (getPlugin(pluginName) === undefined) {
    _registerPlugin(pluginName, pluginClass, priority);
  }
}

/**
 * Registers plugin under the given name.
 *
 * @param {string|Function} pluginName The plugin name or plugin class.
 * @param {Function} [pluginClass] The plugin class.
 * @param {number} [priority] The plugin priority.
 */
function _registerPlugin(pluginName, pluginClass, priority) {
  const unifiedPluginName = (0, _string.toUpperCaseFirst)(pluginName);
  if (uniquePluginsList.hasItem(unifiedPluginName)) {
    throw new Error(ERROR_PLUGIN_REGISTERED(unifiedPluginName));
  }
  if (priority === undefined) {
    uniquePluginsQueue.addItem(unifiedPluginName);
  } else {
    priorityPluginsQueue.addItem(priority, unifiedPluginName);
  }
  uniquePluginsList.addItem(unifiedPluginName, pluginClass);
}

/**
 * Unifies arguments to register the plugin.
 *
 * @param {string|Function} pluginName The plugin name or plugin class.
 * @param {Function} [pluginClass] The plugin class.
 * @param {number} [priority] The plugin priority.
 * @returns {Array}
 */
function unifyPluginArguments(pluginName, pluginClass, priority) {
  if (typeof pluginName === 'function') {
    pluginClass = pluginName;
    pluginName = pluginClass.PLUGIN_KEY;
    priority = pluginClass.PLUGIN_PRIORITY;
  }
  return [pluginName, pluginClass, priority];
}