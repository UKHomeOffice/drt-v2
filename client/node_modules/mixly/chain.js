// Public API
module.exports = mixChain;

/**
 * Modifies prototype chain for the provided objects,
 * based on the order of the arguments
 *
 * @param {...object} object - objects to be part of the prototype chain
 * @returns {object} - augmented object
 */
function mixChain()
{
  var args = Array.prototype.slice.call(arguments)
    , i    = args.length
    ;

  while (--i > 0)
  {
    args[i-1].__proto__ = args[i];
  }

  return args[i];
}
