"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
var _element = require("../../../helpers/dom/element");
var _function = require("../../../helpers/function");
var _feature = require("../../../helpers/feature");
var _browser = require("../../../helpers/browser");
var _mixed = require("../../../helpers/mixed");
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * @class Event
 */
var _wtSettings = /*#__PURE__*/new WeakMap();
var _domBindings = /*#__PURE__*/new WeakMap();
var _wtTable = /*#__PURE__*/new WeakMap();
var _selectionManager = /*#__PURE__*/new WeakMap();
var _parent = /*#__PURE__*/new WeakMap();
var _eventManager = /*#__PURE__*/new WeakMap();
var _facadeGetter = /*#__PURE__*/new WeakMap();
var _selectedCellBeforeTouchEnd = /*#__PURE__*/new WeakMap();
var _dblClickTimeout = /*#__PURE__*/new WeakMap();
var _dblClickOrigin = /*#__PURE__*/new WeakMap();
class Event {
  /**
   * @param {FacadeGetter} facadeGetter Gets an instance facade.
   * @param {DomBindings} domBindings Bindings into dom.
   * @param {Settings} wtSettings The walkontable settings.
   * @param {EventManager} eventManager The walkontable event manager.
   * @param {Table} wtTable The table.
   * @param {SelectionManager} selectionManager Selections.
   * @param {Event} [parent=null] The main Event instance.
   */
  constructor(facadeGetter, domBindings, wtSettings, eventManager, wtTable, selectionManager) {
    let parent = arguments.length > 6 && arguments[6] !== undefined ? arguments[6] : null;
    _classPrivateFieldInitSpec(this, _wtSettings, void 0);
    _classPrivateFieldInitSpec(this, _domBindings, void 0);
    _classPrivateFieldInitSpec(this, _wtTable, void 0);
    _classPrivateFieldInitSpec(this, _selectionManager, void 0);
    _classPrivateFieldInitSpec(this, _parent, void 0);
    /**
     * Instance of {@link EventManager}.
     *
     * @type {EventManager}
     */
    _classPrivateFieldInitSpec(this, _eventManager, void 0);
    /**
     * Should be use only for passing face called external origin methods, like registered event listeners.
     * It provides backward compatibility by getting instance facade.
     *
     * @todo Consider about removing this from Event class, because it make relationship into facade (implicit circular
     *   dependency).
     * @todo Con. Maybe passing listener caller as an ioc from faced resolves this issue. To rethink later.
     *
     * @type {FacadeGetter}
     */
    _classPrivateFieldInitSpec(this, _facadeGetter, void 0);
    /**
     * @type {boolean}
     */
    _classPrivateFieldInitSpec(this, _selectedCellBeforeTouchEnd, void 0);
    /**
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _dblClickTimeout, [null, null]);
    /**
     * @type {number[]}
     */
    _classPrivateFieldInitSpec(this, _dblClickOrigin, [null, null]);
    _classPrivateFieldSet(_wtSettings, this, wtSettings);
    _classPrivateFieldSet(_domBindings, this, domBindings);
    _classPrivateFieldSet(_wtTable, this, wtTable);
    _classPrivateFieldSet(_selectionManager, this, selectionManager);
    _classPrivateFieldSet(_parent, this, parent);
    _classPrivateFieldSet(_eventManager, this, eventManager);
    _classPrivateFieldSet(_facadeGetter, this, facadeGetter);
    this.registerEvents();
  }

