import "core-js/modules/es.symbol";
import "core-js/modules/es.symbol.description";
import "core-js/modules/es.symbol.iterator";
import "core-js/modules/es.array.concat";
import "core-js/modules/es.array.from";
import "core-js/modules/es.array.includes";
import "core-js/modules/es.array.index-of";
import "core-js/modules/es.array.iterator";
import "core-js/modules/es.array.join";
import "core-js/modules/es.array.slice";
import "core-js/modules/es.object.to-string";
import "core-js/modules/es.regexp.constructor";
import "core-js/modules/es.regexp.to-string";
import "core-js/modules/es.string.includes";
import "core-js/modules/es.string.iterator";
import "core-js/modules/es.string.replace";
import "core-js/modules/es.string.split";
import "core-js/modules/es.string.trim";
import "core-js/modules/web.dom-collections.iterator";

function _toConsumableArray(arr) { return _arrayWithoutHoles(arr) || _iterableToArray(arr) || _nonIterableSpread(); }

function _nonIterableSpread() { throw new TypeError("Invalid attempt to spread non-iterable instance"); }

function _iterableToArray(iter) { if (Symbol.iterator in Object(iter) || Object.prototype.toString.call(iter) === "[object Arguments]") return Array.from(iter); }

function _arrayWithoutHoles(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = new Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } }

import { isIE8, isIE9, isSafari } from '../browser';
import { hasCaptionProblem, isClassListSupported, isTextContentSupported, isGetComputedStyleSupported } from '../feature';
/**
 * Get the parent of the specified node in the DOM tree.
 *
 * @param  {HTMLElement} element Element from which traversing is started.
 * @param  {Number} [level=0] Traversing deep level.
 * @return {HTMLElement|null}
 */

export function getParent(element) {
  var level = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : 0;
  var iteration = -1;
  var parent = null;
  var elementToCheck = element;

  while (elementToCheck !== null) {
    if (iteration === level) {
      parent = elementToCheck;
      break;
    }

    if (elementToCheck.host && elementToCheck.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      elementToCheck = elementToCheck.host;
    } else {
      iteration += 1;
      elementToCheck = elementToCheck.parentNode;
    }
  }

  return parent;
}
/**
 * Goes up the DOM tree (including given element) until it finds an element that matches the nodes or nodes name.
 * This method goes up through web components.
 *
 * @param {HTMLElement} element Element from which traversing is started
 * @param {Array} nodes Array of elements or Array of elements name
 * @param {HTMLElement} [until]
 * @returns {HTMLElement|null}
 */

export function closest(element, nodes, until) {
  var elementToCheck = element;

  while (elementToCheck !== null && elementToCheck !== until) {
    if (elementToCheck.nodeType === Node.ELEMENT_NODE && (nodes.indexOf(elementToCheck.nodeName) > -1 || nodes.indexOf(elementToCheck) > -1)) {
      return elementToCheck;
    }

    if (elementToCheck.host && elementToCheck.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      elementToCheck = elementToCheck.host;
    } else {
      elementToCheck = elementToCheck.parentNode;
    }
  }

  return null;
}
/**
 * Goes "down" the DOM tree (including given element) until it finds an element that matches the nodes or nodes name.
 *
 * @param {HTMLElement} element Element from which traversing is started
 * @param {Array} nodes Array of elements or Array of elements name
 * @param {HTMLElement} [until]
 * @returns {HTMLElement|null}
 */

export function closestDown(element, nodes, until) {
  var matched = [];
  var elementToCheck = element;

  while (elementToCheck) {
    elementToCheck = closest(elementToCheck, nodes, until);

    if (!elementToCheck || until && !until.contains(elementToCheck)) {
      break;
    }

    matched.push(elementToCheck);

    if (elementToCheck.host && elementToCheck.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      elementToCheck = elementToCheck.host;
    } else {
      elementToCheck = elementToCheck.parentNode;
    }
  }

  var length = matched.length;
  return length ? matched[length - 1] : null;
}
/**
 * Goes up the DOM tree and checks if element is child of another element.
 *
 * @param child Child element
 * @param {Object|String} parent Parent element OR selector of the parent element.
 *                               If string provided, function returns `true` for the first occurrence of element with that class.
 * @returns {Boolean}
 */

