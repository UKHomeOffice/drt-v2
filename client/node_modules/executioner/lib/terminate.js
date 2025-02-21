// Public API
module.exports = terminate;

/**
 * Terminates currently executed job,
 * if available
 *
 * @param   {object} control - job control with reference to the executed process
 * @returns {boolean} - true if there was a process to terminate, false otherwise
 */
function terminate(control)
{
  if (control && control._process && typeof control._process.kill == 'function')
  {
    control._process._executioner_killRequested = true;
    control._process.kill();
    return true;
  }

  return false;
}
