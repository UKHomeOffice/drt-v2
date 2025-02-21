var _Handsontable$cellTyp, _Handsontable$editors, _Handsontable$rendere, _Handsontable$validat, _Handsontable$plugins;
import Handsontable, { CellCoords, CellRange } from "./base.mjs";
import { registerAllModules } from "./registry.mjs";
import EventManager, { getListenersCounter } from "./eventManager.mjs";
import { getRegisteredMapsCounter } from "./translations/index.mjs";
import jQueryWrapper from "./helpers/wrappers/jquery.mjs";
import GhostTable from "./utils/ghostTable.mjs";
import * as parseTableHelpers from "./utils/parseTable.mjs";
import * as arrayHelpers from "./helpers/array.mjs";
import * as browserHelpers from "./helpers/browser.mjs";
import * as dataHelpers from "./helpers/data.mjs";
import * as dateHelpers from "./helpers/date.mjs";
import * as featureHelpers from "./helpers/feature.mjs";
import * as functionHelpers from "./helpers/function.mjs";
import * as mixedHelpers from "./helpers/mixed.mjs";
import * as numberHelpers from "./helpers/number.mjs";
import * as objectHelpers from "./helpers/object.mjs";
import * as stringHelpers from "./helpers/string.mjs";
import * as unicodeHelpers from "./helpers/unicode.mjs";
import * as domHelpers from "./helpers/dom/element.mjs";
import * as domEventHelpers from "./helpers/dom/event.mjs";
import { getRegisteredEditorNames, getEditor, registerEditor } from "./editors/registry.mjs";
import { getRegisteredRendererNames, getRenderer, registerRenderer } from "./renderers/registry.mjs";
import { getRegisteredValidatorNames, getValidator, registerValidator } from "./validators/registry.mjs";
import { getRegisteredCellTypeNames, getCellType, registerCellType } from "./cellTypes/registry.mjs";
import { getPluginsNames, getPlugin, registerPlugin } from "./plugins/registry.mjs";
import { BasePlugin } from "./plugins/base/index.mjs";
registerAllModules();
jQueryWrapper(Handsontable);

// TODO: Remove this exports after rewrite tests about this module
Handsontable.__GhostTable = GhostTable;
Handsontable._getListenersCounter = getListenersCounter; // For MemoryLeak tests
Handsontable._getRegisteredMapsCounter = getRegisteredMapsCounter; // For MemoryLeak tests
Handsontable.EventManager = EventManager;

// Export all helpers to the Handsontable object
const HELPERS = [arrayHelpers, browserHelpers, dataHelpers, dateHelpers, featureHelpers, functionHelpers, mixedHelpers, numberHelpers, objectHelpers, stringHelpers, unicodeHelpers, parseTableHelpers];
const DOM = [domHelpers, domEventHelpers];
Handsontable.helper = {};
Handsontable.dom = {};

// Fill general helpers.
arrayHelpers.arrayEach(HELPERS, helper => {
  arrayHelpers.arrayEach(Object.getOwnPropertyNames(helper), key => {
    if (key.charAt(0) !== '_') {
      Handsontable.helper[key] = helper[key];
    }
  });
});

// Fill DOM helpers.
arrayHelpers.arrayEach(DOM, helper => {
  arrayHelpers.arrayEach(Object.getOwnPropertyNames(helper), key => {
    if (key.charAt(0) !== '_') {
      Handsontable.dom[key] = helper[key];
    }
  });
});

// Export cell types.
Handsontable.cellTypes = (_Handsontable$cellTyp = Handsontable.cellTypes) !== null && _Handsontable$cellTyp !== void 0 ? _Handsontable$cellTyp : {};
arrayHelpers.arrayEach(getRegisteredCellTypeNames(), cellTypeName => {
  Handsontable.cellTypes[cellTypeName] = getCellType(cellTypeName);
});
Handsontable.cellTypes.registerCellType = registerCellType;
Handsontable.cellTypes.getCellType = getCellType;

// Export all registered editors from the Handsontable.
Handsontable.editors = (_Handsontable$editors = Handsontable.editors) !== null && _Handsontable$editors !== void 0 ? _Handsontable$editors : {};
arrayHelpers.arrayEach(getRegisteredEditorNames(), editorName => {
  Handsontable.editors[`${stringHelpers.toUpperCaseFirst(editorName)}Editor`] = getEditor(editorName);
});
Handsontable.editors.registerEditor = registerEditor;
Handsontable.editors.getEditor = getEditor;

// Export all registered renderers from the Handsontable.
Handsontable.renderers = (_Handsontable$rendere = Handsontable.renderers) !== null && _Handsontable$rendere !== void 0 ? _Handsontable$rendere : {};
arrayHelpers.arrayEach(getRegisteredRendererNames(), rendererName => {
  const renderer = getRenderer(rendererName);
  if (rendererName === 'base') {
    Handsontable.renderers.cellDecorator = renderer;
  }
  Handsontable.renderers[`${stringHelpers.toUpperCaseFirst(rendererName)}Renderer`] = renderer;
});
Handsontable.renderers.registerRenderer = registerRenderer;
Handsontable.renderers.getRenderer = getRenderer;

// Export all registered validators from the Handsontable.
Handsontable.validators = (_Handsontable$validat = Handsontable.validators) !== null && _Handsontable$validat !== void 0 ? _Handsontable$validat : {};
arrayHelpers.arrayEach(getRegisteredValidatorNames(), validatorName => {
  Handsontable.validators[`${stringHelpers.toUpperCaseFirst(validatorName)}Validator`] = getValidator(validatorName);
});
Handsontable.validators.registerValidator = registerValidator;
Handsontable.validators.getValidator = getValidator;

// Export all registered plugins from the Handsontable.
// Make sure to initialize the plugin dictionary as an empty object. Otherwise, while
// transpiling the files into ES and CommonJS format, the injected CoreJS helper
// `import "core-js/modules/es.object.get-own-property-names";` won't be processed
// by the `./config/plugin/babel/add-import-extension` babel plugin. Thus, the distribution
// files will be broken. The reason is not known right now (probably it's caused by bug in
// the Babel or missing something in the plugin).
Handsontable.plugins = (_Handsontable$plugins = Handsontable.plugins) !== null && _Handsontable$plugins !== void 0 ? _Handsontable$plugins : {};
arrayHelpers.arrayEach(getPluginsNames(), pluginName => {
  Handsontable.plugins[pluginName] = getPlugin(pluginName);
});
Handsontable.plugins[`${stringHelpers.toUpperCaseFirst(BasePlugin.PLUGIN_KEY)}Plugin`] = BasePlugin;
Handsontable.plugins.registerPlugin = registerPlugin;
Handsontable.plugins.getPlugin = getPlugin;
export { CellCoords, CellRange };
export default Handsontable;