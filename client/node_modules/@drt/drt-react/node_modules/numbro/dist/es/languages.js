var commonjsGlobal = typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : typeof global !== 'undefined' ? global : typeof self !== 'undefined' ? self : {};

function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

var languages$1 = {};

var bg_min = {exports: {}};

(function (module, exports) {
	!function(e,o){module.exports=o();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Bulgarian
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"bg",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"И",million:"А",billion:"M",trillion:"T"},ordinal:()=>".",currency:{symbol:"лв.",code:"BGN"}})}));
	
} (bg_min));

var bg_minExports = bg_min.exports;

var csCZ_min = {exports: {}};

(function (module, exports) {
	!function(e,a){module.exports=a();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Czech
		 * locale: Czech Republic
		 * author : Jan Pesa : https://github.com/smajl (based on work from Anatoli Papirovski : https://github.com/apapirovski)
		 */return e({languageTag:"cs-CZ",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"tis.",million:"mil.",billion:"mld.",trillion:"bil."},ordinal:function(){return "."},spaceSeparated:!0,currency:{symbol:"Kč",position:"postfix",code:"CZK"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,spaceSeparatedAbbreviation:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (csCZ_min));

var csCZ_minExports = csCZ_min.exports;

var daDK_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Danish
		 * locale: Denmark
		 * author : Michael Storgaard : https://github.com/mstorgaard
		 */return e({languageTag:"da-DK",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"t",million:"mio",billion:"mia",trillion:"b"},ordinal:function(){return "."},currency:{symbol:"kr",position:"postfix",code:"DKK"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (daDK_min));

var daDK_minExports = daDK_min.exports;

var deAT_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : German
		 * locale: Austria
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"de-AT",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"€",code:"EUR"}})}));
	
} (deAT_min));

var deAT_minExports = deAT_min.exports;

var deCH_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : German
		 * locale: Switzerland
		 * author : Michael Piefel : https://github.com/piefel (based on work from Marco Krage : https://github.com/sinky)
		 */return e({languageTag:"de-CH",delimiters:{thousands:"’",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"CHF",position:"postfix",code:"CHF"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (deCH_min));

var deCH_minExports = deCH_min.exports;

var deDE_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : German
		 * locale: Germany
		 * author : Marco Krage : https://github.com/sinky
		 *
		 * Generally useful in Germany, Austria, Luxembourg, Belgium
		 */return e({languageTag:"de-DE",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"Mi",billion:"Ma",trillion:"Bi"},ordinal:function(){return "."},spaceSeparated:!0,currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{totalLength:4,thousandSeparated:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (deDE_min));

var deDE_minExports = deDE_min.exports;

var deLI_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : German
		 * locale: Liechtenstein
		 * author : Michael Piefel : https://github.com/piefel (based on work from Marco Krage : https://github.com/sinky)
		 */return e({languageTag:"de-LI",delimiters:{thousands:"'",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"CHF",position:"postfix",code:"CHF"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (deLI_min));

var deLI_minExports = deLI_min.exports;

var el_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Greek (el)
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"el",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"χ",million:"ε",billion:"δ",trillion:"τ"},ordinal:function(){return "."},currency:{symbol:"€",code:"EUR"}})}));
	
} (el_min));

var el_minExports = el_min.exports;

var enAU_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : English
		 * locale: Australia
		 * author : Benedikt Huss : https://github.com/ben305
		 */return e({languageTag:"en-AU",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1==~~(e%100/10)?"th":1===t?"st":2===t?"nd":3===t?"rd":"th"},currency:{symbol:"$",position:"prefix",code:"AUD"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (enAU_min));

var enAU_minExports = enAU_min.exports;

var enGB_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : English
		 * locale: United Kingdom of Great Britain and Northern Ireland
		 * author : Dan Ristic : https://github.com/dristic
		 */return e({languageTag:"en-GB",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1==~~(e%100/10)?"th":1===t?"st":2===t?"nd":3===t?"rd":"th"},currency:{symbol:"£",position:"prefix",code:"GBP"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!1,spaceSeparatedCurrency:!1,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!1,average:!0},fullWithTwoDecimals:{output:"currency",thousandSeparated:!0,spaceSeparated:!1,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,spaceSeparated:!1,mantissa:0}}})}));
	
} (enGB_min));

