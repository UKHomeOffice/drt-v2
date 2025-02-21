"use strict";

require("core-js/modules/es.symbol");

require("core-js/modules/es.symbol.description");

require("core-js/modules/es.symbol.iterator");

require("core-js/modules/es.array.iterator");

require("core-js/modules/es.object.get-own-property-descriptor");

require("core-js/modules/es.object.get-prototype-of");

require("core-js/modules/es.object.set-prototype-of");

require("core-js/modules/es.object.to-string");

require("core-js/modules/es.reflect.get");

require("core-js/modules/es.string.iterator");

require("core-js/modules/web.dom-collections.iterator");

exports.__esModule = true;
exports.default = void 0;

var _unicode = require("./../helpers/unicode");

var _object = require("./../helpers/object");

var _element = require("./../helpers/dom/element");

var _event = require("./../helpers/dom/event");

var _textEditor = _interopRequireDefault(require("./textEditor"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _typeof(obj) { if (typeof Symbol === "function" && typeof Symbol.iterator === "symbol") { _typeof = function _typeof(obj) { return typeof obj; }; } else { _typeof = function _typeof(obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol && obj !== Symbol.prototype ? "symbol" : typeof obj; }; } return _typeof(obj); }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

function _possibleConstructorReturn(self, call) { if (call && (_typeof(call) === "object" || typeof call === "function")) { return call; } return _assertThisInitialized(self); }

function _assertThisInitialized(self) { if (self === void 0) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return self; }

function _get(target, property, receiver) { if (typeof Reflect !== "undefined" && Reflect.get) { _get = Reflect.get; } else { _get = function _get(target, property, receiver) { var base = _superPropBase(target, property); if (!base) return; var desc = Object.getOwnPropertyDescriptor(base, property); if (desc.get) { return desc.get.call(receiver); } return desc.value; }; } return _get(target, property, receiver || target); }

function _superPropBase(object, property) { while (!Object.prototype.hasOwnProperty.call(object, property)) { object = _getPrototypeOf(object); if (object === null) break; } return object; }

function _getPrototypeOf(o) { _getPrototypeOf = Object.setPrototypeOf ? Object.getPrototypeOf : function _getPrototypeOf(o) { return o.__proto__ || Object.getPrototypeOf(o); }; return _getPrototypeOf(o); }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function"); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, writable: true, configurable: true } }); if (superClass) _setPrototypeOf(subClass, superClass); }

function _setPrototypeOf(o, p) { _setPrototypeOf = Object.setPrototypeOf || function _setPrototypeOf(o, p) { o.__proto__ = p; return o; }; return _setPrototypeOf(o, p); }

/**
 * @private
 * @editor HandsontableEditor
 * @class HandsontableEditor
 * @dependencies TextEditor
 */
var HandsontableEditor =
/*#__PURE__*/
function (_TextEditor) {
  _inherits(HandsontableEditor, _TextEditor);

  function HandsontableEditor() {
    _classCallCheck(this, HandsontableEditor);

    return _possibleConstructorReturn(this, _getPrototypeOf(HandsontableEditor).apply(this, arguments));
  }

  _createClass(HandsontableEditor, [{
    key: "open",

    /**
     * Opens the editor and adjust its size.
     */
    value: function open() {
      // this.addHook('beforeKeyDown', event => this.onBeforeKeyDown(event));
      _get(_getPrototypeOf(HandsontableEditor.prototype), "open", this).call(this);

      if (this.htEditor) {
        this.htEditor.destroy();
      }

      if (this.htContainer.style.display === 'none') {
        this.htContainer.style.display = '';
      } // Construct and initialise a new Handsontable


      this.htEditor = new this.hot.constructor(this.htContainer, this.htOptions);
      this.htEditor.init();
      this.htEditor.rootElement.style.display = '';

      if (this.cellProperties.strict) {
        this.htEditor.selectCell(0, 0);
      } else {
        this.htEditor.deselectCell();
      }

      (0, _element.setCaretPosition)(this.TEXTAREA, 0, this.TEXTAREA.value.length);
    }
    /**
     * Closes the editor.
     */

  }, {
    key: "close",
    value: function close() {
      if (this.htEditor) {
        this.htEditor.rootElement.style.display = 'none';
      }

      this.removeHooksByKey('beforeKeyDown');

      _get(_getPrototypeOf(HandsontableEditor.prototype), "close", this).call(this);
    }
    /**
     * Prepares editor's meta data and configuration of the internal Handsontable's instance.
     *
     * @param {Number} row
     * @param {Number} col
     * @param {Number|String} prop
     * @param {HTMLTableCellElement} td
     * @param {*} originalValue
     * @param {Object} cellProperties
     */

  }, {
    key: "prepare",
    value: function prepare(td, row, col, prop, value, cellProperties) {
      _get(_getPrototypeOf(HandsontableEditor.prototype), "prepare", this).call(this, td, row, col, prop, value, cellProperties);

      var parent = this;
      var options = {
        startRows: 0,
        startCols: 0,
        minRows: 0,
        minCols: 0,
        className: 'listbox',
        copyPaste: false,
        autoColumnSize: false,
        autoRowSize: false,
        readOnly: true,
        fillHandle: false,
        autoWrapCol: false,
        autoWrapRow: false,
        afterOnCellMouseDown: function afterOnCellMouseDown(_, coords) {
          var sourceValue = this.getSourceData(coords.row, coords.col); // if the value is undefined then it means we don't want to set the value

          if (sourceValue !== void 0) {
            parent.setValue(sourceValue);
          }

          parent.instance.destroyEditor();
        },
        preventWheel: true
      };

      if (this.cellProperties.handsontable) {
        (0, _object.extend)(options, cellProperties.handsontable);
      }

      this.htOptions = options;
    }
    /**
     * Begins editing on a highlighted cell and hides fillHandle corner if was present.
     *
     * @param {*} newInitialValue
     * @param {*} event
     */

  }, {
    key: "beginEditing",
    value: function beginEditing(newInitialValue, event) {
      var onBeginEditing = this.hot.getSettings().onBeginEditing;

      if (onBeginEditing && onBeginEditing() === false) {
        return;
      }

      _get(_getPrototypeOf(HandsontableEditor.prototype), "beginEditing", this).call(this, newInitialValue, event);
    }
    /**
     * Sets focus state on the select element.
     */

  }, {
    key: "focus",
    value: function focus(safeFocus) {
      _get(_getPrototypeOf(HandsontableEditor.prototype), "focus", this).call(this, safeFocus);
    }
    /**
     * Creates an editor's elements and adds necessary CSS classnames.
     */

  }, {
    key: "createElements",
    value: function createElements() {
      _get(_getPrototypeOf(HandsontableEditor.prototype), "createElements", this).call(this);

      var DIV = this.hot.rootDocument.createElement('DIV');
      DIV.className = 'handsontableEditor';
      this.TEXTAREA_PARENT.appendChild(DIV);
      this.htContainer = DIV;
      this.assignHooks();
    }
    /**
     * Finishes editing and start saving or restoring process for editing cell or last selected range.
     *
     * @param {Boolean} restoreOriginalValue If true, then closes editor without saving value from the editor into a cell.
     * @param {Boolean} ctrlDown If true, then saveValue will save editor's value to each cell in the last selected range.
     * @param {Function} callback
     */

  }, {
    key: "finishEditing",
    value: function finishEditing(restoreOriginalValue, ctrlDown, callback) {
      if (this.htEditor && this.htEditor.isListening()) {
        // if focus is still in the HOT editor
        this.hot.listen(); // return the focus to the parent HOT instance
      }

      if (this.htEditor && this.htEditor.getSelectedLast()) {
        var value = this.htEditor.getInstance().getValue();

        if (value !== void 0) {
          // if the value is undefined then it means we don't want to set the value
          this.setValue(value);
        }
      }

      return _get(_getPrototypeOf(HandsontableEditor.prototype), "finishEditing", this).call(this, restoreOriginalValue, ctrlDown, callback);
    }
    /**
     * Assings afterDestroy callback to prevent memory leaks.
     *
     * @private
     */

  }, {
    key: "assignHooks",
    value: function assignHooks() {
      var _this = this;

      this.hot.addHook('afterDestroy', function () {
        if (_this.htEditor) {
          _this.htEditor.destroy();
        }
      });
    }
    /**
     * onBeforeKeyDown callback.
     *
     * @private
     * @param {Event} event
     */

  }, {
    key: "onBeforeKeyDown",
    value: function onBeforeKeyDown(event) {
      if ((0, _event.isImmediatePropagationStopped)(event)) {
        return;
      }

      var innerHOT = this.htEditor.getInstance();
      var rowToSelect;
      var selectedRow;

      if (event.keyCode === _unicode.KEY_CODES.ARROW_DOWN) {
        if (!innerHOT.getSelectedLast() && !innerHOT.flipped) {
          rowToSelect = 0;
        } else if (innerHOT.getSelectedLast()) {
          if (innerHOT.flipped) {
            rowToSelect = innerHOT.getSelectedLast()[0] + 1;
          } else if (!innerHOT.flipped) {
            var lastRow = innerHOT.countRows() - 1;
            selectedRow = innerHOT.getSelectedLast()[0];
            rowToSelect = Math.min(lastRow, selectedRow + 1);
          }
        }
      } else if (event.keyCode === _unicode.KEY_CODES.ARROW_UP) {
        if (!innerHOT.getSelectedLast() && innerHOT.flipped) {
          rowToSelect = innerHOT.countRows() - 1;
        } else if (innerHOT.getSelectedLast()) {
          if (innerHOT.flipped) {
            selectedRow = innerHOT.getSelectedLast()[0];
            rowToSelect = Math.max(0, selectedRow - 1);
          } else {
            selectedRow = innerHOT.getSelectedLast()[0];
            rowToSelect = selectedRow - 1;
          }
        }
      }

      if (rowToSelect !== void 0) {
        if (rowToSelect < 0 || innerHOT.flipped && rowToSelect > innerHOT.countRows() - 1) {
          innerHOT.deselectCell();
        } else {
          innerHOT.selectCell(rowToSelect, 0);
        }

        if (innerHOT.getData().length) {
          event.preventDefault();
          (0, _event.stopImmediatePropagation)(event);
          this.hot.listen();
          this.TEXTAREA.focus();
        }
      }

      _get(_getPrototypeOf(HandsontableEditor.prototype), "onBeforeKeyDown", this).call(this, event);
    }
  }]);

  return HandsontableEditor;
}(_textEditor.default);

var _default = HandsontableEditor;
exports.default = _default;