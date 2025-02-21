function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { addClass } from './../../helpers/dom/element';
/**
 * Comment editor for the Comments plugin.
 *
 * @class CommentEditor
 * @plugin Comments
 */

var CommentEditor =
/*#__PURE__*/
function () {
  _createClass(CommentEditor, null, [{
    key: "CLASS_EDITOR_CONTAINER",
    get: function get() {
      return 'htCommentsContainer';
    }
  }, {
    key: "CLASS_EDITOR",
    get: function get() {
      return 'htComments';
    }
  }, {
    key: "CLASS_INPUT",
    get: function get() {
      return 'htCommentTextArea';
    }
  }, {
    key: "CLASS_CELL",
    get: function get() {
      return 'htCommentCell';
    }
  }]);

  function CommentEditor(rootDocument) {
    _classCallCheck(this, CommentEditor);

    this.container = null;
    this.rootDocument = rootDocument;
    this.editor = this.createEditor();
    this.editorStyle = this.editor.style;
    this.hidden = true;
    this.hide();
  }
  /**
   * Set position of the comments editor according to the  provided x and y coordinates.
   *
   * @param {Number} x X position (in pixels).
   * @param {Number} y Y position (in pixels).
   */


  _createClass(CommentEditor, [{
    key: "setPosition",
    value: function setPosition(x, y) {
      this.editorStyle.left = "".concat(x, "px");
      this.editorStyle.top = "".concat(y, "px");
    }
    /**
     * Set the editor size according to the provided arguments.
     *
     * @param {Number} width Width in pixels.
     * @param {Number} height Height in pixels.
     */

  }, {
    key: "setSize",
    value: function setSize(width, height) {
      if (width && height) {
        var input = this.getInputElement();
        input.style.width = "".concat(width, "px");
        input.style.height = "".concat(height, "px");
      }
    }
    /**
     * Reset the editor size to its initial state.
     */

  }, {
    key: "resetSize",
    value: function resetSize() {
      var input = this.getInputElement();
      input.style.width = '';
      input.style.height = '';
    }
    /**
     * Set the read-only state for the comments editor.
     *
     * @param {Boolean} state The new read only state.
     */

  }, {
    key: "setReadOnlyState",
    value: function setReadOnlyState(state) {
      var input = this.getInputElement();
      input.readOnly = state;
    }
    /**
     * Show the comments editor.
     */

  }, {
    key: "show",
    value: function show() {
      this.editorStyle.display = 'block';
      this.hidden = false;
    }
    /**
     * Hide the comments editor.
     */

  }, {
    key: "hide",
    value: function hide() {
      this.editorStyle.display = 'none';
      this.hidden = true;
    }
    /**
     * Checks if the editor is visible.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isVisible",
    value: function isVisible() {
      return this.editorStyle.display === 'block';
    }
    /**
     * Set the comment value.
     *
     * @param {String} [value] The value to use.
     */

  }, {
    key: "setValue",
    value: function setValue() {
      var value = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : '';
      var comment = value || '';
      this.getInputElement().value = comment;
    }
    /**
     * Get the comment value.
     *
     * @returns {String}
     */

  }, {
    key: "getValue",
    value: function getValue() {
      return this.getInputElement().value;
    }
    /**
     * Checks if the comment input element is focused.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isFocused",
    value: function isFocused() {
      return this.rootDocument.activeElement === this.getInputElement();
    }
    /**
     * Focus the comments input element.
     */

  }, {
    key: "focus",
    value: function focus() {
      this.getInputElement().focus();
    }
    /**
     * Create the `textarea` to be used as a comments editor.
     *
     * @returns {HTMLElement}
     */

  }, {
    key: "createEditor",
    value: function createEditor() {
      var editor = this.rootDocument.createElement('div');
      var textArea = this.rootDocument.createElement('textarea');
      this.container = this.rootDocument.querySelector(".".concat(CommentEditor.CLASS_EDITOR_CONTAINER));

      if (!this.container) {
        this.container = this.rootDocument.createElement('div');
        addClass(this.container, CommentEditor.CLASS_EDITOR_CONTAINER);
        this.rootDocument.body.appendChild(this.container);
      }

      addClass(editor, CommentEditor.CLASS_EDITOR);
      addClass(textArea, CommentEditor.CLASS_INPUT);
      editor.appendChild(textArea);
      this.container.appendChild(editor);
      return editor;
    }
    /**
     * Get the input element.
     *
     * @returns {HTMLElement}
     */

  }, {
    key: "getInputElement",
    value: function getInputElement() {
      return this.editor.querySelector(".".concat(CommentEditor.CLASS_INPUT));
    }
    /**
     * Destroy the comments editor.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      var containerParentElement = this.container ? this.container.parentNode : null;
      this.editor.parentNode.removeChild(this.editor);
      this.editor = null;
      this.editorStyle = null;

      if (containerParentElement) {
        containerParentElement.removeChild(this.container);
      }
    }
  }]);

  return CommentEditor;
}();

export default CommentEditor;