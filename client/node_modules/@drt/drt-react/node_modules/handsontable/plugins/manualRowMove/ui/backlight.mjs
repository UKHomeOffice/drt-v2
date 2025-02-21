import BaseUI from "./_base.mjs";
import { addClass } from "../../../helpers/dom/element.mjs";
const CSS_CLASSNAME = 'ht__manualRowMove--backlight';

/**
 * @private
 * @class BacklightUI
 */
class BacklightUI extends BaseUI {
  /**
   * Custom className on build process.
   */
  build() {
    super.build();
    addClass(this._element, CSS_CLASSNAME);
  }
}
export default BacklightUI;