export function isChildOf(child, parent) {
  var node = child.parentNode;
  var queriedParents = [];

  if (typeof parent === 'string') {
    queriedParents = Array.prototype.slice.call(child.ownerDocument.querySelectorAll(parent), 0);
  } else {
    queriedParents.push(parent);
  }

  while (node !== null) {
    if (queriedParents.indexOf(node) > -1) {
      return true;
    }

    node = node.parentNode;
  }

  return false;
}
/**
 * Check if an element is part of `hot-table` web component.
 *
 * @param {Element} element
 * @returns {Boolean}
 */

export function isChildOfWebComponentTable(element) {
  var hotTableName = 'hot-table';
  var result = false;
  var parentNode = polymerWrap(element);

  function isHotTable(testElement) {
    return testElement.nodeType === Node.ELEMENT_NODE && testElement.nodeName === hotTableName.toUpperCase();
  }

  while (parentNode !== null) {
    if (isHotTable(parentNode)) {
      result = true;
      break;
    } else if (parentNode.host && parentNode.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      result = isHotTable(parentNode.host);

      if (result) {
        break;
      }

      parentNode = parentNode.host;
    }

    parentNode = parentNode.parentNode;
  }

  return result;
}
/* global Polymer wrap unwrap */

/**
 * Wrap element into polymer/webcomponent container if exists
 *
 * @param element
 * @returns {*}
 */

export function polymerWrap(element) {
  return typeof Polymer !== 'undefined' && typeof wrap === 'function' ? wrap(element) : element;
}
/**
 * Unwrap element from polymer/webcomponent container if exists
 *
 * @param element
 * @returns {*}
 */

export function polymerUnwrap(element) {
  return typeof Polymer !== 'undefined' && typeof unwrap === 'function' ? unwrap(element) : element;
}
/**
 * Counts index of element within its parent
 * WARNING: for performance reasons, assumes there are only element nodes (no text nodes). This is true for Walkotnable
 * Otherwise would need to check for nodeType or use previousElementSibling
 *
 * @see http://jsperf.com/sibling-index/10
 * @param {Element} element
 * @return {Number}
 */

export function index(element) {
  var i = 0;
  var elementToCheck = element;

  if (elementToCheck.previousSibling) {
    /* eslint-disable no-cond-assign */
    while (elementToCheck = elementToCheck.previousSibling) {
      i += 1;
    }
  }

  return i;
}
/**
 * Check if the provided overlay contains the provided element
 *
 * @param {String} overlay
 * @param {HTMLElement} element
 * @param {HTMLElement} root
 * @returns {boolean}
 */

export function overlayContainsElement(overlayType, element, root) {
  var overlayElement = root.parentElement.querySelector(".ht_clone_".concat(overlayType));
  return overlayElement ? overlayElement.contains(element) : null;
}

var _hasClass;

var _addClass;

var _removeClass;

function filterEmptyClassNames(classNames) {
  var result = [];

  if (!classNames || !classNames.length) {
    return result;
  }

  var len = 0;

  while (classNames[len]) {
    result.push(classNames[len]);
    len += 1;
  }

  return result;
}

