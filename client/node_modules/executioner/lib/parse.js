// public API
module.exports = parse;

/**
 * Parses command and updated with provided parameters
 *
 * @param   {string} cmd - command template
 * @param   {object} params - list of parameters
 * @returns {string} - updated command
 */
function parse(cmd, params)
{
  return Object.keys(params).reduce(iterator.bind(this, params), cmd);
}

/**
 * Iterator over params elements
 *
 * @param   {object} params - list of parameters
 * @param   {string} cmd - command template
 * @param   {string} p - parameter key
 * @returns {string} - updated command or empty string
 *                     if one of the params didn't pass the filter
 */
function iterator(params, cmd, p)
{
  var value = params[p];

  // shortcut
  if (!cmd) return cmd;

  // fold booleans into strings accepted by shell
  if (typeof value == 'boolean')
  {
    value = value ? '1' : '';
  }

  if (value !== null && ['undefined', 'string', 'number'].indexOf(typeof value) == -1)
  {
    // empty out cmd to signal the error
    return '';
  }

  // use empty string for `undefined`
  return cmd.replace(new RegExp('\\$\\{' + p + '\\}', 'g'), (value !== undefined && value !== null) ? value : '');
}
