"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/es.set.difference.v2.js");
require("core-js/modules/es.set.intersection.v2.js");
require("core-js/modules/es.set.is-disjoint-from.v2.js");
require("core-js/modules/es.set.is-subset-of.v2.js");
require("core-js/modules/es.set.is-superset-of.v2.js");
require("core-js/modules/es.set.symmetric-difference.v2.js");
require("core-js/modules/es.set.union.v2.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
var _initOnlyCallback = /*#__PURE__*/new WeakMap();
/**
 * @class ExtendMetaPropertiesMod
 */
class ExtendMetaPropertiesMod {
  constructor(metaManager) {
    /**
     * @type {MetaManager}
     */
    _defineProperty(this, "metaManager", void 0);
    /**
     * @type {Set}
     */
    _defineProperty(this, "usageTracker", new Set());
    /**
     * @type {Map}
     */
    _defineProperty(this, "propDescriptors", new Map([['ariaTags', {
      initOnly: true
    }], ['fixedColumnsLeft', {
      target: 'fixedColumnsStart',
      onChange(propName) {
        const isRtl = this.metaManager.hot.isRtl();
        if (isRtl && propName === 'fixedColumnsLeft') {
          throw new Error('The `fixedColumnsLeft` is not supported for RTL. Please use option `fixedColumnsStart`.');
        }
        if (this.usageTracker.has('fixedColumnsLeft') && this.usageTracker.has('fixedColumnsStart')) {
          throw new Error('The `fixedColumnsLeft` and `fixedColumnsStart` should not be used together. ' + 'Please use only the option `fixedColumnsStart`.');
        }
      }
    }], ['layoutDirection', {
      initOnly: true
    }], ['renderAllColumns', {
      initOnly: true
    }], ['renderAllRows', {
      initOnly: true
    }]]));
    /**
     * Callback called when the prop is marked as `initOnly`.
     *
     * @param {string} propName The property name.
     * @param {*} value The new value.
     * @param {boolean} isInitialChange Is the change initial.
     */
    _classPrivateFieldInitSpec(this, _initOnlyCallback, (propName, value, isInitialChange) => {
      if (!isInitialChange) {
        throw new Error(`The \`${propName}\` option can not be updated after the Handsontable is initialized.`);
      }
    });
    this.metaManager = metaManager;
    this.extendMetaProps();
  }
  /**
   * Extends the meta options based on the object descriptors from the `propDescriptors` list.
   */
  extendMetaProps() {
    this.propDescriptors.forEach((descriptor, alias) => {
      const {
        initOnly,
        target,
        onChange
      } = descriptor;
      const hasTarget = typeof target === 'string';
      const targetProp = hasTarget ? target : alias;
      const origProp = `_${targetProp}`;
      this.metaManager.globalMeta.meta[origProp] = this.metaManager.globalMeta.meta[targetProp];
      if (onChange) {
        this.installPropWatcher(alias, origProp, onChange);
        if (hasTarget) {
          this.installPropWatcher(target, origProp, onChange);
        }
      } else if (initOnly) {
        this.installPropWatcher(alias, origProp, _classPrivateFieldGet(_initOnlyCallback, this));
        if (!this.metaManager.globalMeta.meta._initOnlySettings) {
          this.metaManager.globalMeta.meta._initOnlySettings = [];
        }
        this.metaManager.globalMeta.meta._initOnlySettings.push(alias);
      }
    });
  }

  /**
   * Installs the property watcher to the `propName` option and forwards getter and setter to
   * the new one.
   *
   * @param {string} propName The property to watch.
   * @param {string} origProp The property from/to the value is forwarded.
   * @param {Function} onChange The callback.
   */
  installPropWatcher(propName, origProp, onChange) {
    const self = this;
    Object.defineProperty(this.metaManager.globalMeta.meta, propName, {
      get() {
        return this[origProp];
      },
      set(value) {
        const isInitialChange = !self.usageTracker.has(propName);
        self.usageTracker.add(propName);
        onChange.call(self, propName, value, isInitialChange);
        this[origProp] = value;
      },
      enumerable: true,
      configurable: true
    });
  }
}
exports.ExtendMetaPropertiesMod = ExtendMetaPropertiesMod;