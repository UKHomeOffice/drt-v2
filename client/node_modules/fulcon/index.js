// Public API
module.exports = fulcon;

/**
 * Creates wrapper function with the same signature
 *
 * @param   {function} source - function to clone
 * @returns {function} - wrapped function
 */
function fulcon(source)
{
  // makes "clone" look and smell the same
  // strip `bound ` prefix from the function name
  return Function('source', 'return function ' + source.name.replace(/^bound /, '') + '(' + Array(source.length + 1).join('a').split('').join(',') + '){ return source.apply(this, arguments); }')(source);
}
