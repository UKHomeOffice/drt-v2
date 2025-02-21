"use strict";

exports.__esModule = true;
exports.warnAboutPluginsConflict = warnAboutPluginsConflict;
var _console = require("../../helpers/console");
var _templateLiteralTag = require("../../helpers/templateLiteralTag");
/**
 * Warn users about problems when using `columnSorting` and `multiColumnSorting` plugins simultaneously.
 */
function warnAboutPluginsConflict() {
  (0, _console.warn)((0, _templateLiteralTag.toSingleLine)`Plugins \`columnSorting\` and \`multiColumnSorting\` should not be enabled simultaneously. 
    Only \`multiColumnSorting\` will work. The \`columnSorting\` plugin will be disabled.`);
}