var enGB_minExports = enGB_min.exports;

var enIE_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		+ * numbro.js language configuration
		 * language : English
		 * locale: Ireland
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"en-IE",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let n=e%10;return 1==~~(e%100/10)?"th":1===n?"st":2===n?"nd":3===n?"rd":"th"},currency:{symbol:"€",position:"prefix",code:"EUR"}})}));
	
} (enIE_min));

var enIE_minExports = enIE_min.exports;

var enNZ_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : English
		 * locale: New Zealand
		 * author : Benedikt Huss : https://github.com/ben305
		 */return e({languageTag:"en-NZ",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1==~~(e%100/10)?"th":1===t?"st":2===t?"nd":3===t?"rd":"th"},currency:{symbol:"$",position:"prefix",code:"NZD"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (enNZ_min));

var enNZ_minExports = enNZ_min.exports;

var enZA_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : English
		 * locale: South Africa
		 * author : Stewart Scott https://github.com/stewart42
		 */return e({languageTag:"en-ZA",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1==~~(e%100/10)?"th":1===t?"st":2===t?"nd":3===t?"rd":"th"},currency:{symbol:"R",position:"prefix",code:"ZAR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (enZA_min));

var enZA_minExports = enZA_min.exports;

var esAR_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Argentina
		 * author : Hernan Garcia : https://github.com/hgarcia
		 */return e({languageTag:"es-AR",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"$",position:"postfix",code:"ARS"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esAR_min));

var esAR_minExports = esAR_min.exports;

var esCL_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Chile
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-CL",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"$",position:"prefix",code:"CLP"},currencyFormat:{output:"currency",thousandSeparated:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esCL_min));

var esCL_minExports = esCL_min.exports;

var esCO_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Colombia
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-CO",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esCO_min));

var esCO_minExports = esCO_min.exports;

var esCR_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Costa Rica
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-CR",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"₡",position:"postfix",code:"CRC"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esCR_min));

var esCR_minExports = esCR_min.exports;

var esES_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Spain
		 * author : Hernan Garcia : https://github.com/hgarcia
		 */return e({languageTag:"es-ES",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esES_min));

var esES_minExports = esES_min.exports;

var esMX_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Mexico
		 * author : Joe Bordes : https://github.com/joebordes
		 */return e({languageTag:"es-MX",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:function(e){let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"$",position:"postfix",code:"MXN"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esMX_min));

var esMX_minExports = esMX_min.exports;

var esNI_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Nicaragua
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-NI",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"C$",position:"prefix",code:"NIO"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esNI_min));

var esNI_minExports = esNI_min.exports;

var esPE_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Peru
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-PE",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"S/.",position:"prefix",code:"PEN"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esPE_min));

var esPE_minExports = esPE_min.exports;

var esPR_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: Puerto Rico
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-PR",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"$",position:"prefix",code:"USD"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esPR_min));

var esPR_minExports = esPR_min.exports;

var esSV_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Spanish
		 * locale: El Salvador
		 * author : Gwyn Judd : https://github.com/gwynjudd
		 */return e({languageTag:"es-SV",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"mm",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1===t||3===t?"er":2===t?"do":7===t||0===t?"mo":8===t?"vo":9===t?"no":"to"},currency:{symbol:"$",position:"prefix",code:"SVC"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (esSV_min));

var esSV_minExports = esSV_min.exports;

var etEE_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Estonian
		 * locale: Estonia
		 * author : Illimar Tambek : https://github.com/ragulka
		 *
		 * Note: in Estonian, abbreviations are always separated
		 * from numbers with a space
		 */return e({languageTag:"et-EE",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"tuh",million:"mln",billion:"mld",trillion:"trl"},ordinal:function(){return "."},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (etEE_min));

var etEE_minExports = etEE_min.exports;

var faIR_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Farsi
		 * locale: Iran
		 * author : neo13 : https://github.com/neo13
		 */return e({languageTag:"fa-IR",delimiters:{thousands:"،",decimal:"."},abbreviations:{thousand:"هزار",million:"میلیون",billion:"میلیارد",trillion:"تریلیون"},ordinal:function(){return "ام"},currency:{symbol:"﷼",code:"IRR"}})}));
	
} (faIR_min));