if (isClassListSupported()) {
  var isSupportMultipleClassesArg = function isSupportMultipleClassesArg(rootDocument) {
    var element = rootDocument.createElement('div');
    element.classList.add('test', 'test2');
    return element.classList.contains('test2');
  };

  _hasClass = function _hasClass(element, className) {
    if (element.classList === void 0 || typeof className !== 'string' || className === '') {
      return false;
    }

    return element.classList.contains(className);
  };

  _addClass = function _addClass(element, classes) {
    var rootDocument = element.ownerDocument;
    var className = classes;

    if (typeof className === 'string') {
      className = className.split(' ');
    }

    className = filterEmptyClassNames(className);

    if (className.length > 0) {
      if (isSupportMultipleClassesArg(rootDocument)) {
        var _element$classList;

        (_element$classList = element.classList).add.apply(_element$classList, _toConsumableArray(className));
      } else {
        var len = 0;

        while (className && className[len]) {
          element.classList.add(className[len]);
          len += 1;
        }
      }
    }
  };

  _removeClass = function _removeClass(element, classes) {
    var className = classes;

    if (typeof className === 'string') {
      className = className.split(' ');
    }

    className = filterEmptyClassNames(className);

    if (className.length > 0) {
      if (isSupportMultipleClassesArg) {
        var _element$classList2;

        (_element$classList2 = element.classList).remove.apply(_element$classList2, _toConsumableArray(className));
      } else {
        var len = 0;

        while (className && className[len]) {
          element.classList.remove(className[len]);
          len += 1;
        }
      }
    }
  };
} else {
  var createClassNameRegExp = function createClassNameRegExp(className) {
    return new RegExp("(\\s|^)".concat(className, "(\\s|$)"));
  };

  _hasClass = function _hasClass(element, className) {
    // http://snipplr.com/view/3561/addclass-removeclass-hasclass/
    return element.className !== void 0 && createClassNameRegExp(className).test(element.className);
  };

  _addClass = function _addClass(element, classes) {
    var len = 0;
    var _className = element.className;
    var className = classes;

    if (typeof className === 'string') {
      className = className.split(' ');
    }

    if (_className === '') {
      _className = className.join(' ');
    } else {
      while (className && className[len]) {
        if (!createClassNameRegExp(className[len]).test(_className)) {
          _className += " ".concat(className[len]);
        }

        len += 1;
      }
    }

    element.className = _className;
  };

  _removeClass = function _removeClass(element, classes) {
    var len = 0;
    var _className = element.className;
    var className = classes;

    if (typeof className === 'string') {
      className = className.split(' ');
    }

    while (className && className[len]) {
      // String.prototype.trim is defined in polyfill.js
      _className = _className.replace(createClassNameRegExp(className[len]), ' ').trim();
      len += 1;
    }

    if (element.className !== _className) {
      element.className = _className;
    }
  };
}
/**
 * Checks if element has class name
 *
 * @param {HTMLElement} element
 * @param {String} className Class name to check
 * @returns {Boolean}
 */


export function hasClass(element, className) {
  return _hasClass(element, className);
}
/**
 * Add class name to an element
 *
 * @param {HTMLElement} element
 * @param {String|Array} className Class name as string or array of strings
 */

export function addClass(element, className) {
  return _addClass(element, className);
}
/**
 * Remove class name from an element
 *
 * @param {HTMLElement} element
 * @param {String|Array} className Class name as string or array of strings
 */

export function removeClass(element, className) {
  return _removeClass(element, className);
}
export function removeTextNodes(element, parent) {
  if (element.nodeType === 3) {
    parent.removeChild(element); // bye text nodes!
  } else if (['TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR'].indexOf(element.nodeName) > -1) {
    var childs = element.childNodes;

    for (var i = childs.length - 1; i >= 0; i--) {
      removeTextNodes(childs[i], element);
    }
  }
}
/**
 * Remove childs function
 * WARNING - this doesn't unload events and data attached by jQuery
 * http://jsperf.com/jquery-html-vs-empty-vs-innerhtml/9
 * http://jsperf.com/jquery-html-vs-empty-vs-innerhtml/11 - no siginificant improvement with Chrome remove() method
 *
 * @param element
 * @returns {void}
 */
//

export function empty(element) {
  var child;
  /* eslint-disable no-cond-assign */

  while (child = element.lastChild) {
    element.removeChild(child);
  }
}
export var HTML_CHARACTERS = /(<(.*)>|&(.*);)/;
/**
 * Insert content into element trying avoid innerHTML method.
 * @return {void}
 */

