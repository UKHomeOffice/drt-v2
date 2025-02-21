function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Norwegian Bokmål (nb)
 * author : Tim McIntosh (StayinFront NZ)
 */

var nb = {
    languageTag: "nb",
    delimiters: {
        thousands: " ",
        decimal: ","
    },
    abbreviations: {
        thousand: "t",
        million: "mil",
        billion: "mia",
        trillion: "b"
    },
    ordinal: function() {
        return ".";
    },
    currency: {
        symbol: "kr",
        code: "NOK"
    }
};

var nb$1 = /*@__PURE__*/getDefaultExportFromCjs(nb);

export { nb$1 as default };
