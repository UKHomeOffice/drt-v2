import "core-js/modules/es.error.cause.js";
import "core-js/modules/es.array.push.js";
import "core-js/modules/es.array.unshift.js";
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { arrayEach } from "../../helpers/array.mjs";
import { hasOwnProperty } from "../../helpers/object.mjs";
/**
 * Command executor for ContextMenu.
 *
 * @private
 * @class CommandExecutor
 */
export class CommandExecutor {
  constructor(hotInstance) {
    /**
     * @type {Core}
     */
    _defineProperty(this, "hot", void 0);
    /**
     * @type {object}
     */
    _defineProperty(this, "commands", {});
    /**
     * @type {Function}
     */
    _defineProperty(this, "commonCallback", null);
    this.hot = hotInstance;
  }

  /**
   * Register command.
   *
   * @param {string} name Command name.
   * @param {object} commandDescriptor Command descriptor object with properties like `key` (command id),
   *                                   `callback` (task to execute), `name` (command name), `disabled` (command availability).
   */
  registerCommand(name, commandDescriptor) {
    this.commands[name] = commandDescriptor;
  }

  /**
   * Set common callback which will be trigger on every executed command.
   *
   * @param {Function} callback Function which will be fired on every command execute.
   */
  setCommonCallback(callback) {
    this.commonCallback = callback;
  }

  /**
   * Execute command by its name.
   *
   * @param {string} commandName Command id.
   * @param {*} params Arguments passed to command task.
   */
  execute(commandName) {
    for (var _len = arguments.length, params = new Array(_len > 1 ? _len - 1 : 0), _key = 1; _key < _len; _key++) {
      params[_key - 1] = arguments[_key];
    }
    const commandSplit = commandName.split(':');
    const commandNamePrimary = commandSplit[0];
    const subCommandName = commandSplit.length === 2 ? commandSplit[1] : null;
    let command = this.commands[commandNamePrimary];
    if (!command) {
      throw new Error(`Menu command '${commandNamePrimary}' not exists.`);
    }
    if (subCommandName && command.submenu) {
      command = findSubCommand(subCommandName, command.submenu.items);
    }
    if (command.disabled === true) {
      return;
    }
    if (typeof command.disabled === 'function' && command.disabled.call(this.hot) === true) {
      return;
    }
    if (hasOwnProperty(command, 'submenu')) {
      return;
    }
    const callbacks = [];
    if (typeof command.callback === 'function') {
      callbacks.push(command.callback);
    }
    if (typeof this.commonCallback === 'function') {
      callbacks.push(this.commonCallback);
    }
    params.unshift(commandSplit.join(':'));
    arrayEach(callbacks, callback => callback.apply(this.hot, params));
  }
}

/**
 * @param {string} subCommandName The subcommand name.
 * @param {string[]} subCommands The collection of the commands.
 * @returns {boolean}
 */
function findSubCommand(subCommandName, subCommands) {
  let command;
  arrayEach(subCommands, cmd => {
    const cmds = cmd.key ? cmd.key.split(':') : null;
    if (Array.isArray(cmds) && cmds[1] === subCommandName) {
      command = cmd;
      return false;
    }
  });
  return command;
}