export function fastInnerHTML(element, content) {
  if (HTML_CHARACTERS.test(content)) {
    element.innerHTML = content;
  } else {
    fastInnerText(element, content);
  }
}
/**
 * Insert text content into element
 * @return {Boolean}
 */

export function fastInnerText(element, content) {
  var child = element.firstChild;

  if (child && child.nodeType === 3 && child.nextSibling === null) {
    // fast lane - replace existing text node
    if (isTextContentSupported) {
      // http://jsperf.com/replace-text-vs-reuse
      child.textContent = content;
    } else {
      // http://jsperf.com/replace-text-vs-reuse
      child.data = content;
    }
  } else {
    // slow lane - empty element and insert a text node
    empty(element);
    element.appendChild(element.ownerDocument.createTextNode(content));
  }
}
/**
 * Returns true if element is attached to the DOM and visible, false otherwise
 * @param elem
 * @returns {boolean}
 */

export function isVisible(elem) {
  var documentElement = elem.ownerDocument.documentElement;
  var next = elem;

  while (polymerUnwrap(next) !== documentElement) {
    // until <html> reached
    if (next === null) {
      // parent detached from DOM
      return false;
    } else if (next.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      if (next.host) {
        // this is Web Components Shadow DOM
        // see: http://w3c.github.io/webcomponents/spec/shadow/#encapsulation
        // according to spec, should be if (next.ownerDocument !== window.document), but that doesn't work yet
        if (next.host.impl) {
          // Chrome 33.0.1723.0 canary (2013-11-29) Web Platform features disabled
          return isVisible(next.host.impl);
        } else if (next.host) {
          // Chrome 33.0.1723.0 canary (2013-11-29) Web Platform features enabled
          return isVisible(next.host);
        }

        throw new Error('Lost in Web Components world');
      } else {
        return false; // this is a node detached from document in IE8
      }
    } else if (next.style && next.style.display === 'none') {
      return false;
    }

    next = next.parentNode;
  }

  return true;
}
/**
 * Returns elements top and left offset relative to the document. Function is not compatible with jQuery offset.
 *
 * @param {HTMLElement} elem
 * @return {Object} Returns object with `top` and `left` props
 */

export function offset(elem) {
  var rootDocument = elem.ownerDocument;
  var rootWindow = rootDocument.defaultView;
  var documentElement = rootDocument.documentElement;
  var elementToCheck = elem;
  var offsetLeft;
  var offsetTop;
  var lastElem;
  var box;

  if (hasCaptionProblem() && elementToCheck.firstChild && elementToCheck.firstChild.nodeName === 'CAPTION') {
    // fixes problem with Firefox ignoring <caption> in TABLE offset (see also export outerHeight)
    // http://jsperf.com/offset-vs-getboundingclientrect/8
    box = elementToCheck.getBoundingClientRect();
    return {
      top: box.top + (rootWindow.pageYOffset || documentElement.scrollTop) - (documentElement.clientTop || 0),
      left: box.left + (rootWindow.pageXOffset || documentElement.scrollLeft) - (documentElement.clientLeft || 0)
    };
  }

  offsetLeft = elementToCheck.offsetLeft;
  offsetTop = elementToCheck.offsetTop;
  lastElem = elementToCheck;
  /* eslint-disable no-cond-assign */

  while (elementToCheck = elementToCheck.offsetParent) {
    // from my observation, document.body always has scrollLeft/scrollTop == 0
    if (elementToCheck === rootDocument.body) {
      break;
    }

    offsetLeft += elementToCheck.offsetLeft;
    offsetTop += elementToCheck.offsetTop;
    lastElem = elementToCheck;
  } // slow - http://jsperf.com/offset-vs-getboundingclientrect/6


  if (lastElem && lastElem.style.position === 'fixed') {
    // if(lastElem !== document.body) { //faster but does gives false positive in Firefox
    offsetLeft += rootWindow.pageXOffset || documentElement.scrollLeft;
    offsetTop += rootWindow.pageYOffset || documentElement.scrollTop;
  }

  return {
    left: offsetLeft,
    top: offsetTop
  };
}
/**
 * Returns the document's scrollTop property.
 *
 * @param {Window} rootWindow
 * @returns {Number}
 */
