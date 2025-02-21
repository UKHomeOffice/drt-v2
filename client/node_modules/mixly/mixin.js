// Public API
module.exports = mixin;

/**
 * Creates prototype chain with the properties
 * from the provided objects, by (shallow) copying
 * own properties from each object onto respective
 * elements in the chain
 *
 * @param   {...object} from - object(s) to mix in with
 * @returns {object} - new object with mixed in properties
 */
function mixin()
{
  var output = {}
    , args   = Array.prototype.slice.call(arguments)
    , i      = args.length
    ;

  while (i-- > 0)
  {
    mixinConstructor.prototype = output;
    output = new mixinConstructor(args[i]);
  }

  return output;
}

/**
 * Populates instance with provided properties (shallow copy)
 *
 * @private
 * @param   {object} source - source object to copy properties from
 * @returns {void}
 */
function mixinConstructor(source)
{
  var keys = Object.keys(source)
    , i    = keys.length
    ;

  while (i-- > 0) {
    this[keys[i]] = source[keys[i]];
  }
}
