import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.filter";
import "core-js/modules/es.array.find";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.array.sort";
import "core-js/modules/es.object.get-own-property-descriptor";
import "core-js/modules/es.object.get-prototype-of";
import "core-js/modules/es.object.set-prototype-of";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.reflect.get";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.string.replace";
import "core-js/modules/es.weak-map";
import "core-js/modules/web.dom-collections.iterator";

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

import { KEY_CODES, isPrintableChar } from './../helpers/unicode';
import { stringify, isDefined } from './../helpers/mixed';
import { stripTags } from './../helpers/string';
import { pivot, arrayMap } from './../helpers/array';
import { addClass, getCaretPosition, getScrollbarWidth, getSelectionEndPosition, outerWidth, outerHeight, offset, getTrimmingContainer, setCaretPosition } from './../helpers/dom/element';
import HandsontableEditor from './handsontableEditor';
var privatePool = new WeakMap();
/**
 * @private
 * @editor AutocompleteEditor
 * @class AutocompleteEditor
 * @dependencies HandsontableEditor
 */

var AutocompleteEditor =
/*#__PURE__*/
function (_HandsontableEditor) {
  _inherits(AutocompleteEditor, _HandsontableEditor);

  function AutocompleteEditor(instance) {
    var _this;

    _classCallCheck(this, AutocompleteEditor);

    _this = _possibleConstructorReturn(this, _getPrototypeOf(AutocompleteEditor).call(this, instance));
    /**
     * Query string to turn available values over.
     *
     * @type {String}
     */

    _this.query = null;
    /**
     * Contains stripped choices.
     *
     * @type {String[]}
     */

    _this.strippedChoices = [];
    /**
     * Contains raw choices.
     *
     * @type {Array}
     */

    _this.rawChoices = [];
    privatePool.set(_assertThisInitialized(_this), {
      skipOne: false
    });
    return _this;
  }
  /**
   * Gets current value from editable element.
   *
   * @returns {String}
   */


  _createClass(AutocompleteEditor, [{
    key: "getValue",
    value: function getValue() {
      var _this2 = this;

      var selectedValue = this.rawChoices.find(function (value) {
        var strippedValue = _this2.stripValueIfNeeded(value);

        return strippedValue === _this2.TEXTAREA.value;
      });

      if (isDefined(selectedValue)) {
        return selectedValue;
      }

      return this.TEXTAREA.value;
    }
    /**
     * Creates an editor's elements and adds necessary CSS classnames.
     */

  }, {
    key: "createElements",
    value: function createElements() {
      _get(_getPrototypeOf(AutocompleteEditor.prototype), "createElements", this).call(this);

      addClass(this.htContainer, 'autocompleteEditor');
      addClass(this.htContainer, this.hot.rootWindow.navigator.platform.indexOf('Mac') === -1 ? '' : 'htMacScroll');
    }
    /**
     * Opens the editor and adjust its size and internal Handsontable's instance.
     */

  }, {
    key: "open",
    value: function open() {
      var _this3 = this;

      var priv = privatePool.get(this); // this.addHook('beforeKeyDown', event => this.onBeforeKeyDown(event));
      // Ugly fix for handsontable which grab window object for autocomplete scroll listener instead table element.

      this.TEXTAREA_PARENT.style.overflow = 'auto';

      _get(_getPrototypeOf(AutocompleteEditor.prototype), "open", this).call(this);

      this.TEXTAREA_PARENT.style.overflow = '';
      var choicesListHot = this.htEditor.getInstance();
      var trimDropdown = this.cellProperties.trimDropdown === void 0 ? true : this.cellProperties.trimDropdown;
      this.showEditableElement();
      this.focus();
      var scrollbarWidth = getScrollbarWidth(this.hot.rootDocument);
      choicesListHot.updateSettings({
        colWidths: trimDropdown ? [outerWidth(this.TEXTAREA) - 2] : void 0,
        width: trimDropdown ? outerWidth(this.TEXTAREA) + scrollbarWidth + 2 : void 0,
        afterRenderer: function afterRenderer(TD, row, col, prop, value) {
          var _this3$cellProperties = _this3.cellProperties,
              filteringCaseSensitive = _this3$cellProperties.filteringCaseSensitive,
              allowHtml = _this3$cellProperties.allowHtml;
          var query = _this3.query;
          var cellValue = stringify(value);
          var indexOfMatch;
          var match;

          if (cellValue && !allowHtml) {
            indexOfMatch = filteringCaseSensitive === true ? cellValue.indexOf(query) : cellValue.toLowerCase().indexOf(query.toLowerCase());

            if (indexOfMatch !== -1) {
              match = cellValue.substr(indexOfMatch, query.length);
              cellValue = cellValue.replace(match, "<strong>".concat(match, "</strong>"));
            }
          }

          TD.innerHTML = cellValue;
        },
        autoColumnSize: true,
        modifyColWidth: function modifyColWidth(width, col) {
          // workaround for <strong> text overlapping the dropdown, not really accurate
          var autoColumnSize = this.getPlugin('autoColumnSize');
          var columnWidth = width;

          if (autoColumnSize) {
            var autoWidths = autoColumnSize.widths;

            if (autoWidths[col]) {
              columnWidth = autoWidths[col];
            }
          }

          return trimDropdown ? columnWidth : columnWidth + 15;
        }
      }); // Add additional space for autocomplete holder

      this.htEditor.view.wt.wtTable.holder.parentNode.style['padding-right'] = "".concat(scrollbarWidth + 2, "px");

      if (priv.skipOne) {
        priv.skipOne = false;
      }

      this.hot._registerTimeout(function () {
        _this3.queryChoices(_this3.TEXTAREA.value);
      });
    }
    /**
     * Closes the editor.
     */

  }, {
    key: "close",
    value: function close() {
      this.removeHooksByKey('beforeKeyDown');

      _get(_getPrototypeOf(AutocompleteEditor.prototype), "close", this).call(this);
    }
    /**
     * Verifies result of validation or closes editor if user's cancelled changes. Re-renders WalkOnTable.
     *
     * @param {Boolean|undefined} result
     */

  }, {
    key: "discardEditor",
    value: function discardEditor(result) {
      _get(_getPrototypeOf(AutocompleteEditor.prototype), "discardEditor", this).call(this, result);

      this.hot.view.render();
    }
    /**
     * Prepares choices list based on applied argument.
     *
     * @private
     * @param {String} query
     */

  }, {
    key: "queryChoices",
    value: function queryChoices(query) {
      var _this4 = this;

      var source = this.cellProperties.source;
      this.query = query;

      if (typeof source === 'function') {
        source.call(this.cellProperties, query, function (choices) {
          _this4.rawChoices = choices;

          _this4.updateChoicesList(_this4.stripValuesIfNeeded(choices));
        });
      } else if (Array.isArray(source)) {
        this.rawChoices = source;
        this.updateChoicesList(this.stripValuesIfNeeded(source));
      } else {
        this.updateChoicesList([]);
      }
    }
    /**
     * Updates list of the possible completions to choose.
     *
     * @private
     * @param {Array} choicesList
     */

  }, {
    key: "updateChoicesList",
    value: function updateChoicesList(choicesList) {
      var pos = getCaretPosition(this.TEXTAREA);
      var endPos = getSelectionEndPosition(this.TEXTAREA);
      var sortByRelevanceSetting = this.cellProperties.sortByRelevance;
      var filterSetting = this.cellProperties.filter;
      var orderByRelevance = null;
      var highlightIndex = null;
      var choices = choicesList;

      if (sortByRelevanceSetting) {
        orderByRelevance = AutocompleteEditor.sortByRelevance(this.stripValueIfNeeded(this.getValue()), choices, this.cellProperties.filteringCaseSensitive);
      }

      var orderByRelevanceLength = Array.isArray(orderByRelevance) ? orderByRelevance.length : 0;

      if (filterSetting === false) {
        if (orderByRelevanceLength) {
          highlightIndex = orderByRelevance[0];
        }
      } else {
        var sorted = [];

        for (var i = 0, choicesCount = choices.length; i < choicesCount; i++) {
          if (sortByRelevanceSetting && orderByRelevanceLength <= i) {
            break;
          }

          if (orderByRelevanceLength) {
            sorted.push(choices[orderByRelevance[i]]);
          } else {
            sorted.push(choices[i]);
          }
        }

        highlightIndex = 0;
        choices = sorted;
      }

      this.strippedChoices = choices;
      this.htEditor.loadData(pivot([choices]));
      this.updateDropdownHeight();
      this.flipDropdownIfNeeded();

      if (this.cellProperties.strict === true) {
        this.highlightBestMatchingChoice(highlightIndex);
      }

      this.hot.listen(false);
      setCaretPosition(this.TEXTAREA, pos, pos === endPos ? void 0 : endPos);
    }
    /**
     * Checks where is enough place to open editor.
     *
     * @private
     * @returns {Boolean}
     */

  }, {
    key: "flipDropdownIfNeeded",
    value: function flipDropdownIfNeeded() {
      var textareaOffset = offset(this.TEXTAREA);
      var textareaHeight = outerHeight(this.TEXTAREA);
      var dropdownHeight = this.getDropdownHeight();
      var trimmingContainer = getTrimmingContainer(this.hot.view.wt.wtTable.TABLE);
      var trimmingContainerScrollTop = trimmingContainer.scrollTop;
      var headersHeight = outerHeight(this.hot.view.wt.wtTable.THEAD);
      var containerOffset = {
        row: 0,
        col: 0
      };

      if (trimmingContainer !== this.hot.rootWindow) {
        containerOffset = offset(trimmingContainer);
      }

      var spaceAbove = textareaOffset.top - containerOffset.top - headersHeight + trimmingContainerScrollTop;
      var spaceBelow = trimmingContainer.scrollHeight - spaceAbove - headersHeight - textareaHeight;
      var flipNeeded = dropdownHeight > spaceBelow && spaceAbove > spaceBelow;

      if (flipNeeded) {
        this.flipDropdown(dropdownHeight);
      } else {
        this.unflipDropdown();
      }

      this.limitDropdownIfNeeded(flipNeeded ? spaceAbove : spaceBelow, dropdownHeight);
      return flipNeeded;
    }
    /**
     * Checks if the internal table should generate scrollbar or could be rendered without it.
     *
     * @private
     * @param {Number} spaceAvailable
     * @param {Number} dropdownHeight
     */

  }, {
    key: "limitDropdownIfNeeded",
    value: function limitDropdownIfNeeded(spaceAvailable, dropdownHeight) {
      if (dropdownHeight > spaceAvailable) {
        var tempHeight = 0;
        var i = 0;
        var lastRowHeight = 0;
        var height = null;

        do {
          lastRowHeight = this.htEditor.getRowHeight(i) || this.htEditor.view.wt.wtSettings.settings.defaultRowHeight;
          tempHeight += lastRowHeight;
          i += 1;
        } while (tempHeight < spaceAvailable);

        height = tempHeight - lastRowHeight;

        if (this.htEditor.flipped) {
          this.htEditor.rootElement.style.top = "".concat(parseInt(this.htEditor.rootElement.style.top, 10) + dropdownHeight - height, "px");
        }

        this.setDropdownHeight(tempHeight - lastRowHeight);
      }
    }
    /**
     * Configures editor to open it at the top.
     *
     * @private
     * @param {Number} dropdownHeight
     */

  }, {
    key: "flipDropdown",
    value: function flipDropdown(dropdownHeight) {
      var dropdownStyle = this.htEditor.rootElement.style;
      dropdownStyle.position = 'absolute';
      dropdownStyle.top = "".concat(-dropdownHeight, "px");
      this.htEditor.flipped = true;
    }
    /**
     * Configures editor to open it at the bottom.
     *
     * @private
     */

  }, {
    key: "unflipDropdown",
    value: function unflipDropdown() {
      var dropdownStyle = this.htEditor.rootElement.style;

      if (dropdownStyle.position === 'absolute') {
        dropdownStyle.position = '';
        dropdownStyle.top = '';
      }

      this.htEditor.flipped = void 0;
    }
    /**
     * Updates width and height of the internal Handsontable's instance.
     *
     * @private
     */

  }, {
    key: "updateDropdownHeight",
    value: function updateDropdownHeight() {
      var currentDropdownWidth = this.htEditor.getColWidth(0) + getScrollbarWidth(this.hot.rootDocument) + 2;
      var trimDropdown = this.cellProperties.trimDropdown;
      this.htEditor.updateSettings({
        height: this.getDropdownHeight(),
        width: trimDropdown ? void 0 : currentDropdownWidth
      });
      this.htEditor.view.wt.wtTable.alignOverlaysWithTrimmingContainer();
    }
    /**
     * Sets new height of the internal Handsontable's instance.
     *
     * @private
     * @param {Number} height
     */

  }, {
    key: "setDropdownHeight",
    value: function setDropdownHeight(height) {
      this.htEditor.updateSettings({
        height: height
      });
    }
    /**
     * Creates new selection on specified row index, or deselects selected cells.
     *
     * @private
     * @param {Number|undefined} index
     */

  }, {
    key: "highlightBestMatchingChoice",
    value: function highlightBestMatchingChoice(index) {
      if (typeof index === 'number') {
        this.htEditor.selectCell(index, 0, void 0, void 0, void 0, false);
      } else {
        this.htEditor.deselectCell();
      }
    }
    /**
     * Calculates and return the internal Handsontable's height.
     *
     * @private
     * @returns {Number}
     */

  }, {
    key: "getDropdownHeight",
    value: function getDropdownHeight() {
      var firstRowHeight = this.htEditor.getInstance().getRowHeight(0) || 23;
      var visibleRows = this.cellProperties.visibleRows;
      return this.strippedChoices.length >= visibleRows ? visibleRows * firstRowHeight : this.strippedChoices.length * firstRowHeight + 8;
    }
    /**
     * Sanitizes value from potential dangerous tags.
     *
     * @private
     * @param {String} value
     * @returns {String}
     */

  }, {
    key: "stripValueIfNeeded",
    value: function stripValueIfNeeded(value) {
      return this.stripValuesIfNeeded([value])[0];
    }
    /**
     * Sanitizes an array of the values from potential dangerous tags.
     *
     * @private
     * @param {String[]} values
     * @returns {String[]}
     */

  }, {
    key: "stripValuesIfNeeded",
    value: function stripValuesIfNeeded(values) {
      var allowHtml = this.cellProperties.allowHtml;
      var stringifiedValues = arrayMap(values, function (value) {
        return stringify(value);
      });
      var strippedValues = arrayMap(stringifiedValues, function (value) {
        return allowHtml ? value : stripTags(value);
      });
      return strippedValues;
    }
    /**
     * Captures use of arrow down and up to control their behaviour.
     *
     * @private
     * @param {Number} keyCode
     * @returns {Boolean}
     */

  }, {
    key: "allowKeyEventPropagation",
    value: function allowKeyEventPropagation(keyCode) {
      var selectedRange = this.htEditor.getSelectedRangeLast();
      var selected = {
        row: selectedRange ? selectedRange.from.row : -1
      };
      var allowed = false;

      if (keyCode === KEY_CODES.ARROW_DOWN && selected.row > 0 && selected.row < this.htEditor.countRows() - 1) {
        allowed = true;
      }

      if (keyCode === KEY_CODES.ARROW_UP && selected.row > -1) {
        allowed = true;
      }

      return allowed;
    }
    /**
     * onBeforeKeyDown callback.
     *
     * @private
     * @param {KeyboardEvent} event
     */

  }, {
    key: "onBeforeKeyDown",
    value: function onBeforeKeyDown(event) {
      var _this5 = this;

      var priv = privatePool.get(this);
      priv.skipOne = false;

      if (isPrintableChar(event.keyCode) || event.keyCode === KEY_CODES.BACKSPACE || event.keyCode === KEY_CODES.DELETE || event.keyCode === KEY_CODES.INSERT) {
        var timeOffset = 0; // on ctl+c / cmd+c don't update suggestion list

        if (event.keyCode === KEY_CODES.C && (event.ctrlKey || event.metaKey)) {
          return;
        }

        if (!this.isOpened()) {
          timeOffset += 10;
        }

        if (this.htEditor) {
          this.hot._registerTimeout(function () {
            _this5.queryChoices(_this5.TEXTAREA.value);

            priv.skipOne = true;
          }, timeOffset);
        }
      }

      _get(_getPrototypeOf(AutocompleteEditor.prototype), "onBeforeKeyDown", this).call(this, event);
    }
  }]);

  return AutocompleteEditor;
}(HandsontableEditor);
/**
 * Filters and sorts by relevance.
 *
 * @param value
 * @param choices
 * @param caseSensitive
 * @returns {Number[]} array of indexes in original choices array
 */