// eslint-disable-next-line no-restricted-globals

export function getWindowScrollTop() {
  var rootWindow = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : window;
  var res = rootWindow.scrollY;

  if (res === void 0) {
    // IE8-11
    res = rootWindow.document.documentElement.scrollTop;
  }

  return res;
}
/**
 * Returns the document's scrollLeft property.
 *
 * @param {Window} rootWindow
 * @returns {Number}
 */
// eslint-disable-next-line no-restricted-globals

export function getWindowScrollLeft() {
  var rootWindow = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : window;
  var res = rootWindow.scrollX;

  if (res === void 0) {
    // IE8-11
    res = rootWindow.document.documentElement.scrollLeft;
  }

  return res;
}
/**
 * Returns the provided element's scrollTop property.
 *
 * @param element
 * @param {Window} rootWindow
 * @returns {Number}
 */
// eslint-disable-next-line no-restricted-globals

export function getScrollTop(element) {
  var rootWindow = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : window;

  if (element === rootWindow) {
    return getWindowScrollTop(rootWindow);
  }

  return element.scrollTop;
}
/**
 * Returns the provided element's scrollLeft property.
 *
 * @param element
 * @param {Window} rootWindow
 * @returns {Number}
 */
// eslint-disable-next-line no-restricted-globals

export function getScrollLeft(element) {
  var rootWindow = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : window;

  if (element === rootWindow) {
    return getWindowScrollLeft(rootWindow);
  }

  return element.scrollLeft;
}
/**
 * Returns a DOM element responsible for scrolling of the provided element.
 *
 * @param {HTMLElement} element
 * @returns {HTMLElement} Element's scrollable parent
 */

export function getScrollableElement(element) {
  var rootDocument = element.ownerDocument;
  var rootWindow = rootDocument ? rootDocument.defaultView : void 0;

  if (!rootDocument) {
    rootDocument = element.document ? element.document : element;
    rootWindow = rootDocument.defaultView;
  }

  var props = ['auto', 'scroll'];
  var supportedGetComputedStyle = isGetComputedStyleSupported();
  var el = element.parentNode;

  while (el && el.style && rootDocument.body !== el) {
    var _el$style = el.style,
        overflow = _el$style.overflow,
        overflowX = _el$style.overflowX,
        overflowY = _el$style.overflowY;

    if ([overflow, overflowX, overflowY].includes('scroll')) {
      return el;
    } else if (supportedGetComputedStyle) {
      var _rootWindow$getComput = rootWindow.getComputedStyle(el);

      overflow = _rootWindow$getComput.overflow;
      overflowX = _rootWindow$getComput.overflowX;
      overflowY = _rootWindow$getComput.overflowY;

      if (props.includes(overflow) || props.includes(overflowX) || props.includes(overflowY)) {
        return el;
      }
    } // The '+ 1' after the scrollHeight/scrollWidth is to prevent problems with zoomed out Chrome.


    if (el.clientHeight <= el.scrollHeight + 1 && (props.includes(overflowY) || props.includes(overflow))) {
      return el;
    }

    if (el.clientWidth <= el.scrollWidth + 1 && (props.includes(overflowX) || props.includes(overflow))) {
      return el;
    }

    el = el.parentNode;
  }

  return rootWindow;
}
/**
 * Returns a DOM element responsible for trimming the provided element.
 *
 * @param {HTMLElement} base Base element
 * @returns {HTMLElement} Base element's trimming parent
 */

