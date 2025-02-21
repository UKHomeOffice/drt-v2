import "core-js/modules/es.error.cause.js";
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
import { addClass, outerWidth, outerHeight } from "../../helpers/dom/element.mjs";
import { mixin } from "../../helpers/object.mjs";
import localHooks from "../../mixins/localHooks.mjs";
import { EditorResizeObserver } from "./editorResizeObserver.mjs";
/**
 * Comment editor for the Comments plugin.
 *
 * @private
 * @class CommentEditor
 */
var _rootDocument = /*#__PURE__*/new WeakMap();
var _isRtl = /*#__PURE__*/new WeakMap();
var _container = /*#__PURE__*/new WeakMap();
var _editor = /*#__PURE__*/new WeakMap();
var _editorStyle = /*#__PURE__*/new WeakMap();
var _hidden = /*#__PURE__*/new WeakMap();
var _resizeObserver = /*#__PURE__*/new WeakMap();
class CommentEditor {
  static get CLASS_EDITOR_CONTAINER() {
    return 'htCommentsContainer';
  }
  static get CLASS_EDITOR() {
    return 'htComments';
  }
  static get CLASS_INPUT() {
    return 'htCommentTextArea';
  }
  static get CLASS_CELL() {
    return 'htCommentCell';
  }

  /**
   * @type {Document}
   */

