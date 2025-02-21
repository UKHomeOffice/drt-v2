import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.for-each.js";
import { shortcutsEditorContext } from "./editor.mjs";
import { shortcutsGridContext } from "./grid.mjs";
export * from "./constants.mjs";
/**
 * Register all shortcut contexts.
 *
 * @param {Handsontable} hotInstance The Handsontable instance.
 */
export function registerAllShortcutContexts(hotInstance) {
  [shortcutsGridContext, shortcutsEditorContext].forEach(context => context(hotInstance));
}