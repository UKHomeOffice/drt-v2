"use strict";

exports.__esModule = true;
var _textEditor = require("../textEditor");
const EDITOR_TYPE = exports.EDITOR_TYPE = 'time';

/**
 * @private
 * @class TimeEditor
 */
class TimeEditor extends _textEditor.TextEditor {
  static get EDITOR_TYPE() {
    return EDITOR_TYPE;
  }

  /**
   * Prepares editor's meta data.
   *
   * @param {number} row The visual row index.
   * @param {number} col The visual column index.
   * @param {number|string} prop The column property (passed when datasource is an array of objects).
   * @param {HTMLTableCellElement} td The rendered cell element.
   * @param {*} value The rendered value.
   * @param {object} cellProperties The cell meta object (see {@link Core#getCellMeta}).
   */
  prepare(row, col, prop, td, value, cellProperties) {
    super.prepare(row, col, prop, td, value, cellProperties);
    this.TEXTAREA.dir = 'ltr';
  }
}
exports.TimeEditor = TimeEditor;