  constructor(rootDocument, isRtl) {
    var _this = this;
    _classPrivateFieldInitSpec(this, _rootDocument, void 0);
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _isRtl, false);
    /**
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _container, null);
    /**
     * @type {HTMLElement}
     */
    _classPrivateFieldInitSpec(this, _editor, void 0);
    /**
     * @type {CSSStyleDeclaration}
     */
    _classPrivateFieldInitSpec(this, _editorStyle, void 0);
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _hidden, true);
    /**
     * @type {EditorResizeObserver}
     */
    _classPrivateFieldInitSpec(this, _resizeObserver, new EditorResizeObserver());
    _classPrivateFieldSet(_rootDocument, this, rootDocument);
    _classPrivateFieldSet(_isRtl, this, isRtl);
    _classPrivateFieldSet(_editor, this, this.createEditor());
    _classPrivateFieldSet(_editorStyle, this, _classPrivateFieldGet(_editor, this).style);
    _classPrivateFieldGet(_resizeObserver, this).setObservedElement(this.getInputElement());
    _classPrivateFieldGet(_resizeObserver, this).addLocalHook('resize', function () {
      for (var _len = arguments.length, args = new Array(_len), _key = 0; _key < _len; _key++) {
        args[_key] = arguments[_key];
      }
      return _this.runLocalHooks('resize', ...args);
    });
    this.hide();
  }

  /**
   * Set position of the comments editor according to the  provided x and y coordinates.
   *
   * @param {number} x X position (in pixels).
   * @param {number} y Y position (in pixels).
   */
  setPosition(x, y) {
    _classPrivateFieldGet(_editorStyle, this).left = `${x}px`;
    _classPrivateFieldGet(_editorStyle, this).top = `${y}px`;
  }

  /**
   * Set the editor size according to the provided arguments.
   *
   * @param {number} width Width in pixels.
   * @param {number} height Height in pixels.
   */
  setSize(width, height) {
    if (width && height) {
      const input = this.getInputElement();
      input.style.width = `${width}px`;
      input.style.height = `${height}px`;
    }
  }

  /**
   * Returns the size of the comments editor.
   *
   * @returns {{ width: number, height: number }}
   */
  getSize() {
    return {
      width: outerWidth(this.getInputElement()),
      height: outerHeight(this.getInputElement())
    };
  }

  /**
   * Starts observing the editor size.
   */
  observeSize() {
    _classPrivateFieldGet(_resizeObserver, this).observe();
  }

  /**
   * Reset the editor size to its initial state.
   */
  resetSize() {
    const input = this.getInputElement();
    input.style.width = '';
    input.style.height = '';
  }

  /**
   * Set the read-only state for the comments editor.
   *
   * @param {boolean} state The new read only state.
   */
  setReadOnlyState(state) {
    const input = this.getInputElement();
    input.readOnly = state;
  }

  /**
   * Show the comments editor.
   */
  show() {
    _classPrivateFieldGet(_editorStyle, this).display = 'block';
    _classPrivateFieldSet(_hidden, this, false);
  }

  /**
   * Hide the comments editor.
   */
  hide() {
    _classPrivateFieldGet(_resizeObserver, this).unobserve();
    if (!_classPrivateFieldGet(_hidden, this)) {
      _classPrivateFieldGet(_editorStyle, this).display = 'none';
    }
    _classPrivateFieldSet(_hidden, this, true);
  }

  /**
   * Checks if the editor is visible.
   *
   * @returns {boolean}
   */
  isVisible() {
    return _classPrivateFieldGet(_editorStyle, this).display === 'block';
  }

  /**
   * Set the comment value.
   *
   * @param {string} [value] The value to use.
   */
  setValue() {
    let value = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : '';
    const comment = value || '';
    this.getInputElement().value = comment;
  }

  /**
   * Get the comment value.
   *
   * @returns {string}
   */
  getValue() {
    return this.getInputElement().value;
  }

  /**
   * Checks if the comment input element is focused.
   *
   * @returns {boolean}
   */
  isFocused() {
    return _classPrivateFieldGet(_rootDocument, this).activeElement === this.getInputElement();
  }

  /**
   * Focus the comments input element.
   */
  focus() {
    this.getInputElement().focus();
  }

  /**
   * Create the `textarea` to be used as a comments editor.
   *
   * @returns {HTMLElement}
   */
  createEditor() {
    const editor = _classPrivateFieldGet(_rootDocument, this).createElement('div');
    const textarea = _classPrivateFieldGet(_rootDocument, this).createElement('textarea');
    editor.style.display = 'none';
    _classPrivateFieldSet(_container, this, _classPrivateFieldGet(_rootDocument, this).createElement('div'));
    _classPrivateFieldGet(_container, this).setAttribute('dir', _classPrivateFieldGet(_isRtl, this) ? 'rtl' : 'ltr');
    addClass(_classPrivateFieldGet(_container, this), CommentEditor.CLASS_EDITOR_CONTAINER);
    _classPrivateFieldGet(_rootDocument, this).body.appendChild(_classPrivateFieldGet(_container, this));
    addClass(editor, CommentEditor.CLASS_EDITOR);
    addClass(textarea, CommentEditor.CLASS_INPUT);
    textarea.setAttribute('data-hot-input', true);
    editor.appendChild(textarea);
    _classPrivateFieldGet(_container, this).appendChild(editor);
    return editor;
  }

  /**
   * Get the input element.
   *
   * @returns {HTMLElement}
   */
  getInputElement() {
    return _classPrivateFieldGet(_editor, this).querySelector(`.${CommentEditor.CLASS_INPUT}`);
  }

  /**
   * Get the editor element.
   *
   * @returns {HTMLElement} The editor element.
   */
  getEditorElement() {
    return _classPrivateFieldGet(_editor, this);
  }

  /**
   * Destroy the comments editor.
   */
  destroy() {
    const containerParentElement = _classPrivateFieldGet(_container, this) ? _classPrivateFieldGet(_container, this).parentNode : null;
    _classPrivateFieldGet(_editor, this).parentNode.removeChild(_classPrivateFieldGet(_editor, this));
    _classPrivateFieldSet(_editor, this, null);
    _classPrivateFieldSet(_editorStyle, this, null);
    _classPrivateFieldGet(_resizeObserver, this).destroy();
    if (containerParentElement) {
      containerParentElement.removeChild(_classPrivateFieldGet(_container, this));
    }
  }
}
mixin(CommentEditor, localHooks);
export default CommentEditor;