var faIR_minExports = faIR_min.exports;

var fiFI_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Finnish
		 * locale: Finland
		 * author : Sami Saada : https://github.com/samitheberber
		 */return e({languageTag:"fi-FI",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"M",billion:"G",trillion:"T"},ordinal:function(){return "."},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (fiFI_min));

var fiFI_minExports = fiFI_min.exports;

var filPH_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Filipino (Pilipino)
		 * locale: Philippines
		 * author : Michael Abadilla : https://github.com/mjmaix
		 */return e({languageTag:"fil-PH",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>{let t=e%10;return 1==~~(e%100/10)?"th":1===t?"st":2===t?"nd":3===t?"rd":"th"},currency:{symbol:"₱",code:"PHP"}})}));
	
} (filPH_min));

var filPH_minExports = filPH_min.exports;

var frCA_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : French
		 * locale: Canada
		 * author : Léo Renaud-Allaire : https://github.com/renaudleo
		 */return e({languageTag:"fr-CA",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"M",billion:"G",trillion:"T"},ordinal:e=>1===e?"er":"ème",spaceSeparated:!0,currency:{symbol:"$",position:"postfix",code:"USD"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (frCA_min));

var frCA_minExports = frCA_min.exports;

var frCH_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : French
		 * locale: Switzerland
		 * author : Adam Draper : https://github.com/adamwdraper
		 */return e({languageTag:"fr-CH",delimiters:{thousands:" ",decimal:"."},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:e=>1===e?"er":"ème",currency:{symbol:"CHF",position:"postfix",code:"CHF"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (frCH_min));

var frCH_minExports = frCH_min.exports;

var frFR_min = {exports: {}};

(function (module, exports) {
	!function(e,o){module.exports=o();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : French
		 * locale: France
		 * author : Adam Draper : https://github.com/adamwdraper
		 */return e({languageTag:"fr-FR",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"M",billion:"Mrd",trillion:"billion"},ordinal:e=>1===e?"er":"ème",bytes:{binarySuffixes:["o","Kio","Mio","Gio","Tio","Pio","Eio","Zio","Yio"],decimalSuffixes:["o","Ko","Mo","Go","To","Po","Eo","Zo","Yo"]},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (frFR_min));

var frFR_minExports = frFR_min.exports;

var heIL_min = {exports: {}};

(function (module, exports) {
	!function(e,a){module.exports=a();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Hebrew
		 * locale : IL
		 * author : Eli Zehavi : https://github.com/eli-zehavi
		 */return e({languageTag:"he-IL",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"אלף",million:"מיליון",billion:"מיליארד",trillion:"טריליון"},currency:{symbol:"₪",position:"prefix",code:"ILS"},ordinal:()=>"",currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (heIL_min));

var heIL_minExports = heIL_min.exports;

var huHU_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Hungarian
		 * locale: Hungary
		 * author : Peter Bakondy : https://github.com/pbakondy
		 */return e({languageTag:"hu-HU",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"E",million:"M",billion:"Mrd",trillion:"T"},ordinal:function(){return "."},currency:{symbol:"Ft",position:"postfix",code:"HUF"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (huHU_min));

var huHU_minExports = huHU_min.exports;

var id_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Indonesian
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"id",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"r",million:"j",billion:"m",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"Rp",code:"IDR"}})}));
	
} (id_min));

var id_minExports = id_min.exports;

var itCH_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Italian
		 * locale: Switzerland
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"it-CH",delimiters:{thousands:"'",decimal:"."},abbreviations:{thousand:"mila",million:"mil",billion:"b",trillion:"t"},ordinal:function(){return "°"},currency:{symbol:"CHF",code:"CHF"}})}));
	
} (itCH_min));

