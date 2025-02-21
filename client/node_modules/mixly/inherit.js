// Public API
module.exports = mixInherit;

/**
 * Assigns prototype from the superclass to the target class,
 * compatible with node's builtin version, but browser-friendly
 * (without browserify magic, i.e. works with other packagers)
 *
 * Note: *Destroys target's prototype*
 *
 * @param   {function} target - Function (class) to update prototype on
 * @param   {function} super_ - Superclass to be prototype donor
 * @returns {function} - augmented function (class)
 */
function mixInherit(target, super_)
{
  target.super_ = super_;
  target.prototype = Object.create(super_.prototype, {
    constructor:
    {
      value       : target,
      enumerable  : false,
      writable    : true,
      configurable: true
    }
  });

  return target;
}
