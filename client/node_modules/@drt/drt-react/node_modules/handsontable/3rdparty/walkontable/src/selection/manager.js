"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.array.unscopables.flat.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
require("core-js/modules/esnext.iterator.map.js");
var _element = require("../../../../helpers/dom/element");
var _scanner2 = require("./scanner");
var _border = _interopRequireDefault(require("./border/border"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _classPrivateMethodInitSpec(e, a) { _checkPrivateRedeclaration(e, a), a.add(e); }
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * Module responsible for rendering selections (CSS classes) and borders based on the
 * collection of the Selection instances provided throughout the `selections` Walkontable
 * setting.
 *
 * @private
 */
var _activeOverlaysWot = /*#__PURE__*/new WeakMap();
var _selections = /*#__PURE__*/new WeakMap();
var _scanner = /*#__PURE__*/new WeakMap();
var _appliedClasses = /*#__PURE__*/new WeakMap();
var _destroyListeners = /*#__PURE__*/new WeakMap();
var _selectionBorders = /*#__PURE__*/new WeakMap();
var _SelectionManager_brand = /*#__PURE__*/new WeakSet();
class SelectionManager {
  constructor(selections) {
    /**
     * Resets the elements to their initial state (remove the CSS classes that are added in the
     * previous render cycle).
     */
    _classPrivateMethodInitSpec(this, _SelectionManager_brand);
    /**
     * The overlay's Walkontable instance that are currently processed.
     *
     * @type {Walkontable}
     */
    _classPrivateFieldInitSpec(this, _activeOverlaysWot, void 0);
    /**
     * The Highlight instance that holds Selections instances within it.
     *
     * @type {Highlight|null}
     */
    _classPrivateFieldInitSpec(this, _selections, void 0);
    /**
     * The SelectionScanner allows to scan and collect the cell and header elements that matches
     * to the coords defined in the selections.
     *
     * @type {SelectionScanner}
     */
    _classPrivateFieldInitSpec(this, _scanner, new _scanner2.SelectionScanner());
    /**
     * The Map tracks applied CSS classes. It's used to reset the elements state to their initial state.
     *
     * @type {WeakMap}
     */
    _classPrivateFieldInitSpec(this, _appliedClasses, new WeakMap());
    /**
     * The Map tracks applied "destroy" listeners for Selection instances.
     *
     * @type {WeakMap}
     */
    _classPrivateFieldInitSpec(this, _destroyListeners, new WeakSet());
    /**
     * The Map holds references to Border classes for Selection instances which requires that when
     * the "border" setting is defined.
     *
     * @type {Map}
     */
    _classPrivateFieldInitSpec(this, _selectionBorders, new Map());
    _classPrivateFieldSet(_selections, this, selections);
  }

  /**
   * Sets the active Walkontable instance.
   *
   * @param {Walkontable} activeWot The overlays or master Walkontable instance.
   * @returns {SelectionManager}
   */
  setActiveOverlay(activeWot) {
    _classPrivateFieldSet(_activeOverlaysWot, this, activeWot);
    _classPrivateFieldGet(_scanner, this).setActiveOverlay(_classPrivateFieldGet(_activeOverlaysWot, this));
    if (!_classPrivateFieldGet(_appliedClasses, this).has(_classPrivateFieldGet(_activeOverlaysWot, this))) {
      _classPrivateFieldGet(_appliedClasses, this).set(_classPrivateFieldGet(_activeOverlaysWot, this), new Set());
    }
    return this;
  }

  /**
   * Gets the Selection instance of the "focus" type.
   *
   * @returns {Selection|null}
   */
  getFocusSelection() {
    return _classPrivateFieldGet(_selections, this) !== null ? _classPrivateFieldGet(_selections, this).getFocus() : null;
  }

  /**
   * Gets the Selection instance of the "area" type.
   *
   * @returns {Selection|null}
   */
  getAreaSelection() {
    return _classPrivateFieldGet(_selections, this) !== null ? _classPrivateFieldGet(_selections, this).createLayeredArea() : null;
  }

  /**
   * Gets the Border instance associated with Selection instance.
   *
   * @param {Selection} selection The selection instance.
   * @returns {Border|null} Returns the Border instance (new for each overlay Walkontable instance).
   */
  getBorderInstance(selection) {
    if (!selection.settings.border) {
      return null;
    }
    if (_classPrivateFieldGet(_selectionBorders, this).has(selection)) {
      const borders = _classPrivateFieldGet(_selectionBorders, this).get(selection);
      if (borders.has(_classPrivateFieldGet(_activeOverlaysWot, this))) {
        return borders.get(_classPrivateFieldGet(_activeOverlaysWot, this));
      }
      const border = new _border.default(_classPrivateFieldGet(_activeOverlaysWot, this), selection.settings);
      borders.set(_classPrivateFieldGet(_activeOverlaysWot, this), border);
      return border;
    }
    const border = new _border.default(_classPrivateFieldGet(_activeOverlaysWot, this), selection.settings);
    _classPrivateFieldGet(_selectionBorders, this).set(selection, new Map([[_classPrivateFieldGet(_activeOverlaysWot, this), border]]));
    return border;
  }

  /**
   * Gets all Border instances associated with Selection instance for all overlays.
   *
   * @param {Selection} selection The selection instance.
   * @returns {Border[]}
   */
  getBorderInstances(selection) {
    var _classPrivateFieldGet2, _classPrivateFieldGet3;
    return Array.from((_classPrivateFieldGet2 = (_classPrivateFieldGet3 = _classPrivateFieldGet(_selectionBorders, this).get(selection)) === null || _classPrivateFieldGet3 === void 0 ? void 0 : _classPrivateFieldGet3.values()) !== null && _classPrivateFieldGet2 !== void 0 ? _classPrivateFieldGet2 : []);
  }

  /**
   * Destroys the Border instance associated with Selection instance.
   *
   * @param {Selection} selection The selection instance.
   */
  destroyBorders(selection) {
    _classPrivateFieldGet(_selectionBorders, this).get(selection).forEach(border => border.destroy());
    _classPrivateFieldGet(_selectionBorders, this).delete(selection);
  }

  /**
   * Renders all the selections (add CSS classes to cells and draw borders).
   *
   * @param {boolean} fastDraw Indicates the render cycle type (fast/slow).
   */
  render(fastDraw) {
    if (_classPrivateFieldGet(_selections, this) === null) {
      return;
    }
    if (fastDraw) {
      // there was no rerender, so we need to remove classNames by ourselves
      _assertClassBrand(_SelectionManager_brand, this, _resetCells).call(this);
    }
    const selections = Array.from(_classPrivateFieldGet(_selections, this));
    const classNamesMap = new Map();
    const headerAttributesMap = new Map();
    for (let i = 0; i < selections.length; i++) {
      const selection = selections[i];
      const {
        className,
        headerAttributes,
        createLayers,
        selectionType
      } = selection.settings;
      if (!_classPrivateFieldGet(_destroyListeners, this).has(selection)) {
        _classPrivateFieldGet(_destroyListeners, this).add(selection);
        selection.addLocalHook('destroy', () => this.destroyBorders(selection));
      }
      const borderInstance = this.getBorderInstance(selection);
      if (selection.isEmpty()) {
        borderInstance === null || borderInstance === void 0 || borderInstance.disappear();
        continue; // eslint-disable-line no-continue
      }
      if (className) {
        const elements = _classPrivateFieldGet(_scanner, this).setActiveSelection(selection).scan();
        elements.forEach(element => {
          if (classNamesMap.has(element)) {
            const classNamesLayers = classNamesMap.get(element);
            if (classNamesLayers.has(className) && createLayers === true) {
              classNamesLayers.set(className, classNamesLayers.get(className) + 1);
            } else {
              classNamesLayers.set(className, 1);
            }
          } else {
            classNamesMap.set(element, new Map([[className, 1]]));
          }
          if (headerAttributes) {
            if (!headerAttributesMap.has(element)) {
              headerAttributesMap.set(element, []);
            }
            if (element.nodeName === 'TH') {
              headerAttributesMap.get(element).push(...headerAttributes);
            }
          }
        });
      }
      const corners = selection.getCorners();
      _classPrivateFieldGet(_activeOverlaysWot, this).getSetting('onBeforeDrawBorders', corners, selectionType);
      borderInstance === null || borderInstance === void 0 || borderInstance.appear(corners);
    }
    classNamesMap.forEach((classNamesLayers, element) => {
      var _classPrivateFieldGet4;
      const classNames = Array.from(classNamesLayers).map(_ref => {
        let [className, occurrenceCount] = _ref;
        if (occurrenceCount === 1) {
          return className;
        }
        return [className, ...Array.from({
          length: occurrenceCount - 1
        }, (_, i) => `${className}-${i + 1}`)];
      }).flat();
      classNames.forEach(className => _classPrivateFieldGet(_appliedClasses, this).get(_classPrivateFieldGet(_activeOverlaysWot, this)).add(className));
      (0, _element.addClass)(element, classNames);
      if (element.nodeName === 'TD' && Array.isArray((_classPrivateFieldGet4 = _classPrivateFieldGet(_selections, this).options) === null || _classPrivateFieldGet4 === void 0 ? void 0 : _classPrivateFieldGet4.cellAttributes)) {
        (0, _element.setAttribute)(element, _classPrivateFieldGet(_selections, this).options.cellAttributes);
      }
    });

    // Set the attributes for the headers if they're focused.
    Array.from(headerAttributesMap.keys()).forEach(element => {
      (0, _element.setAttribute)(element, [...headerAttributesMap.get(element)]);
    });
  }
}
exports.SelectionManager = SelectionManager;
function _resetCells() {
  const appliedOverlaysClasses = _classPrivateFieldGet(_appliedClasses, this).get(_classPrivateFieldGet(_activeOverlaysWot, this));
  const classesToRemove = _classPrivateFieldGet(_activeOverlaysWot, this).wtSettings.getSetting('onBeforeRemoveCellClassNames');
  if (Array.isArray(classesToRemove)) {
    for (let i = 0; i < classesToRemove.length; i++) {
      appliedOverlaysClasses.add(classesToRemove[i]);
    }
  }
  appliedOverlaysClasses.forEach(className => {
    var _classPrivateFieldGet5, _classPrivateFieldGet6;
    const nodes = _classPrivateFieldGet(_activeOverlaysWot, this).wtTable.TABLE.querySelectorAll(`.${className}`);
    let cellAttributes = [];
    if (Array.isArray((_classPrivateFieldGet5 = _classPrivateFieldGet(_selections, this).options) === null || _classPrivateFieldGet5 === void 0 ? void 0 : _classPrivateFieldGet5.cellAttributes)) {
      cellAttributes = _classPrivateFieldGet(_selections, this).options.cellAttributes.map(el => el[0]);
    }
    if (Array.isArray((_classPrivateFieldGet6 = _classPrivateFieldGet(_selections, this).options) === null || _classPrivateFieldGet6 === void 0 ? void 0 : _classPrivateFieldGet6.headerAttributes)) {
      cellAttributes = [...cellAttributes, ..._classPrivateFieldGet(_selections, this).options.headerAttributes.map(el => el[0])];
    }
    for (let i = 0, len = nodes.length; i < len; i++) {
      (0, _element.removeClass)(nodes[i], className);
      (0, _element.removeAttribute)(nodes[i], cellAttributes);
    }
  });
  appliedOverlaysClasses.clear();
}