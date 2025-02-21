"use strict";

exports.__esModule = true;
/**
 * @typedef {'top'|'bottom'|'inline_start'|'top_inline_start_corner'|'bottom_inline_start_corner'} CLONE_TYPES_ENUM
 */
const CLONE_TOP = exports.CLONE_TOP = 'top';
const CLONE_BOTTOM = exports.CLONE_BOTTOM = 'bottom';
const CLONE_INLINE_START = exports.CLONE_INLINE_START = 'inline_start';
const CLONE_TOP_INLINE_START_CORNER = exports.CLONE_TOP_INLINE_START_CORNER = 'top_inline_start_corner';
const CLONE_BOTTOM_INLINE_START_CORNER = exports.CLONE_BOTTOM_INLINE_START_CORNER = 'bottom_inline_start_corner';
const CLONE_TYPES = exports.CLONE_TYPES = [CLONE_TOP, CLONE_BOTTOM, CLONE_INLINE_START, CLONE_TOP_INLINE_START_CORNER, CLONE_BOTTOM_INLINE_START_CORNER];
const CLONE_CLASS_NAMES = exports.CLONE_CLASS_NAMES = new Map([[CLONE_TOP, `ht_clone_${CLONE_TOP}`], [CLONE_BOTTOM, `ht_clone_${CLONE_BOTTOM}`], [CLONE_INLINE_START, `ht_clone_${CLONE_INLINE_START} ht_clone_left`], [CLONE_TOP_INLINE_START_CORNER, `ht_clone_${CLONE_TOP_INLINE_START_CORNER} ht_clone_top_left_corner`], [CLONE_BOTTOM_INLINE_START_CORNER, `ht_clone_${CLONE_BOTTOM_INLINE_START_CORNER} ht_clone_bottom_left_corner`]]);