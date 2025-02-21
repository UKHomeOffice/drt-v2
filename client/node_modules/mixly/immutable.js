var append = require('./append.js');

// Public API
module.exports = mixImmutable;

/**
 * Creates immutable (shallow) copy of the provided objects
 *
 * @param   {...object} from - object(s) to merge with
 * @returns {object} mixed result new object
 */
function mixImmutable()
{
  var args = Array.prototype.slice.apply(arguments);
  return append.apply(null, [{}].concat(args));
}
