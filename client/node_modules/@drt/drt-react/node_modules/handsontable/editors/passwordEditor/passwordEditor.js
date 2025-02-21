"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _textEditor = require("../textEditor");
var _autoResize = require("../../utils/autoResize");
var _element = require("../../helpers/dom/element");
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
const EDITOR_TYPE = exports.EDITOR_TYPE = 'password';

/**
 * @private
 * @class PasswordEditor
 */
class PasswordEditor extends _textEditor.TextEditor {
  constructor() {
    super(...arguments);
    /**
     * Autoresize instance for resizing the editor to the size of the entered text. Its overwrites the default
     * resizer of the TextEditor.
     *
     * @private
     * @type {Function}
     */
    _defineProperty(this, "autoResize", (0, _autoResize.createInputElementResizer)(this.hot.rootDocument, {
      textContent: element => '•'.repeat(element.value.length)
    }));
  }
  static get EDITOR_TYPE() {
    return EDITOR_TYPE;
  }
  createElements() {
    super.createElements();
    this.TEXTAREA = this.hot.rootDocument.createElement('input');
    this.TEXTAREA.setAttribute('type', 'password');
    this.TEXTAREA.setAttribute('data-hot-input', ''); // Makes the element recognizable by Hot as its own component's element.
    this.TEXTAREA.className = 'handsontableInput';
    this.textareaStyle = this.TEXTAREA.style;
    this.textareaStyle.width = 0;
    this.textareaStyle.height = 0;
    (0, _element.empty)(this.TEXTAREA_PARENT);
    this.TEXTAREA_PARENT.appendChild(this.TEXTAREA);
  }
}
exports.PasswordEditor = PasswordEditor;