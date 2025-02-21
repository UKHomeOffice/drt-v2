function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : Spanish
 * locale: Chile
 * author : Gwyn Judd : https://github.com/gwynjudd
 */

var esCL = {
    languageTag: "es-CL",
    delimiters: {
        thousands: ".",
        decimal: ","
    },
    abbreviations: {
        thousand: "k",
        million: "mm",
        billion: "b",
        trillion: "t"
    },
    ordinal: (number) => {
        let b = number % 10;
        return (b === 1 || b === 3) ? "er" : (b === 2) ? "do" : (b === 7 || b === 0) ? "mo" : (b === 8) ? "vo" : (b === 9) ? "no" : "to";
    },
    currency: {
        symbol: "$",
        position: "prefix",
        code: "CLP"
    },
    currencyFormat: {
        output: "currency",
        thousandSeparated: true
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

var esCL$1 = /*@__PURE__*/getDefaultExportFromCjs(esCL);

export { esCL$1 as default };