export function getTrimmingContainer(base) {
  var rootDocument = base.ownerDocument;
  var rootWindow = rootDocument.defaultView;
  var el = base.parentNode;

  while (el && el.style && rootDocument.body !== el) {
    if (el.style.overflow !== 'visible' && el.style.overflow !== '') {
      return el;
    }

    var computedStyle = getComputedStyle(el, rootWindow);
    var allowedProperties = ['scroll', 'hidden', 'auto'];
    var property = computedStyle.getPropertyValue('overflow');
    var propertyY = computedStyle.getPropertyValue('overflow-y');
    var propertyX = computedStyle.getPropertyValue('overflow-x');

    if (allowedProperties.includes(property) || allowedProperties.includes(propertyY) || allowedProperties.includes(propertyX)) {
      return el;
    }

    el = el.parentNode;
  }

  return rootWindow;
}
/**
 * Returns a style property for the provided element. (Be it an inline or external style).
 *
 * @param {HTMLElement} element
 * @param {String} prop Wanted property
 * @param {Window} rootWindow
 * @returns {String|undefined} Element's style property
 */
// eslint-disable-next-line no-restricted-globals

export function getStyle(element, prop) {
  var rootWindow = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : window;

  if (!element) {
    return;
  } else if (element === rootWindow) {
    if (prop === 'width') {
      return "".concat(rootWindow.innerWidth, "px");
    } else if (prop === 'height') {
      return "".concat(rootWindow.innerHeight, "px");
    }

    return;
  }

  var styleProp = element.style[prop];

  if (styleProp !== '' && styleProp !== void 0) {
    return styleProp;
  }

  var computedStyle = getComputedStyle(element, rootWindow);

  if (computedStyle[prop] !== '' && computedStyle[prop] !== void 0) {
    return computedStyle[prop];
  }
}
/**
 * Returns a computed style object for the provided element. (Needed if style is declared in external stylesheet).
 *
 * @param element
 * @param {Window} rootWindow
 * @returns {IEElementStyle|CssStyle} Elements computed style object
 */
// eslint-disable-next-line no-restricted-globals

export function getComputedStyle(element) {
  var rootWindow = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : window;
  return element.currentStyle || rootWindow.getComputedStyle(element);
}
/**
 * Returns the element's outer width.
 *
 * @param element
 * @returns {number} Element's outer width
 */

export function outerWidth(element) {
  return element.offsetWidth;
}
/**
 * Returns the element's outer height
 *
 * @param elem
 * @returns {number} Element's outer height
 */

export function outerHeight(elem) {
  if (hasCaptionProblem() && elem.firstChild && elem.firstChild.nodeName === 'CAPTION') {
    // fixes problem with Firefox ignoring <caption> in TABLE.offsetHeight
    // jQuery (1.10.1) still has this unsolved
    // may be better to just switch to getBoundingClientRect
    // http://bililite.com/blog/2009/03/27/finding-the-size-of-a-table/
    // http://lists.w3.org/Archives/Public/www-style/2009Oct/0089.html
    // http://bugs.jquery.com/ticket/2196
    // http://lists.w3.org/Archives/Public/www-style/2009Oct/0140.html#start140
    return elem.offsetHeight + elem.firstChild.offsetHeight;
  }

  return elem.offsetHeight;
}
/**
 * Returns the element's inner height.
 *
 * @param element
 * @returns {number} Element's inner height
 */

export function innerHeight(element) {
  return element.clientHeight || element.innerHeight;
}
/**
 * Returns the element's inner width.
 *
 * @param element
 * @returns {number} Element's inner width
 */

export function innerWidth(element) {
  return element.clientWidth || element.innerWidth;
}
export function addEvent(element, event, callback) {
  var rootWindow = element.defaultView;

  if (!rootWindow) {
    rootWindow = element.document ? element : element.ownerDocument.defaultView;
  }

  if (rootWindow.addEventListener) {
    element.addEventListener(event, callback, false);
  } else {
    element.attachEvent("on".concat(event), callback);
  }
}
export function removeEvent(element, event, callback) {
  var rootWindow = element.defaultView;

  if (!rootWindow) {
    rootWindow = element.document ? element : element.ownerDocument.defaultView;
  }

  if (rootWindow.removeEventListener) {
    element.removeEventListener(event, callback, false);
  } else {
    element.detachEvent("on".concat(event), callback);
  }
}
/**
 * Returns caret position in text input
 *
 * @author https://stackoverflow.com/questions/263743/how-to-get-caret-position-in-textarea
 * @return {Number}
 */