var itCH_minExports = itCH_min.exports;

var itIT_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Italian
		 * locale: Italy
		 * author : Giacomo Trombi : http://cinquepunti.it
		 */return e({languageTag:"it-IT",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"mila",million:"mil",billion:"b",trillion:"t"},ordinal:function(){return "º"},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (itIT_min));

var itIT_minExports = itIT_min.exports;

var jaJP_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Japanese
		 * locale: Japan
		 * author : teppeis : https://github.com/teppeis
		 */return e({languageTag:"ja-JP",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"千",million:"百万",billion:"十億",trillion:"兆"},ordinal:function(){return "."},currency:{symbol:"¥",position:"prefix",code:"JPY"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (jaJP_min));

var jaJP_minExports = jaJP_min.exports;

var koKR_min = {exports: {}};

(function (module, exports) {
	!function(e,o){module.exports=o();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Korean
		 * author (numbro.js Version): Randy Wilander : https://github.com/rocketedaway
		 * author (numeral.js Version) : Rich Daley : https://github.com/pedantic-git
		 */return e({languageTag:"ko-KR",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"천",million:"백만",billion:"십억",trillion:"일조"},ordinal:function(){return "."},currency:{symbol:"₩",code:"KPW"}})}));
	
} (koKR_min));

var koKR_minExports = koKR_min.exports;

var lvLV_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Latvian
		 * locale: Latvia
		 * author : Lauris Bukšis-Haberkorns : https://github.com/Lafriks
		 */return e({languageTag:"lv-LV",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"tūkst.",million:"milj.",billion:"mljrd.",trillion:"trilj."},ordinal:function(){return "."},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (lvLV_min));

var lvLV_minExports = lvLV_min.exports;

var nbNO_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language: Norwegian Bokmål
		 * locale: Norway
		 * author : Benjamin Van Ryseghem
		 */return e({languageTag:"nb-NO",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"t",million:"M",billion:"md",trillion:"b"},ordinal:()=>"",currency:{symbol:"kr",position:"postfix",code:"NOK"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (nbNO_min));

var nbNO_minExports = nbNO_min.exports;

var nb_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Norwegian Bokmål (nb)
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"nb",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"t",million:"mil",billion:"mia",trillion:"b"},ordinal:function(){return "."},currency:{symbol:"kr",code:"NOK"}})}));
	
} (nb_min));

var nb_minExports = nb_min.exports;

var nlBE_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Dutch
		 * locale: Belgium
		 * author : Dieter Luypaert : https://github.com/moeriki
		 */return e({languageTag:"nl-BE",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"mln",billion:"mld",trillion:"bln"},ordinal:e=>{let t=e%100;return 0!==e&&t<=1||8===t||t>=20?"ste":"de"},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (nlBE_min));

var nlBE_minExports = nlBE_min.exports;

var nlNL_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Dutch
		 * locale: Netherlands
		 * author : Dave Clayton : https://github.com/davedx
		 */return e({languageTag:"nl-NL",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"k",million:"mln",billion:"mrd",trillion:"bln"},ordinal:e=>{let t=e%100;return 0!==e&&t<=1||8===t||t>=20?"ste":"de"},currency:{symbol:"€",position:"prefix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (nlNL_min));

var nlNL_minExports = nlNL_min.exports;

var nn_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Norwegian Nynorsk (nn)
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"nn",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"t",million:"mil",billion:"mia",trillion:"b"},ordinal:function(){return "."},currency:{symbol:"kr",code:"NOK"}})}));
	
} (nn_min));

var nn_minExports = nn_min.exports;

