// Public API
module.exports = mixCopy;

/**
 * Copies (shallow) own properties between provided objects
 *
 * @param   {object} to - source object to copy properties to
 * @param   {object} from - source object to copy properties from
 * @returns {object} – augmented `to` object
 */
function mixCopy(to, from)
{
  var keys = Object.keys(from)
    , i    = keys.length
    ;

  while (i-- > 0)
  {
    to[keys[i]] = from[keys[i]];
  }

  return to;
}
