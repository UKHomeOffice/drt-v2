"use strict";

exports.__esModule = true;
/**
 * Selection type that is visible only if the row or column header is clicked. If that happened
 * all row or column header layers are highlighted.
 *
 * @type {string}
 */
const ACTIVE_HEADER_TYPE = exports.ACTIVE_HEADER_TYPE = 'active-header';
/**
 * Selection type that is visible only if the a cell or cells are clicked. If that happened
 * only the most closest to the cells row or column header is highlighted.
 *
 * @type {string}
 */
const HEADER_TYPE = exports.HEADER_TYPE = 'header';
/**
 * Selection type that is visible when a cell or cells are clicked. The selected cells are
 * highlighted.
 *
 * @type {string}
 */
const AREA_TYPE = exports.AREA_TYPE = 'area';
/**
 * Selection type defines a cell that follows the user (keyboard navigation).
 *
 * @type {string}
 */
const FOCUS_TYPE = exports.FOCUS_TYPE = 'focus';
/**
 * Selection type defines borders for the autofill functionality.
 *
 * @type {string}
 */
const FILL_TYPE = exports.FILL_TYPE = 'fill';
/**
 * Selection type defines highlights for the `currentRowClassName` option.
 *
 * @type {string}
 */
const ROW_TYPE = exports.ROW_TYPE = 'row';
/**
 * Selection type defines highlights for the `currentColumnClassName` option.
 *
 * @type {string}
 */
const COLUMN_TYPE = exports.COLUMN_TYPE = 'column';
/**
 * Selection type defines highlights managed by the CustomBorders plugin.
 *
 * @type {string}
 */
const CUSTOM_SELECTION_TYPE = exports.CUSTOM_SELECTION_TYPE = 'custom-selection';