var plPL_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Polish
		 * locale : Poland
		 * author : Dominik Bulaj : https://github.com/dominikbulaj
		 */return e({languageTag:"pl-PL",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"tys.",million:"mln",billion:"mld",trillion:"bln"},ordinal:()=>".",currency:{symbol:" zł",position:"postfix",code:"PLN"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (plPL_min));

var plPL_minExports = plPL_min.exports;

var ptBR_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Portuguese
		 * locale : Brazil
		 * author : Ramiro letandas Jr : https://github.com/ramirovjr
		 */return e({languageTag:"pt-BR",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"mil",million:"milhões",billion:"b",trillion:"t"},ordinal:function(){return "º"},currency:{symbol:"R$",position:"prefix",code:"BRL"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ptBR_min));

var ptBR_minExports = ptBR_min.exports;

var ptPT_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Portuguese
		 * locale : Portugal
		 * author : Diogo Resende : https://github.com/dresende
		 */return e({languageTag:"pt-PT",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"k",million:"m",billion:"b",trillion:"t"},ordinal:function(){return "º"},currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ptPT_min));

var ptPT_minExports = ptPT_min.exports;

var roRO_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numeral.js language configuration
		 * language : Romanian
		 * author : Andrei Alecu https://github.com/andreialecu
		 */return e({languageTag:"ro-RO",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"mii",million:"mil",billion:"mld",trillion:"bln"},ordinal:function(){return "."},currency:{symbol:" lei",position:"postfix",code:"RON"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (roRO_min));

var roRO_minExports = roRO_min.exports;

var ro_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numeral.js language configuration
		 * language : Romanian
		 * author : Andrei Alecu https://github.com/andreialecu
		 */return e({languageTag:"ro-RO",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"mii",million:"mil",billion:"mld",trillion:"bln"},ordinal:function(){return "."},currency:{symbol:" lei",position:"postfix",code:"RON"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ro_min));

var ro_minExports = ro_min.exports;

var ruRU_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Russian
		 * locale : Russsia
		 * author : Anatoli Papirovski : https://github.com/apapirovski
		 */return e({languageTag:"ru-RU",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"тыс.",million:"млн",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"руб.",position:"postfix",code:"RUB"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ruRU_min));

var ruRU_minExports = ruRU_min.exports;

var ruUA_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Russian
		 * locale : Ukraine
		 * author : Anatoli Papirovski : https://github.com/apapirovski
		 */return e({languageTag:"ru-UA",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"тыс.",million:"млн",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"₴",position:"postfix",code:"UAH"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ruUA_min));

var ruUA_minExports = ruUA_min.exports;

var skSK_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Slovak
		 * locale : Slovakia
		 * author : Jan Pesa : https://github.com/smajl (based on work from Ahmed Al Hafoudh : http://www.freevision.sk)
		 */return e({languageTag:"sk-SK",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"tis.",million:"mil.",billion:"mld.",trillion:"bil."},ordinal:function(){return "."},spaceSeparated:!0,currency:{symbol:"€",position:"postfix",code:"EUR"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (skSK_min));

var skSK_minExports = skSK_min.exports;

var sl_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Slovene
		 * locale: Slovenia
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"sl",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"tis.",million:"mil.",billion:"b",trillion:"t"},ordinal:function(){return "."},currency:{symbol:"€",code:"EUR"}})}));
	
} (sl_min));

var sl_minExports = sl_min.exports;

var srCyrlRS_min = {exports: {}};

(function (module, exports) {
	!function(e,o){module.exports=o();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Serbian (sr)
		 * country : Serbia (Cyrillic)
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"sr-Cyrl-RS",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"тыс.",million:"млн",billion:"b",trillion:"t"},ordinal:()=>".",currency:{symbol:"RSD",code:"RSD"}})}));
	
} (srCyrlRS_min));

var srCyrlRS_minExports = srCyrlRS_min.exports;