AutocompleteEditor.sortByRelevance = function (value, choices, caseSensitive) {
  var choicesRelevance = [];
  var currentItem;
  var valueLength = value.length;
  var valueIndex;
  var charsLeft;
  var result = [];
  var i;
  var choicesCount = choices.length;

  if (valueLength === 0) {
    for (i = 0; i < choicesCount; i++) {
      result.push(i);
    }

    return result;
  }

  for (i = 0; i < choicesCount; i++) {
    currentItem = stripTags(stringify(choices[i]));

    if (caseSensitive) {
      valueIndex = currentItem.indexOf(value);
    } else {
      valueIndex = currentItem.toLowerCase().indexOf(value.toLowerCase());
    }

    if (valueIndex !== -1) {
      charsLeft = currentItem.length - valueIndex - valueLength;
      choicesRelevance.push({
        baseIndex: i,
        index: valueIndex,
        charsLeft: charsLeft,
        value: currentItem
      });
    }
  }

  choicesRelevance.sort(function (a, b) {
    if (b.index === -1) {
      return -1;
    }

    if (a.index === -1) {
      return 1;
    }

    if (a.index < b.index) {
      return -1;
    } else if (b.index < a.index) {
      return 1;
    } else if (a.index === b.index) {
      if (a.charsLeft < b.charsLeft) {
        return -1;
      } else if (a.charsLeft > b.charsLeft) {
        return 1;
      }
    }

    return 0;
  });

  for (i = 0, choicesCount = choicesRelevance.length; i < choicesCount; i++) {
    result.push(choicesRelevance[i].baseIndex);
  }

  return result;
};

export default AutocompleteEditor;