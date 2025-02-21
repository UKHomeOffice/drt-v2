var mixin     = require('./mixin.js')

  , append    = require('./append.js')
  , chain     = require('./chain.js')
  , copy      = require('./copy.js')
  , extend    = require('./extend.js')
  , funky     = require('./funky.js')
  , immutable = require('./immutable.js')
  , inherit   = require('./inherit.js')
  ;

// Public API

// use mixin (property copying to prototypes) as default function
module.exports = mixin;

// expose everything with explicit names
module.exports.append    = append;
module.exports.chain     = chain;
module.exports.copy      = copy;
module.exports.extend    = extend;
module.exports.funky     = funky;
module.exports.immutable = immutable;
module.exports.inherit   = inherit;
