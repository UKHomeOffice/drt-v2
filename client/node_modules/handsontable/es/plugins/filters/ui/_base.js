import "core-js/modules/es.string.starts-with";

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import { clone, extend, mixin, objectEach } from '../../../helpers/object';
import localHooks from '../../../mixins/localHooks';
import EventManager from '../../../eventManager';
import { addClass } from '../../../helpers/dom/element';
import { arrayEach } from '../../../helpers/array';
import * as C from '../../../i18n/constants';
var STATE_BUILT = 'built';
var STATE_BUILDING = 'building';
var EVENTS_TO_REGISTER = ['click', 'input', 'keydown', 'keypress', 'keyup', 'focus', 'blur', 'change'];
/**
 * @class
 * @private
 */

var BaseUI =
/*#__PURE__*/
function () {
  _createClass(BaseUI, null, [{
    key: "DEFAULTS",
    get: function get() {
      return clone({
        className: '',
        value: '',
        tagName: 'div',
        children: [],
        wrapIt: true
      });
    }
  }]);

  function BaseUI(hotInstance, options) {
    _classCallCheck(this, BaseUI);

    /**
     * Instance of Handsontable.
     *
     * @type {Core}
     */
    this.hot = hotInstance;
    /**
     * Instance of EventManager.
     *
     * @type {EventManager}
     */

    this.eventManager = new EventManager(this);
    /**
     * List of element options.
     *
     * @type {Object}
     */

    this.options = extend(BaseUI.DEFAULTS, options);
    /**
     * Build root DOM element.
     *
     * @type {Element}
     * @private
     */

    this._element = this.hot.rootDocument.createElement(this.options.wrapIt ? 'div' : this.options.tagName);
    /**
     * Flag which determines build state of element.
     *
     * @type {Boolean}
     */

    this.buildState = false;
  }
  /**
   * Set the element value.
   *
   * @returns {*}
   */


  _createClass(BaseUI, [{
    key: "setValue",
    value: function setValue(value) {
      this.options.value = value;
      this.update();
    }
    /**
     * Get the element value.
     *
     * @returns {*}
     */

  }, {
    key: "getValue",
    value: function getValue() {
      return this.options.value;
    }
    /**
     * Get element as a DOM object.
     *
     * @returns {Element}
     */

  }, {
    key: "isBuilt",

    /**
     * Check if element was built (built whole DOM structure).
     *
     * @returns {Boolean}
     */
    value: function isBuilt() {
      return this.buildState === STATE_BUILT;
    }
    /**
     * Translate value if it is possible. It's checked if value belongs to namespace of translated phrases.
     *
     * @param {*} value Value which will may be translated.
     * @returns {*} Translated value if translation was possible, original value otherwise.
     */

  }, {
    key: "translateIfPossible",
    value: function translateIfPossible(value) {
      if (typeof value === 'string' && value.startsWith(C.FILTERS_NAMESPACE)) {
        return this.hot.getTranslatedPhrase(value);
      }

      return value;
    }
    /**
     * Build DOM structure.
     */

  }, {
    key: "build",
    value: function build() {
      var _this = this;

      var registerEvent = function registerEvent(element, eventName) {
        _this.eventManager.addEventListener(element, eventName, function (event) {
          return _this.runLocalHooks(eventName, event, _this);
        });
      };

      if (!this.buildState) {
        this.buildState = STATE_BUILDING;
      }

      if (this.options.className) {
        addClass(this._element, this.options.className);
      }

      if (this.options.children.length) {
        arrayEach(this.options.children, function (element) {
          return _this._element.appendChild(element.element);
        });
      } else if (this.options.wrapIt) {
        var element = this.hot.rootDocument.createElement(this.options.tagName);
        objectEach(this.options, function (value, key) {
          if (element[key] !== void 0 && key !== 'className' && key !== 'tagName' && key !== 'children') {
            element[key] = _this.translateIfPossible(value);
          }
        });

        this._element.appendChild(element);

        arrayEach(EVENTS_TO_REGISTER, function (eventName) {
          return registerEvent(element, eventName);
        });
      } else {
        arrayEach(EVENTS_TO_REGISTER, function (eventName) {
          return registerEvent(_this._element, eventName);
        });
      }
    }
    /**
     * Update DOM structure.
     */

  }, {
    key: "update",
    value: function update() {}
    /**
     * Reset to initial state.
     */

  }, {
    key: "reset",
    value: function reset() {
      this.options.value = '';
      this.update();
    }
    /**
     * Show element.
     */

  }, {
    key: "show",
    value: function show() {
      this.element.style.display = '';
    }
    /**
     * Hide element.
     */

  }, {
    key: "hide",
    value: function hide() {
      this.element.style.display = 'none';
    }
    /**
     * Focus element.
     */

  }, {
    key: "focus",
    value: function focus() {}
  }, {
    key: "destroy",
    value: function destroy() {
      this.eventManager.destroy();
      this.eventManager = null;
      this.hot = null;

      if (this._element.parentNode) {
        this._element.parentNode.removeChild(this._element);
      }

      this._element = null;
    }
  }, {
    key: "element",
    get: function get() {
      if (this.buildState === STATE_BUILDING) {
        return this._element;
      }

      if (this.buildState === STATE_BUILT) {
        this.update();
        return this._element;
      }

      this.buildState = STATE_BUILDING;
      this.build();
      this.buildState = STATE_BUILT;
      return this._element;
    }
  }]);

  return BaseUI;
}();

mixin(BaseUI, localHooks);
export default BaseUI;