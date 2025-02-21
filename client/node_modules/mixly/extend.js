var chain   = require('./chain.js')
  , inherit = require('./inherit.js')
  ;

// Public API
module.exports = mixExtend;

/**
 * Extends target class with superclass,
 * assigns superclass prototype as prototype
 * of the target class and adds superclass itself
 * as `__proto__` of the target class,
 * allowing "static" methods inheritance.
 *
 * Note: *Destroys target's prototype*
 * 
 * @param   {function} target - Function (class) to update prototype on
 * @param   {function} super_ - Superclass to be prototype donor
 * @returns {function} - augmented function (class)
 */
function mixExtend(target, super_)
{
  return inherit(chain(target, super_), super_);
}
