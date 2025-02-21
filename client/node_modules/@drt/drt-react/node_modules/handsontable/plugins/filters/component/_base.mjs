import "core-js/modules/es.error.cause.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { arrayEach } from "../../../helpers/array.mjs";
import { mixin } from "../../../helpers/object.mjs";
import localHooks from "../../../mixins/localHooks.mjs";
import { LinkedPhysicalIndexToValueMap as IndexToValueMap } from "../../../translations/index.mjs";
/**
 * @private
 * @class BaseComponent
 */
export class BaseComponent {
  constructor(hotInstance, _ref) {
    let {
      id,
      stateless = true
    } = _ref;
    /**
     * The Handsontable instance.
     *
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * The component uniq id.
     *
     * @type {string}
     */
    _defineProperty(this, "id", void 0);
    /**
     * List of registered component UI elements.
     *
     * @type {Array}
     */
    _defineProperty(this, "elements", []);
    /**
     * Flag which determines if element is hidden.
     *
     * @type {boolean}
     */
    _defineProperty(this, "hidden", false);
    /**
     * The component states id.
     *
     * @type {string}
     */
    _defineProperty(this, "stateId", '');
    /**
     * Index map which stores component states for each column.
     *
     * @type {LinkedPhysicalIndexToValueMap|null}
     */
    _defineProperty(this, "state", void 0);
    this.hot = hotInstance;
    this.id = id;
    this.stateId = `Filters.component.${this.id}`;
    this.state = stateless ? null : this.hot.columnIndexMapper.registerMap(this.stateId, new IndexToValueMap());
  }

  /**
   * Gets the list of elements from which the component is built.
   *
   * @returns {BaseUI[]}
   */
  getElements() {
    return this.elements;
  }

  /**
   * Reset elements to its initial state.
   */
  reset() {
    arrayEach(this.elements, ui => ui.reset());
  }

  /**
   * Hide component.
   */
  hide() {
    this.hidden = true;
  }

  /**
   * Show component.
   */
  show() {
    this.hidden = false;
  }

  /**
   * Check if component is hidden.
   *
   * @returns {boolean}
   */
  isHidden() {
    return this.hot === null || this.hidden;
  }

  /**
   * Restores the component state from the given physical column index. The method
   * internally calls the `setState` method. The state then is individually processed
   * by each component.
   *
   * @param {number} physicalColumn The physical column index.
   */
  restoreState(physicalColumn) {
    if (this.state) {
      this.setState(this.state.getValueAtIndex(physicalColumn));
    }
  }

  /**
   * The custom logic for component state restoring.
   */
  setState() {
    throw new Error('The state setting logic is not implemented');
  }

  /**
   * Saves the component state to the given physical column index. The method
   * internally calls the `getState` method, which returns the current state of
   * the component.
   *
   * @param {number} physicalColumn The physical column index.
   */
  saveState(physicalColumn) {
    if (this.state) {
      this.state.setValueAtIndex(physicalColumn, this.getState());
    }
  }

  /**
   * The custom logic for component state gathering (for stateful components).
   */
  getState() {
    throw new Error('The state gathering logic is not implemented');
  }

  /**
   * Destroy element.
   */
  destroy() {
    this.hot.columnIndexMapper.unregisterMap(this.stateId);
    this.clearLocalHooks();
    arrayEach(this.elements, ui => ui.destroy());
    this.state = null;
    this.elements = null;
    this.hot = null;
  }
}
mixin(BaseComponent, localHooks);