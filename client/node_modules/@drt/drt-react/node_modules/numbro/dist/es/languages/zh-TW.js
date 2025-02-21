function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Chinese (Taiwan)
 * author (numbro.js Version): Randy Wilander : https://github.com/rocketedaway
 * author (numeral.js Version) : Rich Daley : https://github.com/pedantic-git
 */

var zhTW = {
    languageTag: "zh-TW",
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
        return "第";
    },
    currency: {
        symbol: "NT$",
        code: "TWD"
    }
};

var zhTW$1 = /*@__PURE__*/getDefaultExportFromCjs(zhTW);

export { zhTW$1 as default };
