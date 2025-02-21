var parse   = require('./parse.js')
  , execute = require('./execute.js')
  ;

// Public API
module.exports = run;

/**
 * Runs specified command, replaces parameter placeholders.
 *
 * @param   {array} collector - job control object, contains list of results
 * @param   {object|array} commands - list of commands to execute
 * @param   {array} keys - list of commands keys
 * @param   {object} params - parameters for each command
 * @param   {object} options - options for child_process.exec
 * @param   {function} callback - invoked when all commands have been processed
 * @returns {void}
 */
function run(collector, commands, keys, params, options, callback)
{
  // either keys is array or commands
  var prefix, key, cmd = (keys || commands).shift();

  // done here
  if (!cmd)
  {
    return callback(null, collector);
  }

  // transform object into a command
  if (keys)
  {
    key = cmd;
    cmd = commands[key];
  }

  // update placeholders
  cmd = parse(cmd, params);

  // if loop over parameters terminated early
  // do not proceed further
  if (!cmd)
  {
    callback(new Error('Parameters should be a primitive value.'));
    return;
  }

  // populate command prefix
  prefix = parse(options.cmdPrefix || '', params);

  if (prefix)
  {
    cmd = prefix + ' ' + cmd;
  }

  execute(collector, cmd, options, function(error, output)
  {
    // store the output, if present
    if (output)
    {
      collector.push((key ? key + ': ' : '') + output);
    }

    // do not proceed further if error
    if (error)
    {
      // do not pass collector back if it a regular error
      callback(error, error.terminated ? collector : undefined);
      return;
    }

    // rinse, repeat
    run(collector, commands, keys, params, options, callback);
  });
}
