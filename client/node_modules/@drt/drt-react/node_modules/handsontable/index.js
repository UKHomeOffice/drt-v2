"use strict";

exports.__esModule = true;
var _base = _interopRequireWildcard(require("./base"));
exports.CellCoords = _base.CellCoords;
exports.CellRange = _base.CellRange;
var _registry = require("./registry");
var _eventManager = _interopRequireWildcard(require("./eventManager"));
var _translations = require("./translations");
var _jquery = _interopRequireDefault(require("./helpers/wrappers/jquery"));
var _ghostTable = _interopRequireDefault(require("./utils/ghostTable"));
var parseTableHelpers = _interopRequireWildcard(require("./utils/parseTable"));
var arrayHelpers = _interopRequireWildcard(require("./helpers/array"));
var browserHelpers = _interopRequireWildcard(require("./helpers/browser"));
var dataHelpers = _interopRequireWildcard(require("./helpers/data"));
var dateHelpers = _interopRequireWildcard(require("./helpers/date"));
var featureHelpers = _interopRequireWildcard(require("./helpers/feature"));
var functionHelpers = _interopRequireWildcard(require("./helpers/function"));
var mixedHelpers = _interopRequireWildcard(require("./helpers/mixed"));
var numberHelpers = _interopRequireWildcard(require("./helpers/number"));
var objectHelpers = _interopRequireWildcard(require("./helpers/object"));
var stringHelpers = _interopRequireWildcard(require("./helpers/string"));
var unicodeHelpers = _interopRequireWildcard(require("./helpers/unicode"));
var domHelpers = _interopRequireWildcard(require("./helpers/dom/element"));
var domEventHelpers = _interopRequireWildcard(require("./helpers/dom/event"));
var _registry2 = require("./editors/registry");
var _registry3 = require("./renderers/registry");
var _registry4 = require("./validators/registry");
var _registry5 = require("./cellTypes/registry");
var _registry6 = require("./plugins/registry");
var _base2 = require("./plugins/base");
var _Handsontable$cellTyp, _Handsontable$editors, _Handsontable$rendere, _Handsontable$validat, _Handsontable$plugins;
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
(0, _registry.registerAllModules)();
(0, _jquery.default)(_base.default);

// TODO: Remove this exports after rewrite tests about this module
_base.default.__GhostTable = _ghostTable.default;
_base.default._getListenersCounter = _eventManager.getListenersCounter; // For MemoryLeak tests
_base.default._getRegisteredMapsCounter = _translations.getRegisteredMapsCounter; // For MemoryLeak tests
_base.default.EventManager = _eventManager.default;

// Export all helpers to the Handsontable object
const HELPERS = [arrayHelpers, browserHelpers, dataHelpers, dateHelpers, featureHelpers, functionHelpers, mixedHelpers, numberHelpers, objectHelpers, stringHelpers, unicodeHelpers, parseTableHelpers];
const DOM = [domHelpers, domEventHelpers];
_base.default.helper = {};
_base.default.dom = {};

// Fill general helpers.
arrayHelpers.arrayEach(HELPERS, helper => {
  arrayHelpers.arrayEach(Object.getOwnPropertyNames(helper), key => {
    if (key.charAt(0) !== '_') {
      _base.default.helper[key] = helper[key];
    }
  });
});

// Fill DOM helpers.
arrayHelpers.arrayEach(DOM, helper => {
  arrayHelpers.arrayEach(Object.getOwnPropertyNames(helper), key => {
    if (key.charAt(0) !== '_') {
      _base.default.dom[key] = helper[key];
    }
  });
});

// Export cell types.
_base.default.cellTypes = (_Handsontable$cellTyp = _base.default.cellTypes) !== null && _Handsontable$cellTyp !== void 0 ? _Handsontable$cellTyp : {};
arrayHelpers.arrayEach((0, _registry5.getRegisteredCellTypeNames)(), cellTypeName => {
  _base.default.cellTypes[cellTypeName] = (0, _registry5.getCellType)(cellTypeName);
});
_base.default.cellTypes.registerCellType = _registry5.registerCellType;
_base.default.cellTypes.getCellType = _registry5.getCellType;

// Export all registered editors from the Handsontable.
_base.default.editors = (_Handsontable$editors = _base.default.editors) !== null && _Handsontable$editors !== void 0 ? _Handsontable$editors : {};
arrayHelpers.arrayEach((0, _registry2.getRegisteredEditorNames)(), editorName => {
  _base.default.editors[`${stringHelpers.toUpperCaseFirst(editorName)}Editor`] = (0, _registry2.getEditor)(editorName);
});
_base.default.editors.registerEditor = _registry2.registerEditor;
_base.default.editors.getEditor = _registry2.getEditor;

// Export all registered renderers from the Handsontable.
_base.default.renderers = (_Handsontable$rendere = _base.default.renderers) !== null && _Handsontable$rendere !== void 0 ? _Handsontable$rendere : {};
arrayHelpers.arrayEach((0, _registry3.getRegisteredRendererNames)(), rendererName => {
  const renderer = (0, _registry3.getRenderer)(rendererName);
  if (rendererName === 'base') {
    _base.default.renderers.cellDecorator = renderer;
  }
  _base.default.renderers[`${stringHelpers.toUpperCaseFirst(rendererName)}Renderer`] = renderer;
});
_base.default.renderers.registerRenderer = _registry3.registerRenderer;
_base.default.renderers.getRenderer = _registry3.getRenderer;

// Export all registered validators from the Handsontable.
_base.default.validators = (_Handsontable$validat = _base.default.validators) !== null && _Handsontable$validat !== void 0 ? _Handsontable$validat : {};
arrayHelpers.arrayEach((0, _registry4.getRegisteredValidatorNames)(), validatorName => {
  _base.default.validators[`${stringHelpers.toUpperCaseFirst(validatorName)}Validator`] = (0, _registry4.getValidator)(validatorName);
});
_base.default.validators.registerValidator = _registry4.registerValidator;
_base.default.validators.getValidator = _registry4.getValidator;

// Export all registered plugins from the Handsontable.
// Make sure to initialize the plugin dictionary as an empty object. Otherwise, while
// transpiling the files into ES and CommonJS format, the injected CoreJS helper
// `import "core-js/modules/es.object.get-own-property-names";` won't be processed
// by the `./config/plugin/babel/add-import-extension` babel plugin. Thus, the distribution
// files will be broken. The reason is not known right now (probably it's caused by bug in
// the Babel or missing something in the plugin).
_base.default.plugins = (_Handsontable$plugins = _base.default.plugins) !== null && _Handsontable$plugins !== void 0 ? _Handsontable$plugins : {};
arrayHelpers.arrayEach((0, _registry6.getPluginsNames)(), pluginName => {
  _base.default.plugins[pluginName] = (0, _registry6.getPlugin)(pluginName);
});
_base.default.plugins[`${stringHelpers.toUpperCaseFirst(_base2.BasePlugin.PLUGIN_KEY)}Plugin`] = _base2.BasePlugin;
_base.default.plugins.registerPlugin = _registry6.registerPlugin;
_base.default.plugins.getPlugin = _registry6.getPlugin;
var _default = exports.default = _base.default;