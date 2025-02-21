function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Farsi
 * locale: Iran
 * author : neo13 : https://github.com/neo13
 */

var faIR = {
    languageTag: "fa-IR",
    delimiters: {
        thousands: "،",
        decimal: "."
    },
    abbreviations: {
        thousand: "هزار",
        million: "میلیون",
        billion: "میلیارد",
        trillion: "تریلیون"
    },
    ordinal: function() {
        return "ام";
    },
    currency: {
        symbol: "﷼",
        code: "IRR"
    }
};

var faIR$1 = /*@__PURE__*/getDefaultExportFromCjs(faIR);

export { faIR$1 as default };
