module.exports = require("./lib.js")
//todo this is not the right place to mount these to the window, where should we do that?

window.Spinner = module.exports.spinner;
// window.React = module.exports.react;
// window.ReactDOM = module.exports.reactdom;
window.ReactPopover = module.exports.popover;