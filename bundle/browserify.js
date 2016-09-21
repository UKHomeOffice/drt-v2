var inf = './start.js'
var outf = './bundle.js'
if(process.argv.length>2)
 inf = process.argv[2]
if(process.argv.length>3)
    outf = process.argv[3]
console.log(outf)
var fs = require("fs")
var browserify = require('browserify');
var b = browserify(inf,
    { standalone: 'Bundle'});
var out = fs.createWriteStream(outf)
b.bundle().pipe(out)
