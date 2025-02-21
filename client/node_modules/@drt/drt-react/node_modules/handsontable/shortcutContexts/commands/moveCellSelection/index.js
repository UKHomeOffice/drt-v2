"use strict";

exports.__esModule = true;
exports.getAllCommands = getAllCommands;
var _down = require("./down");
var _downByViewportHeight = require("./downByViewportHeight");
var _inlineEnd = require("./inlineEnd");
var _inlineStart = require("./inlineStart");
var _left = require("./left");
var _right = require("./right");
var _toMostBottom = require("./toMostBottom");
var _toMostBottomInlineEnd = require("./toMostBottomInlineEnd");
var _toMostInlineEnd = require("./toMostInlineEnd");
var _toMostInlineStart = require("./toMostInlineStart");
var _toMostLeft = require("./toMostLeft");
var _toMostRight = require("./toMostRight");
var _toMostTop = require("./toMostTop");
var _toMostTopInlineStart = require("./toMostTopInlineStart");
var _up = require("./up");
var _upByViewportHeight = require("./upByViewportHeight");
/**
 * Returns complete list of the shortcut commands for the cells moving feature.
 *
 * @returns {Function[]}
 */
function getAllCommands() {
  return [_down.command, _downByViewportHeight.command, _inlineEnd.command, _inlineStart.command, _left.command, _right.command, _toMostBottom.command, _toMostBottomInlineEnd.command, _toMostInlineEnd.command, _toMostInlineStart.command, _toMostLeft.command, _toMostRight.command, _toMostTop.command, _toMostTopInlineStart.command, _up.command, _upByViewportHeight.command];
}