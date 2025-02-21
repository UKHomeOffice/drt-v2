# mixly [![NPM Module](https://img.shields.io/npm/v/mixly.svg?style=flat)](https://www.npmjs.com/package/mixly)

Collection of mixin tools for objects and functions.

[![PhantomJS Build](https://img.shields.io/travis/alexindigo/mixly/master.svg?label=browser&style=flat)](https://travis-ci.org/alexindigo/mixly)
[![Linux Build](https://img.shields.io/travis/alexindigo/mixly/master.svg?label=linux:0.10-5.x&style=flat)](https://travis-ci.org/alexindigo/mixly)
[![Windows Build](https://img.shields.io/appveyor/ci/alexindigo/mixly/master.svg?label=windows:0.10-5.x&style=flat)](https://ci.appveyor.com/project/alexindigo/mixly)
<!-- [![Readme](https://img.shields.io/badge/readme-tested-brightgreen.svg?style=flat)](https://www.npmjs.com/package/reamde) -->

[![Coverage Status](https://img.shields.io/coveralls/alexindigo/mixly/master.svg?label=code+coverage&style=flat)](https://coveralls.io/github/alexindigo/mixly?branch=master)
[![Dependency Status](https://img.shields.io/david/alexindigo/mixly.svg?style=flat)](https://david-dm.org/alexindigo/mixly)
[![bitHound Overall Score](https://www.bithound.io/github/alexindigo/mixly/badges/score.svg)](https://www.bithound.io/github/alexindigo/mixly)

| compression     |    size |
| :-------------- | ------: |
| mixly.js        | 8.94 kB |
| mixly.min.js    | 2.93 kB |
| mixly.min.js.gz |   964 B |


## Table of Contents

<!-- TOC -->
- [Install](#install)
- [API](#api)
  - [mixly](#mixly)
  - [append](#append)
  - [chain](#chain)
  - [copy](#copy)
  - [extend](#extend)
  - [funky](#funky)
  - [immutable](#immutable)
  - [inherit](#inherit)
- [License](#license)

<!-- TOC END -->

## Install

```shell
$ npm install --save mixly
```

## API

### mixly

_`mixly(object[, object[, ...object]])`_

Alias: _`mixly/mixin`_

Creates prototype chain with the properties from the provided objects, by (shallow) copying own properties from each object onto respective elements in the chain.

```javascript
var mixly = require('mixly');

var o1 = { O1: true, commonThing: 'o1' }
  , o2 = { O2: true, commonThing: 'o2' }
  , o3 = { O3: true, commonThing: 'o3' }
  ;

// (o1) -> (o2) -> (o3)
var o0 = mixly(o1, o2, o3);

assert.notStrictEqual(Object.getPrototypeOf(o0), o1, 'Object `o0` does not have prototype set to object `o1`');
assert.notStrictEqual(Object.getPrototypeOf(o1), o2, 'Object `o1` does not have prototype set to object `o2`');
assert.notStrictEqual(Object.getPrototypeOf(o2), o3, 'Object `o2` does not have prototype set to object `o3`');

assert.strictEqual(o0.O1, true, 'copied properties from the first object');
assert.strictEqual(o0.O2, true, 'has access to the properties of the second object');
assert.strictEqual(o0.O3, true, 'has access to the properties of the third object');
assert.strictEqual(o0.commonThing, 'o1', 'shared properties from first object "win"');
assert.strictEqual(Object.getPrototypeOf(o0).commonThing, 'o2', 'has access to the shared properties of the second object');
assert.strictEqual(Object.getPrototypeOf(Object.getPrototypeOf(o0)).commonThing, 'o3', 'has access to the shared properties of the third object');

assert.strictEqual(o0.hasOwnProperty('commonThing'), true, 'has shared properties from first object as own');
assert.strictEqual(o0.hasOwnProperty('O1'), true, 'has shared properties from first object as own');
assert.strictEqual(o0.hasOwnProperty('O2'), false, 'does not own properties from the second object');
assert.strictEqual(o0.hasOwnProperty('O3'), false, 'does not own properties from the third object');
```

More details in [test/mixin.js](test/mixin.js) test file.

### append

_`mixly.append(object[, object[, ...object]])`_

Aliases: _`mixly/append`_, _`mixly/flat`_

Appends objects' properties (shallow copy) into the first object.

```javascript
var append = require('mixly/append');

var o1 = { O1: true, commonThing: 'o1' }
  , o2 = { O2: true, commonThing: 'o2' }
  , o3 = { O3: true, commonThing: 'o3' }
  ;

// o1 + o2 + o3
var oX = append(o1, o2, o3);

assert.strictEqual(oX, o1, 'first argument was modified');
assert.strictEqual(oX.O1, true, 'kept properties from the first object');
assert.strictEqual(oX.O2, true, 'obtained properties from the second object');
assert.strictEqual(oX.O3, true, 'obtained properties from the third object');
assert.strictEqual(oX.commonThing, 'o3', 'last object in the list overrides shared properties');
```

More details in [test/append.js](test/append.js) test file.

### chain

_`mixly.chain(object[, object[, ...object]])`_

Aliases: _`mixly/chain`_, _`mixly/proto`_

Modifies prototype chain for the provided objects, based on the order of the arguments.

```javascript
var chain = require('mixly/chain');

var o1 = { O1: true, commonThing: 'o1' }
  , o2 = { O2: true, commonThing: 'o2' }
  , o3 = { O3: true, commonThing: 'o3' }
  ;

// o1 -> o2 -> o3
mixly.chain(o1, o2, o3);

assert.strictEqual(Object.getPrototypeOf(o1), o2, 'Object `o1` has prototype set to object `o2`');
assert.strictEqual(Object.getPrototypeOf(o2), o3, 'Object `o2` has prototype set to object `o3`');

assert.strictEqual(o1.O1, true, 'kept properties from the first object');
assert.strictEqual(o1.O2, true, 'has access to the properties of the second object');
assert.strictEqual(o1.O3, true, 'has access to the properties of the third object');
assert.strictEqual(o1.commonThing, 'o1', 'kept own shared properties');
assert.strictEqual(Object.getPrototypeOf(o1).commonThing, 'o2', 'has access to the shared properties of the second object');
assert.strictEqual(Object.getPrototypeOf(Object.getPrototypeOf(o1)).commonThing, 'o3', 'has access to the shared properties of the third object');

assert.strictEqual(o1.hasOwnProperty('commonThing'), true, 'kept own shared properties');
assert.strictEqual(o1.hasOwnProperty('O1'), true, 'kept own unique properties');
assert.strictEqual(o1.hasOwnProperty('O2'), false, 'does not own properties from the second object');
assert.strictEqual(o1.hasOwnProperty('O3'), false, 'does not own properties from the third object');
```

More details in [test/chain.js](test/chain.js) test file.

### copy

_`mixly.copy(object, object)`_

Alias: _`mixly/copy`_

Copies (shallow) own properties between provided objects. _Used internally by other `mixly` methods._

```javascript
var copy = require('mixly/copy');

var o1 = { O1: true, commonThing: 'o1' }
  , o2 = { O2: true, commonThing: 'o2' }
  ;

// o1 + o2
var oX = copy(o1, o2);

assert.strictEqual(oX, o1, 'first argument was modified');
assert.strictEqual(oX.O1, true, 'kept properties from the first object');
assert.strictEqual(oX.O2, true, 'obtained properties from the second object');
assert.strictEqual(oX.commonThing, 'o2', 'last object in the list overrides shared properties');
```

More details in [test/copy.js](test/copy.js) test file.

### extend

_`mixly.extend(function, function)`_

Alias: _`mixly/extend`_

Extends target class with superclass, assigns superclass prototype as prototype of the target class and adds superclass itself as `__proto__` of the target class, allowing "static" methods inheritance.

```javascript
var extend = require('mixly/extend');

function F1()
{
  F1.super_.apply(this, arguments);
  this.p0 += 'd2';
}
F1.prototype.p1 = 'f1';
F1.static1 = true;

function F2()
{
  this.p0 = 'r2';
}
F2.prototype.p2 = 'f2';
F2.static2 = true;

// F1 -> F2
extend(F1, F2);

assert.strictEqual('p1' in F1.prototype, false, 'original prototype is gone');
assert.strictEqual('p2' in F1.prototype, true, 'replaced with new prototype');

assert.strictEqual(F1.static1, true, 'original static property is accessible');
assert.strictEqual(F1.static2, true, 'extended static property is accessible');

// new instance
var f1 = new F1();

assert.strictEqual(f1.p0, 'r2d2', 'constructor executes provided super constructor');
```

More details in [test/extend.js](test/extend.js) test file.

### funky

_`mixly.funky(function[, function[, ...function]])`_

Alias: _`mixly/funky`_

Creates prototype chain from the provided functions, by (shallow) copying prototypes from each function onto respective elements in the chain.

```javascript
var funky = require('mixly/funky');

function F1()
{
  this.super_.apply(this, arguments);
  this.f1 = true;
}
F1.prototype.f1p = true;

function F2()
{
  // this.super_ is reference to itself
  // we're in the f0 context
  // don't call super_.super_
  this.f2 = true;
}
F2.prototype.f2p = true;

function F3()
{
  // never gets here
  this.f3 = true;
}
F3.prototype.f3p = true;

// F0 -> (F1) -> (F2) -> (F3)
var F0 = mixly.clone(F1, F2, F3);
var f0 = new F0();

assert.notStrictEqual(F0.prototype, F1.prototype, 'is not exactly F1');
assert.strictEqual(F0.prototype.f1p, true, 'but close');

F0.prototype.bla = 42;
assert.strictEqual('bla' in F1.prototype, false, 'F1 stays untouched');

assert.strictEqual(f0.f1, true, 'executed F1 constructor');
assert.strictEqual(f0.f2, true, 'executed F2 constructor');
assert.strictEqual('f3' in f0, false, 'skipped F3 constructor');

assert.strictEqual(f0.f1p, true, 'inherited F1 prototype properties');
assert.strictEqual(f0.f2p, true, 'inherited F2 prototype properties');
assert.strictEqual(f0.f3p, true, 'inherited F3 prototype properties');
```

More details in [test/funky.js](test/funky.js) test file.

### immutable

_`mixly.immutable(object[, object[, ...object]])`_

Alias: _`mixly/immutable`_

Creates immutable (shallow) copy of the provided objects. Similar to [`append`](#append), but doesn't modify any of the provided objects.

```javascript
var immutable = require('mixly/immutable');

var o1 = { O1: true, commonThing: 'o1' }
  , o2 = { O2: true, commonThing: 'o2' }
  , o3 = { O3: true, commonThing: 'o3' }
  ;

// oX + o1 + o2 + o3
var oX = immutable(o1, o2, o3);

assert.notStrictEqual(oX, o1, 'first argument was not modified');

assert.strictEqual(oX.O1, true, 'kept properties from the first object');
assert.strictEqual(oX.O2, true, 'obtained properties from the second object');
assert.strictEqual(oX.O3, true, 'obtained properties from the third object');
assert.strictEqual(oX.commonThing, 'o3', 'last object in the list overrides shared properties');
```

More details in [test/immutable.js](test/immutable.js) test file.

### inherit

_`mixly.inherit(function, function)`_

Alias: _`mixly/inherit`_

Assigns prototype from the superclass to the target class, compatible with node's builtin version (`util.inherit`), but browser-friendly (without browserify magic, i.e. works with other packagers).

```javascript
var inherit = require('mixly/inherit');

function Child() { Parent.call(this); }
function Parent() {}

// Child -> Parent
inherit(Child, Parent);

// create instance
var child = new Child();

assert.strictEqual(child.constructor.super_, Parent, 'child has reference to the Parent constructor');
assert.strictEqual(Object.getPrototypeOf(Object.getPrototypeOf(child)), Parent.prototype, 'child has Parent in the prototype chain');
assert.strictEqual(child instanceof Child, true, 'child instance of Child');
assert.strictEqual(child instanceof Parent, true, 'child instance of Parent');
```

More details in [test/inherit.js](test/inherit.js) test file.

## License

Mixly is released under the [MIT](LICENSE) license.
