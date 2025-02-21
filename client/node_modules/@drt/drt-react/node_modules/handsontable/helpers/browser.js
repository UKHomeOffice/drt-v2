"use strict";

exports.__esModule = true;
exports.isChrome = isChrome;
exports.isChromeWebKit = isChromeWebKit;
exports.isEdge = isEdge;
exports.isEdgeWebKit = isEdgeWebKit;
exports.isFirefox = isFirefox;
exports.isFirefoxWebKit = isFirefoxWebKit;
exports.isIOS = isIOS;
exports.isIpadOS = isIpadOS;
exports.isLinuxOS = isLinuxOS;
exports.isMacOS = isMacOS;
exports.isMobileBrowser = isMobileBrowser;
exports.isSafari = isSafari;
exports.isWindowsOS = isWindowsOS;
exports.setBrowserMeta = setBrowserMeta;
exports.setPlatformMeta = setPlatformMeta;
var _object = require("./object");
var _feature = require("./feature");
const tester = testerFunc => {
  const result = {
    value: false
  };
  result.test = (ua, vendor) => {
    result.value = testerFunc(ua, vendor);
  };
  return result;
};
const browsers = {
  chrome: tester((ua, vendor) => /Chrome/.test(ua) && /Google/.test(vendor)),
  chromeWebKit: tester(ua => /CriOS/.test(ua)),
  edge: tester(ua => /Edge/.test(ua)),
  edgeWebKit: tester(ua => /EdgiOS/.test(ua)),
  firefox: tester(ua => /Firefox/.test(ua)),
  firefoxWebKit: tester(ua => /FxiOS/.test(ua)),
  mobile: tester(ua => /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(ua)),
  safari: tester((ua, vendor) => /Safari/.test(ua) && /Apple Computer/.test(vendor))
};
const platforms = {
  mac: tester(platform => /^Mac/.test(platform)),
  win: tester(platform => /^Win/.test(platform)),
  linux: tester(platform => /^Linux/.test(platform)),
  ios: tester(ua => /iPhone|iPad|iPod/i.test(ua))
};

/**
 * @param {object} [metaObject] The browser identity collection.
 * @param {object} [metaObject.userAgent] The user agent reported by browser.
 * @param {object} [metaObject.vendor] The vendor name reported by browser.
 */
function setBrowserMeta() {
  let {
    userAgent = navigator.userAgent,
    vendor = navigator.vendor
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  (0, _object.objectEach)(browsers, _ref => {
    let {
      test
    } = _ref;
    return void test(userAgent, vendor);
  });
}

/**
 * @param {object} [metaObject] The platform identity collection.
 * @param {object} [metaObject.platform] The platform ID.
 */
function setPlatformMeta() {
  let {
    platform = navigator.platform
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  (0, _object.objectEach)(platforms, _ref2 => {
    let {
      test
    } = _ref2;
    return void test(platform);
  });
}
if ((0, _feature.isCSR)()) {
  setBrowserMeta();
  setPlatformMeta();
}

/**
 * @returns {boolean}
 */
function isChrome() {
  return browsers.chrome.value;
}

/**
 * @returns {boolean}
 */
function isChromeWebKit() {
  return browsers.chromeWebKit.value;
}

/**
 * @returns {boolean}
 */
function isFirefox() {
  return browsers.firefox.value;
}

/**
 * @returns {boolean}
 */
function isFirefoxWebKit() {
  return browsers.firefoxWebKit.value;
}

/**
 * @returns {boolean}
 */
function isSafari() {
  return browsers.safari.value;
}

/**
 * @returns {boolean}
 */
function isEdge() {
  return browsers.edge.value;
}

/**
 * @returns {boolean}
 */
function isEdgeWebKit() {
  return browsers.edgeWebKit.value;
}

/**
 * @returns {boolean}
 */
function isMobileBrowser() {
  return browsers.mobile.value;
}

/**
 * @returns {boolean}
 */
function isIOS() {
  return platforms.ios.value;
}

/**
 * A hacky way to recognize the iPad. Since iOS 13, the iPad on Safari mimics macOS behavior and user agent.
 *
 * @see {@https://stackoverflow.com/a/57838385}
 * @param {object} [metaObject] The browser identity collection.
 * @param {number} [metaObject.maxTouchPoints] The maximum number of simultanous touch points.
 * @returns {boolean}
 */
function isIpadOS() {
  let {
    maxTouchPoints
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : navigator;
  return maxTouchPoints > 2 && platforms.mac.value;
}

/**
 * @returns {boolean}
 */
function isWindowsOS() {
  return platforms.win.value;
}

/**
 * @returns {boolean}
 */
function isMacOS() {
  return platforms.mac.value;
}

/**
 * @returns {boolean}
 */
function isLinuxOS() {
  return platforms.linux.value;
}