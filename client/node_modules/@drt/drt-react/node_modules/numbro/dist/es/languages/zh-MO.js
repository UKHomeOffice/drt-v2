function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Chinese traditional
 * locale: Macau
 * author : Tim McIntosh (StayinFront NZ)
 */

var zhMO = {
    languageTag: "zh-MO",
    delimiters: {
        thousands: ",",
        decimal: "."
    },
    abbreviations: {
        thousand: "千",
        million: "百萬",
        billion: "十億",
        trillion: "兆"
    },
    ordinal: function() {
        return ".";
    },
    currency: {
        symbol: "MOP",
        code: "MOP"
    }
};

var zhMO$1 = /*@__PURE__*/getDefaultExportFromCjs(zhMO);

export { zhMO$1 as default };
