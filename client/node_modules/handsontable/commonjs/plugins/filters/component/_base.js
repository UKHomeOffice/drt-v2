"use strict";

exports.__esModule = true;
exports.default = void 0;

var _array = require("../../../helpers/array");

var _object = require("../../../helpers/object");

var _localHooks = _interopRequireDefault(require("../../../mixins/localHooks"));

var _stateSaver = _interopRequireDefault(require("../../../mixins/stateSaver"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * @plugin Filters
 * @class BaseComponent
 */
var BaseComponent =
/*#__PURE__*/
function () {
  function BaseComponent(hotInstance) {
    _classCallCheck(this, BaseComponent);

    this.hot = hotInstance;
    /**
     * List of registered component UI elements.
     *
     * @type {Array}
     */

    this.elements = [];
    /**
     * Flag which determines if element is hidden.
     *
     * @type {Boolean}
     */

    this.hidden = false;
  }
  /**
   * Reset elements to their initial state.
   */


  _createClass(BaseComponent, [{
    key: "reset",
    value: function reset() {
      (0, _array.arrayEach)(this.elements, function (ui) {
        return ui.reset();
      });
    }
    /**
     * Hide component.
     */

  }, {
    key: "hide",
    value: function hide() {
      this.hidden = true;
    }
    /**
     * Show component.
     */

  }, {
    key: "show",
    value: function show() {
      this.hidden = false;
    }
    /**
     * Check if component is hidden.
     *
     * @returns {Boolean}
     */

  }, {
    key: "isHidden",
    value: function isHidden() {
      return this.hidden;
    }
    /**
     * Destroy element.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this.clearLocalHooks();
      (0, _array.arrayEach)(this.elements, function (ui) {
        return ui.destroy();
      });
      this.elements = null;
      this.hot = null;
    }
  }]);

  return BaseComponent;
}();

(0, _object.mixin)(BaseComponent, _localHooks.default);
(0, _object.mixin)(BaseComponent, _stateSaver.default);
var _default = BaseComponent;
exports.default = _default;