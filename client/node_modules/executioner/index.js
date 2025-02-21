/**
 * Executes provided command with supplied arguments
 */
var extend    = require('mixly/immutable')
  , run       = require('./lib/run.js')
  , terminate = require('./lib/terminate.js')
  ;

// Public API
module.exports           = executioner;
module.exports.terminate = terminate;
// default options
executioner.options = {};

/**
 * Executes provided command with supplied arguments
 *
 * @param   {string|array|object} commands - command to execute, optionally with parameter placeholders
 * @param   {object} params - parameters to pass to the command
 * @param   {object} [options] - command execution options like `cwd`
 * @param   {function} callback - `callback(err, output)`, will be invoked after all commands is finished
 * @returns {object} - execution control object
 */
function executioner(commands, params, options, callback)
{
  var keys, execOptions, collector = [];

  // optional options :)
  if (typeof options == 'function')
  {
    callback = options;
    options  = {};
  }

  // clone params, to mess with them without side effects
  params = extend(params);
  // copy options and with specified shell
  execOptions = extend(executioner.options || {}, options || {});

  // use it as array
  if (typeof commands == 'string')
  {
    commands = [ commands ];
  }

  // assume commands that aren't array are objects
  if (!Array.isArray(commands))
  {
    keys = Object.keys(commands);
  }

  run(collector, commands, keys, params, execOptions, function(err, results)
  {
    // output single command result as a string
    callback(err, (results && results.length == 1) ? results[0] : results);
  });

  return collector;
}