var svSE_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Swedish
		 * locale : Sweden
		 * author : Benjamin Van Ryseghem (benjamin.vanryseghem.com)
		 */return e({languageTag:"sv-SE",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"t",million:"M",billion:"md",trillion:"tmd"},ordinal:()=>"",currency:{symbol:"kr",position:"postfix",code:"SEK"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (svSE_min));

var svSE_minExports = svSE_min.exports;

var thTH_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Thai
		 * locale : Thailand
		 * author : Sathit Jittanupat : https://github.com/jojosati
		 */return e({languageTag:"th-TH",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"พัน",million:"ล้าน",billion:"พันล้าน",trillion:"ล้านล้าน"},ordinal:function(){return "."},currency:{symbol:"฿",position:"postfix",code:"THB"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (thTH_min));

var thTH_minExports = thTH_min.exports;

var trTR_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Turkish
		 * locale : Turkey
		 * author : Ecmel Ercan : https://github.com/ecmel,
		 *          Erhan Gundogan : https://github.com/erhangundogan,
		 *          Burak Yiğit Kaya: https://github.com/BYK
		 */const n={1:"'inci",5:"'inci",8:"'inci",70:"'inci",80:"'inci",2:"'nci",7:"'nci",20:"'nci",50:"'nci",3:"'üncü",4:"'üncü",100:"'üncü",6:"'ncı",9:"'uncu",10:"'uncu",30:"'uncu",40:"'ıncı",60:"'ıncı",90:"'ıncı"};return e({languageTag:"tr-TR",delimiters:{thousands:".",decimal:","},abbreviations:{thousand:"bin",million:"milyon",billion:"milyar",trillion:"trilyon"},ordinal:e=>{if(0===e)return "'ıncı";let t=e%10;return n[t]||n[e%100-t]||n[e>=100?100:null]},currency:{symbol:"₺",position:"postfix",code:"TRY"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,spaceSeparatedCurrency:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (trTR_min));

var trTR_minExports = trTR_min.exports;

var ukUA_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Ukrainian
		 * locale : Ukraine
		 * author : Michael Piefel : https://github.com/piefel (with help from Tetyana Kuzmenko)
		 */return e({languageTag:"uk-UA",delimiters:{thousands:" ",decimal:","},abbreviations:{thousand:"тис.",million:"млн",billion:"млрд",trillion:"блн"},ordinal:()=>"",currency:{symbol:"₴",position:"postfix",code:"UAH"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{output:"currency",mantissa:2,spaceSeparated:!0,thousandSeparated:!0},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",spaceSeparated:!0,thousandSeparated:!0,mantissa:0}}})}));
	
} (ukUA_min));

var ukUA_minExports = ukUA_min.exports;

var zhCN_min = {exports: {}};

(function (module, exports) {
	!function(e,t){module.exports=t();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : simplified chinese
		 * locale : China
		 * author : badplum : https://github.com/badplum
		 */return e({languageTag:"zh-CN",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"千",million:"百万",billion:"十亿",trillion:"兆"},ordinal:function(){return "."},currency:{symbol:"¥",position:"prefix",code:"CNY"},currencyFormat:{thousandSeparated:!0,totalLength:4,spaceSeparated:!0,average:!0},formats:{fourDigits:{totalLength:4,spaceSeparated:!0,average:!0},fullWithTwoDecimals:{thousandSeparated:!0,mantissa:2},fullWithTwoDecimalsNoCurrency:{mantissa:2,thousandSeparated:!0},fullWithNoDecimals:{output:"currency",thousandSeparated:!0,mantissa:0}}})}));
	
} (zhCN_min));

var zhCN_minExports = zhCN_min.exports;

var zhMO_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Chinese traditional
		 * locale: Macau
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"zh-MO",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"千",million:"百萬",billion:"十億",trillion:"兆"},ordinal:function(){return "."},currency:{symbol:"MOP",code:"MOP"}})}));
	
} (zhMO_min));

var zhMO_minExports = zhMO_min.exports;

var zhSG_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Chinese simplified
		 * locale: Singapore
		 * author : Tim McIntosh (StayinFront NZ)
		 */return e({languageTag:"zh-SG",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"千",million:"百万",billion:"十亿",trillion:"兆"},ordinal:function(){return "."},currency:{symbol:"$",code:"SGD"}})}));
	
} (zhSG_min));

