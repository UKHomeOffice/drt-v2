var webpack = require('webpack');
//see https://scalacenter.github.io/scalajs-bundler/cookbook.html#custom-config for source
module.exports = require('./scalajs.webpack.config');

module.exports.loaders = [
    { test: require.resolve('jquery'), loader: 'expose-loader?jQuery!expose-loader?jQuery' }
]
