function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

import arrayMapper from '../../../mixins/arrayMapper';
import { mixin } from '../../../helpers/object';
/**
 * @private
 * @class StrictBindStrategy
 */

var StrictBindStrategy =
/*#__PURE__*/
function () {
  function StrictBindStrategy() {
    _classCallCheck(this, StrictBindStrategy);
  }

  _createClass(StrictBindStrategy, [{
    key: "createRow",

    /**
     * Strategy for the create row action.
     *
     * @param {Number} index Row index.
     * @param {Number} amount
     */
    value: function createRow(index, amount) {
      this.insertItems(index, amount);
    }
    /**
     * Strategy for the remove row action.
     *
     * @param {Number|Array} index Row index or Array of row indexes.
     * @param {Number} amount
     */

  }, {
    key: "removeRow",
    value: function removeRow(index, amount) {
      this.removeItems(index, amount);
    }
    /**
     * Destroy strategy class.
     */

  }, {
    key: "destroy",
    value: function destroy() {
      this._arrayMap = null;
    }
  }], [{
    key: "STRATEGY_NAME",

    /**
     * Loose bind mode.
     *
     * @returns {String}
     */
    get: function get() {
      return 'strict';
    }
  }]);

  return StrictBindStrategy;
}();

mixin(StrictBindStrategy, arrayMapper);
export default StrictBindStrategy;