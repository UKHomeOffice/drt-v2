function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Indonesian
 * author : Tim McIntosh (StayinFront NZ)
 */

var id = {
    languageTag: "id",
    delimiters: {
        thousands: ".",
        decimal: ","
    },
    abbreviations: {
        thousand: "r",
        million: "j",
        billion: "m",
        trillion: "t"
    },
    ordinal: function() {
        return ".";
    },
    currency: {
        symbol: "Rp",
        code: "IDR"
    }
};

var id$1 = /*@__PURE__*/getDefaultExportFromCjs(id);

export { id$1 as default };
