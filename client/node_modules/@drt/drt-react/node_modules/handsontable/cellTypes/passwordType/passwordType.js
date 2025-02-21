"use strict";

exports.__esModule = true;
var _passwordEditor = require("../../editors/passwordEditor");
var _passwordRenderer = require("../../renderers/passwordRenderer");
const CELL_TYPE = exports.CELL_TYPE = 'password';
const PasswordCellType = exports.PasswordCellType = {
  CELL_TYPE,
  editor: _passwordEditor.PasswordEditor,
  renderer: _passwordRenderer.passwordRenderer,
  copyable: false
};