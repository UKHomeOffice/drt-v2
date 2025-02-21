function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Ukrainian
 * locale : Ukraine
 * author : Michael Piefel : https://github.com/piefel (with help from Tetyana Kuzmenko)
 */

var ukUA = {
    languageTag: "uk-UA",
    delimiters: {
        thousands: " ",
        decimal: ","
    },
    abbreviations: {
        thousand: "тис.",
        million: "млн",
        billion: "млрд",
        trillion: "блн"
    },
    ordinal: () => {
        // not ideal, but since in Ukrainian it can taken on
        // different forms (masculine, feminine, neuter)
        // this is all we can do
        return "";
    },
    currency: {
        symbol: "\u20B4",
        position: "postfix",
        code: "UAH"
    },
    currencyFormat: {
        thousandSeparated: true,
        totalLength: 4,
        spaceSeparated: true,
        average: true
    },
    formats: {
        fourDigits: {
            totalLength: 4,
            spaceSeparated: true,
            average: true
        },
        fullWithTwoDecimals: {
            output: "currency",
            mantissa: 2,
            spaceSeparated: true,
            thousandSeparated: true
        },
        fullWithTwoDecimalsNoCurrency: {
            mantissa: 2,
            thousandSeparated: true
        },
        fullWithNoDecimals: {
            output: "currency",
            spaceSeparated: true,
            thousandSeparated: true,
            mantissa: 0
        }
    }
};

var ukUA$1 = /*@__PURE__*/getDefaultExportFromCjs(ukUA);

export { ukUA$1 as default };
