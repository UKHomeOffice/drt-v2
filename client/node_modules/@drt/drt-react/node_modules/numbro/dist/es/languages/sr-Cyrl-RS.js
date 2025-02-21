function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Serbian (sr)
 * country : Serbia (Cyrillic)
 * author : Tim McIntosh (StayinFront NZ)
 */

var srCyrlRS = {
    languageTag: "sr-Cyrl-RS",
    delimiters: {
        thousands: ".",
        decimal: ","
    },
    abbreviations: {
        thousand: "тыс.",
        million: "млн",
        billion: "b",
        trillion: "t"
    },
    ordinal: () => ".",
    currency: {
        symbol: "RSD",
        code: "RSD"
    }
};

var srCyrlRS$1 = /*@__PURE__*/getDefaultExportFromCjs(srCyrlRS);

export { srCyrlRS$1 as default };
