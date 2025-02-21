function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Bulgarian
 * author : Tim McIntosh (StayinFront NZ)
 */

var bg = {
    languageTag: "bg",
    delimiters: {
        thousands: " ",
        decimal: ","
    },
    abbreviations: {
        thousand: "И",
        million: "А",
        billion: "M",
        trillion: "T"
    },
    ordinal: () => ".",
    currency: {
        symbol: "лв.",
        code: "BGN"
    }
};

var bg$1 = /*@__PURE__*/getDefaultExportFromCjs(bg);

export { bg$1 as default };
