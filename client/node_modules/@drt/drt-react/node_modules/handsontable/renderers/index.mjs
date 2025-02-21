import { autocompleteRenderer, RENDERER_TYPE as AUTOCOMPLETE_RENDERER } from "./autocompleteRenderer/index.mjs";
import { baseRenderer, RENDERER_TYPE as BASE_RENDERER } from "./baseRenderer/index.mjs";
import { dropdownRenderer, RENDERER_TYPE as DROPDOWN_RENDERER } from "./dropdownRenderer/index.mjs";
import { checkboxRenderer, RENDERER_TYPE as CHECKBOX_RENDERER } from "./checkboxRenderer/index.mjs";
import { handsontableRenderer, RENDERER_TYPE as HANDSONTABLE_RENDERER } from "./handsontableRenderer/index.mjs";
import { htmlRenderer, RENDERER_TYPE as HTML_RENDERER } from "./htmlRenderer/index.mjs";
import { numericRenderer, RENDERER_TYPE as NUMERIC_RENDERER } from "./numericRenderer/index.mjs";
import { passwordRenderer, RENDERER_TYPE as PASSWORD_RENDERER } from "./passwordRenderer/index.mjs";
import { selectRenderer, RENDERER_TYPE as SELECT_RENDERER } from "./selectRenderer/index.mjs";
import { textRenderer, RENDERER_TYPE as TEXT_RENDERER } from "./textRenderer/index.mjs";
import { timeRenderer, RENDERER_TYPE as TIME_RENDERER } from "./timeRenderer/index.mjs";
import { registerRenderer } from "./registry.mjs";
/**
 * Registers all available renderers.
 */
export function registerAllRenderers() {
  registerRenderer(autocompleteRenderer);
  registerRenderer(baseRenderer);
  registerRenderer(checkboxRenderer);
  registerRenderer(dropdownRenderer);
  registerRenderer(handsontableRenderer);
  registerRenderer(htmlRenderer);
  registerRenderer(numericRenderer);
  registerRenderer(passwordRenderer);
  registerRenderer(selectRenderer);
  registerRenderer(textRenderer);
  registerRenderer(timeRenderer);
}
export { autocompleteRenderer, AUTOCOMPLETE_RENDERER, baseRenderer, BASE_RENDERER, dropdownRenderer, DROPDOWN_RENDERER, checkboxRenderer, CHECKBOX_RENDERER, handsontableRenderer, HANDSONTABLE_RENDERER, htmlRenderer, HTML_RENDERER, numericRenderer, NUMERIC_RENDERER, passwordRenderer, PASSWORD_RENDERER, selectRenderer, SELECT_RENDERER, textRenderer, TEXT_RENDERER, timeRenderer, TIME_RENDERER };
export { getRegisteredRendererNames, getRegisteredRenderers, getRenderer, hasRenderer, registerRenderer } from "./registry.mjs";