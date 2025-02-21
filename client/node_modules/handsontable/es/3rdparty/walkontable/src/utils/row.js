function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

/**
 * Row utils class contains all necessary information about sizes of the rows.
 *
 * @class {RowUtils}
 */
var RowUtils =
/*#__PURE__*/
function () {
  function RowUtils(wot) {
    _classCallCheck(this, RowUtils);

    this.wot = wot;
  }
  /**
   * Returns row height based on passed source index.
   *
   * @param {Number} sourceIndex Row source index.
   * @returns {Number}
   */


  _createClass(RowUtils, [{
    key: "getHeight",
    value: function getHeight(sourceIndex) {
      var height = this.wot.wtSettings.settings.rowHeight(sourceIndex);
      var oversizedHeight = this.wot.wtViewport.oversizedRows[sourceIndex];

      if (oversizedHeight !== void 0) {
        height = height === void 0 ? oversizedHeight : Math.max(height, oversizedHeight);
      }

      return height;
    }
  }]);

  return RowUtils;
}();

export { RowUtils as default };