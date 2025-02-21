function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

/*!
 * numbro.js language configuration
 * language : French
 * locale: France
 * author : Adam Draper : https://github.com/adamwdraper
 */

var frFR = {
    languageTag: "fr-FR",
    delimiters: {
        thousands: " ",
        decimal: ","
    },
    abbreviations: {
        thousand: "k",
        million: "M",
        billion: "Mrd",
        trillion: "billion"
    },
    ordinal: (number) => {
        return number === 1 ? "er" : "ème";
    },
    bytes: {
        binarySuffixes: ["o", "Kio", "Mio", "Gio", "Tio", "Pio", "Eio", "Zio", "Yio"],
        decimalSuffixes: ["o", "Ko", "Mo", "Go", "To", "Po", "Eo", "Zo", "Yo"]
    },
    currency: {
        symbol: "€",
        position: "postfix",
        code: "EUR"
    },
    currencyFormat: {
        thousandSeparated: true,
        totalLength: 4,
        spaceSeparated: true,
        spaceSeparatedCurrency: true,
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

var frFR$1 = /*@__PURE__*/getDefaultExportFromCjs(frFR);

export { frFR$1 as default };
