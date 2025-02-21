var copy = require('./copy.js');

// Public API
module.exports = mixAppend;

/**
 * Merges objects into the first one
 *
 * @param   {object} to - object to merge into
 * @param   {...object} from - object(s) to merge with
 * @returns {object} - mixed result object
 */
function mixAppend(to)
{
  var args = Array.prototype.slice.call(arguments)
    , i    = 0
    ;

  // it will start with `1` – second argument
  // leaving `to` out of the loop
  while (++i < args.length)
  {
    copy(to, args[i]);
  }

  return to;
}
