function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Italian
 * locale: Switzerland
 * author : Tim McIntosh (StayinFront NZ)
 */

var itCH = {
    languageTag: "it-CH",
    delimiters: {
        thousands: "'",
        decimal: "."
    },
    abbreviations: {
        thousand: "mila",
        million: "mil",
        billion: "b",
        trillion: "t"
    },
    ordinal: function() {
        return "°";
    },
    currency: {
        symbol: "CHF",
        code: "CHF"
    }
};

var itCH$1 = /*@__PURE__*/getDefaultExportFromCjs(itCH);

export { itCH$1 as default };