  /**
   * Adds listeners for mouse and touch events.
   *
   * @private
   */
  registerEvents() {
    _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'contextmenu', event => this.onContextMenu(event));
    _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).TABLE, 'mouseover', event => this.onMouseOver(event));
    _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).TABLE, 'mouseout', event => this.onMouseOut(event));
    const initTouchEvents = () => {
      _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'touchstart', event => this.onTouchStart(event));
      _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'touchend', event => this.onTouchEnd(event));
      if (!this.momentumScrolling) {
        this.momentumScrolling = {};
      }
      _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'scroll', () => {
        clearTimeout(this.momentumScrolling._timeout);
        if (!this.momentumScrolling.ongoing) {
          _classPrivateFieldGet(_wtSettings, this).getSetting('onBeforeTouchScroll');
        }
        this.momentumScrolling.ongoing = true;
        this.momentumScrolling._timeout = setTimeout(() => {
          if (!this.touchApplied) {
            this.momentumScrolling.ongoing = false;
            _classPrivateFieldGet(_wtSettings, this).getSetting('onAfterMomentumScroll');
          }
        }, 200);
      });
    };
    const initMouseEvents = () => {
      _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'mouseup', event => this.onMouseUp(event));
      _classPrivateFieldGet(_eventManager, this).addEventListener(_classPrivateFieldGet(_wtTable, this).holder, 'mousedown', event => this.onMouseDown(event));
    };
    if ((0, _browser.isMobileBrowser)()) {
      initTouchEvents();
    } else {
      // PC like devices which support both methods (touchscreen and ability to plug-in mouse).
      if ((0, _feature.isTouchSupported)()) {
        initTouchEvents();
      }
      initMouseEvents();
    }
  }

  /**
   * Checks if an element is already selected.
   *
   * @private
   * @param {Element} touchTarget An element to check.
   * @returns {boolean}
   */
  selectedCellWasTouched(touchTarget) {
    const cellUnderFinger = this.parentCell(touchTarget);
    const coordsOfCellUnderFinger = cellUnderFinger.coords;
    if (_classPrivateFieldGet(_selectedCellBeforeTouchEnd, this) && coordsOfCellUnderFinger) {
      const [rowTouched, rowSelected] = [coordsOfCellUnderFinger.row, _classPrivateFieldGet(_selectedCellBeforeTouchEnd, this).from.row];
      const [colTouched, colSelected] = [coordsOfCellUnderFinger.col, _classPrivateFieldGet(_selectedCellBeforeTouchEnd, this).from.col];
      return rowTouched === rowSelected && colTouched === colSelected;
    }
    return false;
  }

  /**
   * Gets closest TD or TH element.
   *
   * @private
   * @param {Element} elem An element from the traversing starts.
   * @returns {object} Contains coordinates and reference to TD or TH if it exists. Otherwise it's empty object.
   */
  parentCell(elem) {
    const cell = {};
    const TABLE = _classPrivateFieldGet(_wtTable, this).TABLE;
    const TD = (0, _element.closestDown)(elem, ['TD', 'TH'], TABLE);
    if (TD) {
      cell.coords = _classPrivateFieldGet(_wtTable, this).getCoords(TD);
      cell.TD = TD;
    } else if ((0, _element.hasClass)(elem, 'wtBorder') && (0, _element.hasClass)(elem, 'current')) {
      cell.coords = _classPrivateFieldGet(_selectionManager, this).getFocusSelection().cellRange.highlight;
      cell.TD = _classPrivateFieldGet(_wtTable, this).getCell(cell.coords);
    } else if ((0, _element.hasClass)(elem, 'wtBorder') && (0, _element.hasClass)(elem, 'area')) {
      if (_classPrivateFieldGet(_selectionManager, this).getAreaSelection().cellRange) {
        cell.coords = _classPrivateFieldGet(_selectionManager, this).getAreaSelection().cellRange.to;
        cell.TD = _classPrivateFieldGet(_wtTable, this).getCell(cell.coords);
      }
    }
    return cell;
  }

  /**
   * OnMouseDown callback.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onMouseDown(event) {
    const activeElement = _classPrivateFieldGet(_domBindings, this).rootDocument.activeElement;
    const getParentNode = (0, _function.partial)(_element.getParent, event.target);
    const realTarget = event.target;

    // ignore non-TD focusable elements from mouse down processing
    // (https://github.com/handsontable/handsontable/issues/3555)
    if (!['TD', 'TH'].includes(activeElement.nodeName) && (realTarget === activeElement || getParentNode(0) === activeElement || getParentNode(1) === activeElement)) {
      return;
    }
    const cell = this.parentCell(realTarget);
    if ((0, _element.hasClass)(realTarget, 'corner')) {
      _classPrivateFieldGet(_wtSettings, this).getSetting('onCellCornerMouseDown', event, realTarget);
    } else if (cell.TD && _classPrivateFieldGet(_wtSettings, this).has('onCellMouseDown')) {
      this.callListener('onCellMouseDown', event, cell.coords, cell.TD);
    }

    // doubleclick reacts only for left mouse button or from touch events
    if ((event.button === 0 || this.touchApplied) && cell.TD) {
      _classPrivateFieldGet(_dblClickOrigin, this)[0] = cell.TD;
      clearTimeout(_classPrivateFieldGet(_dblClickTimeout, this)[0]);
      _classPrivateFieldGet(_dblClickTimeout, this)[0] = setTimeout(() => {
        _classPrivateFieldGet(_dblClickOrigin, this)[0] = null;
      }, 1000);
    }
  }

  /**
   * OnContextMenu callback.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onContextMenu(event) {
    if (_classPrivateFieldGet(_wtSettings, this).has('onCellContextMenu')) {
      const cell = this.parentCell(event.target);
      if (cell.TD) {
        this.callListener('onCellContextMenu', event, cell.coords, cell.TD);
      }
    }
  }

  /**
   * OnMouseOver callback.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onMouseOver(event) {
    if (!_classPrivateFieldGet(_wtSettings, this).has('onCellMouseOver')) {
      return;
    }
    const table = _classPrivateFieldGet(_wtTable, this).TABLE;
    const td = (0, _element.closestDown)(event.target, ['TD', 'TH'], table);
    const parent = _classPrivateFieldGet(_parent, this) || this;
    if (td && td !== parent.lastMouseOver && (0, _element.isChildOf)(td, table)) {
      parent.lastMouseOver = td;
      this.callListener('onCellMouseOver', event, _classPrivateFieldGet(_wtTable, this).getCoords(td), td);
    }
  }

  /**
   * OnMouseOut callback.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onMouseOut(event) {
    if (!_classPrivateFieldGet(_wtSettings, this).has('onCellMouseOut')) {
      return;
    }
    const table = _classPrivateFieldGet(_wtTable, this).TABLE;
    const lastTD = (0, _element.closestDown)(event.target, ['TD', 'TH'], table);
    const nextTD = (0, _element.closestDown)(event.relatedTarget, ['TD', 'TH'], table);
    const parent = _classPrivateFieldGet(_parent, this) || this;
    if (lastTD && lastTD !== nextTD && (0, _element.isChildOf)(lastTD, table)) {
      this.callListener('onCellMouseOut', event, _classPrivateFieldGet(_wtTable, this).getCoords(lastTD), lastTD);
      if (nextTD === null) {
        parent.lastMouseOver = null;
      }
    }
  }

  /**
   * OnMouseUp callback.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onMouseUp(event) {
    const cell = this.parentCell(event.target);
    if (cell.TD && _classPrivateFieldGet(_wtSettings, this).has('onCellMouseUp')) {
      this.callListener('onCellMouseUp', event, cell.coords, cell.TD);
    }

    // if not left mouse button, and the origin event is not comes from touch
    if (event.button !== 0 && !this.touchApplied) {
      return;
    }
    if (cell.TD === _classPrivateFieldGet(_dblClickOrigin, this)[0] && cell.TD === _classPrivateFieldGet(_dblClickOrigin, this)[1]) {
      if ((0, _element.hasClass)(event.target, 'corner')) {
        this.callListener('onCellCornerDblClick', event, cell.coords, cell.TD);
      } else {
        this.callListener('onCellDblClick', event, cell.coords, cell.TD);
      }
      _classPrivateFieldGet(_dblClickOrigin, this)[0] = null;
      _classPrivateFieldGet(_dblClickOrigin, this)[1] = null;
    } else if (cell.TD === _classPrivateFieldGet(_dblClickOrigin, this)[0]) {
      _classPrivateFieldGet(_dblClickOrigin, this)[1] = cell.TD;
      clearTimeout(_classPrivateFieldGet(_dblClickTimeout, this)[1]);
      _classPrivateFieldGet(_dblClickTimeout, this)[1] = setTimeout(() => {
        _classPrivateFieldGet(_dblClickOrigin, this)[1] = null;
      }, 500);
    }
  }

  /**
   * OnTouchStart callback. Simulates mousedown event.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onTouchStart(event) {
    _classPrivateFieldSet(_selectedCellBeforeTouchEnd, this, _classPrivateFieldGet(_selectionManager, this).getFocusSelection().cellRange);
    this.touchApplied = true;
    this.onMouseDown(event);
  }

  /**
   * OnTouchEnd callback. Simulates mouseup event.
   *
   * @private
   * @param {MouseEvent} event The mouse event object.
   */
  onTouchEnd(event) {
    var _this$parentCell;
    const target = event.target;
    const parentCellCoords = (_this$parentCell = this.parentCell(target)) === null || _this$parentCell === void 0 ? void 0 : _this$parentCell.coords;
    const isCellsRange = (0, _mixed.isDefined)(parentCellCoords) && parentCellCoords.row >= 0 && parentCellCoords.col >= 0;
    const isEventCancelable = event.cancelable && isCellsRange && _classPrivateFieldGet(_wtSettings, this).getSetting('isDataViewInstance');

    // To prevent accidental redirects or other actions that the interactive elements (e.q "A" link) do
    // while the cell is highlighted, all touch events that are triggered on different cells are
    // "preventDefault"'ed. The user can interact with the element (e.q. click on the link that opens
    // a new page) only when the same cell was previously selected (see related PR #7980).
    if (isEventCancelable) {
      const interactiveElements = ['A', 'BUTTON', 'INPUT'];

      // For browsers that use the WebKit as an engine (excluding Safari), there is a bug. The prevent
      // default has to be called all the time. Otherwise, the second tap won't be triggered (probably
      // caused by the native ~300ms delay - https://webkit.org/blog/5610/more-responsive-tapping-on-ios/).
      // To make the interactive elements work, the event target element has to be check. If the element
      // matches the allow-list, the event is not prevented.
      if ((0, _browser.isIOS)() && ((0, _browser.isChromeWebKit)() || (0, _browser.isFirefoxWebKit)()) && this.selectedCellWasTouched(target) && !interactiveElements.includes(target.tagName)) {
        event.preventDefault();
      } else if (!this.selectedCellWasTouched(target)) {
        // For other browsers, prevent default is fired only for the first tap and only when the previous
        // highlighted cell was different.
        event.preventDefault();
      }
    }
    this.onMouseUp(event);
    this.touchApplied = false;
  }

  /**
   * Call listener with backward compatibility.
   *
   * @private
   * @param {string} name Name of listener.
   * @param {MouseEvent} event The event object.
   * @param {CellCoords} coords Coordinates.
   * @param {HTMLElement} target Event target.
   */
  callListener(name, event, coords, target) {
    const listener = _classPrivateFieldGet(_wtSettings, this).getSettingPure(name);
    if (listener) {
      listener(event, coords, target, _classPrivateFieldGet(_facadeGetter, this).call(this));
    }
  }

  /**
   * Clears double-click timeouts and destroys the internal eventManager instance.
   */
  destroy() {
    clearTimeout(_classPrivateFieldGet(_dblClickTimeout, this)[0]);
    clearTimeout(_classPrivateFieldGet(_dblClickTimeout, this)[1]);
    _classPrivateFieldGet(_eventManager, this).destroy();
  }
}
var _default = exports.default = Event;