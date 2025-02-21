"use strict";

exports.__esModule = true;
require("core-js/modules/es.array.push.js");
var _event = _interopRequireDefault(require("../event"));
var _overlays = _interopRequireDefault(require("../overlays"));
var _overlay = require("../overlay");
var _settings = _interopRequireDefault(require("../settings"));
var _master = _interopRequireDefault(require("../table/master"));
var _viewport = _interopRequireDefault(require("../viewport"));
var _base = _interopRequireDefault(require("./_base"));
var _manager = require("../selection/manager");
var _object = require("../../../../helpers/object");
var _element = require("../../../../helpers/dom/element");
var _stylesHandler = require("../utils/stylesHandler");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
/**
 * @class Walkontable
 */
class Walkontable extends _base.default {
  /**
   * @param {HTMLTableElement} table Main table.
   * @param {SettingsPure} settings The Walkontable settings.
   */
  constructor(table, settings) {
    super(table, new _settings.default(settings));
    this.stylesHandler = new _stylesHandler.StylesHandler(this.domBindings);
    const facadeGetter = this.wtSettings.getSetting('facade', this); // todo rethink. I would like to have no access to facade from the internal scope.

    this.wtTable = new _master.default(this.getTableDao(), facadeGetter, this.domBindings, this.wtSettings);
    this.wtViewport = new _viewport.default(this.getViewportDao(), this.domBindings, this.wtSettings, this.eventManager, this.wtTable);
    this.selectionManager = new _manager.SelectionManager(this.wtSettings.getSetting('selections'));
    this.wtEvent = new _event.default(facadeGetter, this.domBindings, this.wtSettings, this.eventManager, this.wtTable, this.selectionManager);
    this.wtOverlays = new _overlays.default(
    // TODO create DAO and remove reference to the Walkontable instance.
    this, facadeGetter, this.domBindings, this.wtSettings, this.eventManager, this.wtTable);
    this.exportSettingsAsClassNames();
    this.findOriginalHeaders();
  }

  /**
   * Export settings as class names added to the parent element of the table.
   */
  exportSettingsAsClassNames() {
    const toExport = {
      rowHeaders: 'htRowHeaders',
      columnHeaders: 'htColumnHeaders'
    };
    const allClassNames = [];
    const newClassNames = [];
    (0, _object.objectEach)(toExport, (className, key) => {
      if (this.wtSettings.getSetting(key).length) {
        newClassNames.push(className);
      }
      allClassNames.push(className);
    });
    (0, _element.removeClass)(this.wtTable.wtRootElement.parentNode, allClassNames);
    (0, _element.addClass)(this.wtTable.wtRootElement.parentNode, newClassNames);
  }

  /**
   * Gets the overlay instance by its name.
   *
   * @param {'inline_start'|'top'|'top_inline_start_corner'|'bottom'|'bottom_inline_start_corner'} overlayName The overlay name.
   * @returns {Overlay | null}
   */
  getOverlayByName(overlayName) {
    var _this$wtOverlays;
    if (!_overlay.CLONE_TYPES.includes(overlayName)) {
      return null;
    }
    const camelCaseOverlay = overlayName.replace(/_([a-z])/g, match => match[1].toUpperCase());
    return (_this$wtOverlays = this.wtOverlays[`${camelCaseOverlay}Overlay`]) !== null && _this$wtOverlays !== void 0 ? _this$wtOverlays : null;
  }

  /**
   * @returns {ViewportDao}
   */
  getViewportDao() {
    const wot = this;
    return {
      get wot() {
        return wot;
      },
      get topOverlayTrimmingContainer() {
        return wot.wtOverlays.topOverlay.trimmingContainer;
      },
      get inlineStartOverlayTrimmingContainer() {
        return wot.wtOverlays.inlineStartOverlay.trimmingContainer;
      },
      get topScrollPosition() {
        return wot.wtOverlays.topOverlay.getScrollPosition();
      },
      get topParentOffset() {
        return wot.wtOverlays.topOverlay.getTableParentOffset();
      },
      get inlineStartScrollPosition() {
        return wot.wtOverlays.inlineStartOverlay.getScrollPosition();
      },
      get inlineStartParentOffset() {
        return wot.wtOverlays.inlineStartOverlay.getTableParentOffset();
      },
      get topOverlay() {
        return wot.wtOverlays.topOverlay; // TODO refactoring: move outside dao, use IOC
      },
      get inlineStartOverlay() {
        return wot.wtOverlays.inlineStartOverlay; // TODO refactoring: move outside dao, use IOC
      },
      get bottomOverlay() {
        return wot.wtOverlays.bottomOverlay; // TODO refactoring: move outside dao, use IOC
      }
    };
  }
}
exports.default = Walkontable;