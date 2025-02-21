"use strict";

exports.__esModule = true;
exports.isImmediatePropagationStopped = isImmediatePropagationStopped;
exports.isLeftClick = isLeftClick;
exports.isRightClick = isRightClick;
exports.isTouchEvent = isTouchEvent;
exports.offsetRelativeTo = offsetRelativeTo;
exports.stopImmediatePropagation = stopImmediatePropagation;
/**
 * Prevent other listeners of the same event from being called.
 *
 * @param {Event} event The mouse event object.
 */
function stopImmediatePropagation(event) {
  event.isImmediatePropagationEnabled = false;
  event.cancelBubble = true;
}

/**
 * Check if event was stopped by `stopImmediatePropagation`.
 *
 * @param {Event} event The mouse event object.
 * @returns {boolean}
 */
function isImmediatePropagationStopped(event) {
  return event.isImmediatePropagationEnabled === false;
}

/**
 * Check if provided event was triggered by clicking the right mouse button.
 *
 * @param {Event} event The mouse event object.
 * @returns {boolean}
 */
function isRightClick(event) {
  return event.button === 2;
}

/**
 * Check if provided event was triggered by clicking the left mouse button.
 *
 * @param {Event} event The mouse event object.
 * @returns {boolean}
 */
function isLeftClick(event) {
  return event.button === 0;
}

/**
 * Check if the provided event is a touch event.
 *
 * @param {Event} event The event object.
 * @returns {boolean}
 */
function isTouchEvent(event) {
  return event instanceof TouchEvent;
}

/**
 * Calculates the event offset until reaching the element defined by `relativeElement` argument.
 *
 * @param {Event} event The mouse event object.
 * @param {HTMLElement|undefined} [untilElement] The element to which the offset will be calculated.
 * @returns {{ x: number, y: number }}
 */
function offsetRelativeTo(event, untilElement) {
  const offset = {
    x: event.offsetX,
    y: event.offsetY
  };
  let element = event.target;
  if (!(untilElement instanceof HTMLElement) || element !== untilElement && element.contains(untilElement)) {
    return offset;
  }
  while (element !== untilElement) {
    offset.x += element.offsetLeft;
    offset.y += element.offsetTop;
    element = element.offsetParent;
  }
  return offset;
}