export function getCaretPosition(el) {
  var rootDocument = el.ownerDocument;

  if (el.selectionStart) {
    return el.selectionStart;
  } else if (rootDocument.selection) {
    // IE8
    el.focus();
    var r = rootDocument.selection.createRange();

    if (r === null) {
      return 0;
    }

    var re = el.createTextRange();
    var rc = re.duplicate();
    re.moveToBookmark(r.getBookmark());
    rc.setEndPoint('EndToStart', re);
    return rc.text.length;
  }

  return 0;
}
/**
 * Returns end of the selection in text input
 *
 * @return {Number}
 */

export function getSelectionEndPosition(el) {
  var rootDocument = el.ownerDocument;

  if (el.selectionEnd) {
    return el.selectionEnd;
  } else if (rootDocument.selection) {
    // IE8
    var r = rootDocument.selection.createRange();

    if (r === null) {
      return 0;
    }

    var re = el.createTextRange();
    return re.text.indexOf(r.text) + r.text.length;
  }

  return 0;
}
/**
 * Returns text under selection.
 *
 * @param {Window} rootWindow
 * @returns {String}
 */
// eslint-disable-next-line no-restricted-globals

export function getSelectionText() {
  var rootWindow = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : window;
  var rootDocument = rootWindow.document;
  var text = '';

  if (rootWindow.getSelection) {
    text = rootWindow.getSelection().toString();
  } else if (rootDocument.selection && rootDocument.selection.type !== 'Control') {
    text = rootDocument.selection.createRange().text;
  }

  return text;
}
/**
 * Cross-platform helper to clear text selection.
 *
 * @param {Window} rootWindow
 */
// eslint-disable-next-line no-restricted-globals

export function clearTextSelection() {
  var rootWindow = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : window;
  var rootDocument = rootWindow.document; // http://stackoverflow.com/questions/3169786/clear-text-selection-with-javascript

  if (rootWindow.getSelection) {
    if (rootWindow.getSelection().empty) {
      // Chrome
      rootWindow.getSelection().empty();
    } else if (rootWindow.getSelection().removeAllRanges) {
      // Firefox
      rootWindow.getSelection().removeAllRanges();
    }
  } else if (rootDocument.selection) {
    // IE?
    rootDocument.selection.empty();
  }
}
/**
 * Sets caret position in text input.
 *
 * @author http://blog.vishalon.net/index.php/javascript-getting-and-setting-caret-position-in-textarea/
 * @param {Element} element
 * @param {Number} pos
 * @param {Number} endPos
 */

export function setCaretPosition(element, pos, endPos) {
  if (endPos === void 0) {
    endPos = pos;
  }

  if (element.setSelectionRange) {
    element.focus();

    try {
      element.setSelectionRange(pos, endPos);
    } catch (err) {
      var elementParent = element.parentNode;
      var parentDisplayValue = elementParent.style.display;
      elementParent.style.display = 'block';
      element.setSelectionRange(pos, endPos);
      elementParent.style.display = parentDisplayValue;
    }
  } else if (element.createTextRange) {
    // IE8
    var range = element.createTextRange();
    range.collapse(true);
    range.moveEnd('character', endPos);
    range.moveStart('character', pos);
    range.select();
  }
}
var cachedScrollbarWidth;
/**
 * Helper to calculate scrollbar width.
 * Source: https://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
 *
 * @private
 * @param {Document} rootDocument
 */
// eslint-disable-next-line no-restricted-globals

