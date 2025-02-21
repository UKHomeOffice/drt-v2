"use strict";

exports.__esModule = true;
exports.getAllCommands = getAllCommands;
var _down = require("./down");
var _downByViewportHeight = require("./downByViewportHeight");
var _left = require("./left");
var _right = require("./right");
var _toColumns = require("./toColumns");
var _toMostBottom = require("./toMostBottom");
var _toMostInlineEnd = require("./toMostInlineEnd");
var _toMostInlineStart = require("./toMostInlineStart");
var _toMostLeft = require("./toMostLeft");
var _toMostRight = require("./toMostRight");
var _toMostTop = require("./toMostTop");
var _toRows = require("./toRows");
var _up = require("./up");
var _upByViewportHeight = require("./upByViewportHeight");
/**
 * Returns complete list of the shortcut commands for the cells selection extending feature.
 *
 * @returns {Function[]}
 */
function getAllCommands() {
  return [_down.command, _downByViewportHeight.command, _left.command, _right.command, _toColumns.command, _toMostBottom.command, _toMostInlineEnd.command, _toMostInlineStart.command, _toMostLeft.command, _toMostRight.command, _toMostTop.command, _toRows.command, _up.command, _upByViewportHeight.command];
}