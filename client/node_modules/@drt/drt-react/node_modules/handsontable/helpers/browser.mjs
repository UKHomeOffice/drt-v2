import { objectEach } from "./object.mjs";
import { isCSR } from "./feature.mjs";
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
export function setBrowserMeta() {
  let {
    userAgent = navigator.userAgent,
    vendor = navigator.vendor
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  objectEach(browsers, _ref => {
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
export function setPlatformMeta() {
  let {
    platform = navigator.platform
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  objectEach(platforms, _ref2 => {
    let {
      test
    } = _ref2;
    return void test(platform);
  });
}
if (isCSR()) {
  setBrowserMeta();
  setPlatformMeta();
}

/**
 * @returns {boolean}
 */
export function isChrome() {
  return browsers.chrome.value;
}

/**
 * @returns {boolean}
 */
export function isChromeWebKit() {
  return browsers.chromeWebKit.value;
}

/**
 * @returns {boolean}
 */
export function isFirefox() {
  return browsers.firefox.value;
}

/**
 * @returns {boolean}
 */
export function isFirefoxWebKit() {
  return browsers.firefoxWebKit.value;
}

/**
 * @returns {boolean}
 */
export function isSafari() {
  return browsers.safari.value;
}

/**
 * @returns {boolean}
 */
export function isEdge() {
  return browsers.edge.value;
}

/**
 * @returns {boolean}
 */
export function isEdgeWebKit() {
  return browsers.edgeWebKit.value;
}

/**
 * @returns {boolean}
 */
export function isMobileBrowser() {
  return browsers.mobile.value;
}

/**
 * @returns {boolean}
 */
export function isIOS() {
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
export function isIpadOS() {
  let {
    maxTouchPoints
  } = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : navigator;
  return maxTouchPoints > 2 && platforms.mac.value;
}

/**
 * @returns {boolean}
 */
export function isWindowsOS() {
  return platforms.win.value;
}

/**
 * @returns {boolean}
 */
export function isMacOS() {
  return platforms.mac.value;
}

/**
 * @returns {boolean}
 */
export function isLinuxOS() {
  return platforms.linux.value;
}