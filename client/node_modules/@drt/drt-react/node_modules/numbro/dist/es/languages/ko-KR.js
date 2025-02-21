function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Korean
 * author (numbro.js Version): Randy Wilander : https://github.com/rocketedaway
 * author (numeral.js Version) : Rich Daley : https://github.com/pedantic-git
 */

var koKR = {
    languageTag: "ko-KR",
    delimiters: {
        thousands: ",",
        decimal: "."
    },
    abbreviations: {
        thousand: "천",
        million: "백만",
        billion: "십억",
        trillion: "일조"
    },
    ordinal: function() {
        return ".";
    },
    currency: {
        symbol: "₩",
        code: "KPW"
    }
};

var koKR$1 = /*@__PURE__*/getDefaultExportFromCjs(koKR);

export { koKR$1 as default };
