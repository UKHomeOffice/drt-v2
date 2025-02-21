var exec = require('child_process').exec;

// Public API
module.exports = execute;

/**
 * Executes provided command and store process reference in the state object
 *
 * @param   {array} collector - outputs storage
 * @param   {string} cmd - command itself
 * @param   {object} options - list of options for the command
 * @param   {function} callback - invoked when done
 * @returns {void}
 */
function execute(collector, cmd, options, callback)
{
  collector._process = exec(cmd, options, function(err, stdout, stderr)
  {
    var cmdPrefix = ''
      , child     = collector._process
      ;

    // normalize
    stdout = (stdout || '').trim();
    stderr = (stderr || '').trim();

    // clean up finished process reference
    delete collector._process;

    if (err)
    {
      // clean up shell errors
      err.message = (err.message || '').trim();
      err.stdout  = stdout;
      err.stderr  = stderr;

      // make it uniform across node versions (and platforms as side effect)
      // looking at you node@6.3+
      if (err.cmd && (cmdPrefix = err.cmd.replace(cmd, '')) != err.cmd)
      {
        err.cmd = cmd;
        err.message = err.message.replace(cmdPrefix, '');
      }

      // check if process has been willingly terminated
      if (child._executioner_killRequested && err.killed)
      {
        // mark job as properly terminated
        err.terminated = true;

        // it happen as supposed, so output might matter
        callback(err, stdout);
        return;
      }

      // just an error, no output matters
      callback(err);
      return;
    }

    // everything is good
    callback(null, stdout);
  });
}
