"use strict";

exports.__esModule = true;
exports.warnAboutPluginsConflict = warnAboutPluginsConflict;

var _console = require("../../helpers/console");

/* eslint-disable import/prefer-default-export */

/**
 * Warn users about problems when using `columnSorting` and `multiColumnSorting` plugins simultaneously.
 */
function warnAboutPluginsConflict() {
  (0, _console.warn)('Plugins `columnSorting` and `multiColumnSorting` should not be enabled simultaneously.');
}