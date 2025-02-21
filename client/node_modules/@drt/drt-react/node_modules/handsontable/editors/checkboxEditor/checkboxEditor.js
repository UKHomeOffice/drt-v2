"use strict";

exports.__esModule = true;
var _baseEditor = require("../baseEditor");
var _element = require("../../helpers/dom/element");
const EDITOR_TYPE = exports.EDITOR_TYPE = 'checkbox';

/**
 * @private
 * @class CheckboxEditor
 */
class CheckboxEditor extends _baseEditor.BaseEditor {
  static get EDITOR_TYPE() {
    return EDITOR_TYPE;
  }
  beginEditing(initialValue, event) {
    // Just some events connected with the checkbox editor are delegated here. Some `keydown` events like `enter` and
    // `space` key presses are handled inside `checkboxRenderer`. Some events come here from `editorManager`. The below
    // `if` statement was created by the author for the purpose of handling only the `doubleclick` event on the TD
    // element with a checkbox.

    if (event && event.type === 'mouseup' && event.target.nodeName === 'TD') {
      const checkbox = this.TD.querySelector('input[type="checkbox"]');
      if (!(0, _element.hasClass)(checkbox, 'htBadValue')) {
        checkbox.click();
      }
    }
  }
  finishEditing() {}
  init() {}
  open() {}
  close() {}
  getValue() {}
  setValue() {}
  focus() {}
}
exports.CheckboxEditor = CheckboxEditor;