var zhSG_minExports = zhSG_min.exports;

var zhTW_min = {exports: {}};

(function (module, exports) {
	!function(e,n){module.exports=n();}(commonjsGlobal,(function(){function e(e){return e&&e.__esModule&&Object.prototype.hasOwnProperty.call(e,"default")?e.default:e}
	/*!
		 * numbro.js language configuration
		 * language : Chinese (Taiwan)
		 * author (numbro.js Version): Randy Wilander : https://github.com/rocketedaway
		 * author (numeral.js Version) : Rich Daley : https://github.com/pedantic-git
		 */return e({languageTag:"zh-TW",delimiters:{thousands:",",decimal:"."},abbreviations:{thousand:"千",million:"百萬",billion:"十億",trillion:"兆"},ordinal:function(){return "第"},currency:{symbol:"NT$",code:"TWD"}})}));
	
} (zhTW_min));

var zhTW_minExports = zhTW_min.exports;

(function (exports) {
	exports["bg"]=bg_minExports;exports["cs-CZ"]=csCZ_minExports;exports["da-DK"]=daDK_minExports;exports["de-AT"]=deAT_minExports;exports["de-CH"]=deCH_minExports;exports["de-DE"]=deDE_minExports;exports["de-LI"]=deLI_minExports;exports["el"]=el_minExports;exports["en-AU"]=enAU_minExports;exports["en-GB"]=enGB_minExports;exports["en-IE"]=enIE_minExports;exports["en-NZ"]=enNZ_minExports;exports["en-ZA"]=enZA_minExports;exports["es-AR"]=esAR_minExports;exports["es-CL"]=esCL_minExports;exports["es-CO"]=esCO_minExports;exports["es-CR"]=esCR_minExports;exports["es-ES"]=esES_minExports;exports["es-MX"]=esMX_minExports;exports["es-NI"]=esNI_minExports;exports["es-PE"]=esPE_minExports;exports["es-PR"]=esPR_minExports;exports["es-SV"]=esSV_minExports;exports["et-EE"]=etEE_minExports;exports["fa-IR"]=faIR_minExports;exports["fi-FI"]=fiFI_minExports;exports["fil-PH"]=filPH_minExports;exports["fr-CA"]=frCA_minExports;exports["fr-CH"]=frCH_minExports;exports["fr-FR"]=frFR_minExports;exports["he-IL"]=heIL_minExports;exports["hu-HU"]=huHU_minExports;exports["id"]=id_minExports;exports["it-CH"]=itCH_minExports;exports["it-IT"]=itIT_minExports;exports["ja-JP"]=jaJP_minExports;exports["ko-KR"]=koKR_minExports;exports["lv-LV"]=lvLV_minExports;exports["nb-NO"]=nbNO_minExports;exports["nb"]=nb_minExports;exports["nl-BE"]=nlBE_minExports;exports["nl-NL"]=nlNL_minExports;exports["nn"]=nn_minExports;exports["pl-PL"]=plPL_minExports;exports["pt-BR"]=ptBR_minExports;exports["pt-PT"]=ptPT_minExports;exports["ro-RO"]=roRO_minExports;exports["ro"]=ro_minExports;exports["ru-RU"]=ruRU_minExports;exports["ru-UA"]=ruUA_minExports;exports["sk-SK"]=skSK_minExports;exports["sl"]=sl_minExports;exports["sr-Cyrl-RS"]=srCyrlRS_minExports;exports["sv-SE"]=svSE_minExports;exports["th-TH"]=thTH_minExports;exports["tr-TR"]=trTR_minExports;exports["uk-UA"]=ukUA_minExports;exports["zh-CN"]=zhCN_minExports;exports["zh-MO"]=zhMO_minExports;exports["zh-SG"]=zhSG_minExports;exports["zh-TW"]=zhTW_minExports; 
} (languages$1));

var languages = /*@__PURE__*/getDefaultExportFromCjs(languages$1);

export { languages as default };