function walkontableCalculateScrollbarWidth() {
  var rootDocument = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : document;
  var inner = rootDocument.createElement('div');
  inner.style.height = '200px';
  inner.style.width = '100%';
  var outer = rootDocument.createElement('div');
  outer.style.boxSizing = 'content-box';
  outer.style.height = '150px';
  outer.style.left = '0px';
  outer.style.overflow = 'hidden';
  outer.style.position = 'absolute';
  outer.style.top = '0px';
  outer.style.width = '200px';
  outer.style.visibility = 'hidden';
  outer.appendChild(inner);
  (rootDocument.body || rootDocument.documentElement).appendChild(outer);
  var w1 = inner.offsetWidth;
  outer.style.overflow = 'scroll';
  var w2 = inner.offsetWidth;

  if (w1 === w2) {
    w2 = outer.clientWidth;
  }

  (rootDocument.body || rootDocument.documentElement).removeChild(outer);
  return w1 - w2;
}
/**
 * Returns the computed width of the native browser scroll bar.
 *
 * @param {Document} rootDocument
 * @return {Number} width
 */
// eslint-disable-next-line no-restricted-globals


export function getScrollbarWidth() {
  var rootDocument = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : document;

  if (cachedScrollbarWidth === void 0) {
    cachedScrollbarWidth = walkontableCalculateScrollbarWidth(rootDocument);
  }

  return cachedScrollbarWidth;
}
/**
 * Checks if the provided element has a vertical scrollbar.
 *
 * @param {HTMLElement} element
 * @returns {Boolean}
 */

export function hasVerticalScrollbar(element) {
  return element.offsetWidth !== element.clientWidth;
}
/**
 * Checks if the provided element has a vertical scrollbar.
 *
 * @param {HTMLElement} element
 * @returns {Boolean}
 */

export function hasHorizontalScrollbar(element) {
  return element.offsetHeight !== element.clientHeight;
}
/**
 * Sets overlay position depending on it's type and used browser
 */

export function setOverlayPosition(overlayElem, left, top) {
  if (isIE8() || isIE9()) {
    overlayElem.style.top = top;
    overlayElem.style.left = left;
  } else if (isSafari()) {
    overlayElem.style['-webkit-transform'] = "translate3d(".concat(left, ",").concat(top, ",0)");
    overlayElem.style['-webkit-transform'] = "translate3d(".concat(left, ",").concat(top, ",0)");
  } else {
    overlayElem.style.transform = "translate3d(".concat(left, ",").concat(top, ",0)");
  }
}
export function getCssTransform(element) {
  var transform;

  if (element.style.transform && (transform = element.style.transform) !== '') {
    return ['transform', transform];
  } else if (element.style['-webkit-transform'] && (transform = element.style['-webkit-transform']) !== '') {
    return ['-webkit-transform', transform];
  }

  return -1;
}
export function resetCssTransform(element) {
  if (element.style.transform && element.style.transform !== '') {
    element.style.transform = '';
  } else if (element.style['-webkit-transform'] && element.style['-webkit-transform'] !== '') {
    element.style['-webkit-transform'] = '';
  }
}
/**
 * Determines if the given DOM element is an input field.
 * Notice: By 'input' we mean input, textarea and select nodes
 *
 * @param {HTMLElement} element - DOM element
 * @returns {Boolean}
 */

export function isInput(element) {
  var inputs = ['INPUT', 'SELECT', 'TEXTAREA'];
  return element && (inputs.indexOf(element.nodeName) > -1 || element.contentEditable === 'true');
}
/**
 * Determines if the given DOM element is an input field placed OUTSIDE of HOT.
 * Notice: By 'input' we mean input, textarea and select nodes
 *
 * @param {HTMLElement} element - DOM element
 * @returns {Boolean}
 */

export function isOutsideInput(element) {
  return isInput(element) && element.className.indexOf('handsontableInput') === -1 && element.className.indexOf('copyPaste') === -1;
}
/**
 * Check if the given DOM element can be focused (by using "select" method).
 *
 * @param {HTMLElement} element - DOM element
 */

export function selectElementIfAllowed(element) {
  var activeElement = element.ownerDocument.activeElement;

  if (!isOutsideInput(activeElement)) {
    element.select();
  }
}