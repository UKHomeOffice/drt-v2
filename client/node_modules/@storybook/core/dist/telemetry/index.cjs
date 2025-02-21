"use strict";
var Is = Object.create;
var Z = Object.defineProperty;
var Cs = Object.getOwnPropertyDescriptor;
var Os = Object.getOwnPropertyNames;
var As = Object.getPrototypeOf, Rs = Object.prototype.hasOwnProperty;
var o = (e, t) => Z(e, "name", { value: t, configurable: !0 });
var p = (e, t) => () => (t || e((t = { exports: {} }).exports, t), t.exports), Gs = (e, t) => {
  for (var r in t)
    Z(e, r, { get: t[r], enumerable: !0 });
}, Tr = (e, t, r, n) => {
  if (t && typeof t == "object" || typeof t == "function")
    for (let i of Os(t))
      !Rs.call(e, i) && i !== r && Z(e, i, { get: () => t[i], enumerable: !(n = Cs(t, i)) || n.enumerable });
  return e;
};
var b = (e, t, r) => (r = e != null ? Is(As(e)) : {}, Tr(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  t || !e || !e.__esModule ? Z(r, "default", { value: e, enumerable: !0 }) : r,
  e
)), _s = (e) => Tr(Z({}, "__esModule", { value: !0 }), e);

// ../node_modules/picocolors/picocolors.js
var Cr = p((Cl, lt) => {
  var kr = process.argv || [], ge = process.env, js = !("NO_COLOR" in ge || kr.includes("--no-color")) && ("FORCE_COLOR" in ge || kr.includes(
  "--color") || process.platform === "win32" || require != null && require("tty").isatty(1) && ge.TERM !== "dumb" || "CI" in ge), Ns = /* @__PURE__ */ o(
  (e, t, r = e) => (n) => {
    let i = "" + n, s = i.indexOf(t, e.length);
    return ~s ? e + Bs(i, t, r, s) + t : e + i + t;
  }, "formatter"), Bs = /* @__PURE__ */ o((e, t, r, n) => {
    let i = "", s = 0;
    do
      i += e.substring(s, n) + r, s = n + t.length, n = e.indexOf(t, s);
    while (~n);
    return i + e.substring(s);
  }, "replaceClose"), Ir = /* @__PURE__ */ o((e = js) => {
    let t = e ? Ns : () => String;
    return {
      isColorSupported: e,
      reset: t("\x1B[0m", "\x1B[0m"),
      bold: t("\x1B[1m", "\x1B[22m", "\x1B[22m\x1B[1m"),
      dim: t("\x1B[2m", "\x1B[22m", "\x1B[22m\x1B[2m"),
      italic: t("\x1B[3m", "\x1B[23m"),
      underline: t("\x1B[4m", "\x1B[24m"),
      inverse: t("\x1B[7m", "\x1B[27m"),
      hidden: t("\x1B[8m", "\x1B[28m"),
      strikethrough: t("\x1B[9m", "\x1B[29m"),
      black: t("\x1B[30m", "\x1B[39m"),
      red: t("\x1B[31m", "\x1B[39m"),
      green: t("\x1B[32m", "\x1B[39m"),
      yellow: t("\x1B[33m", "\x1B[39m"),
      blue: t("\x1B[34m", "\x1B[39m"),
      magenta: t("\x1B[35m", "\x1B[39m"),
      cyan: t("\x1B[36m", "\x1B[39m"),
      white: t("\x1B[37m", "\x1B[39m"),
      gray: t("\x1B[90m", "\x1B[39m"),
      bgBlack: t("\x1B[40m", "\x1B[49m"),
      bgRed: t("\x1B[41m", "\x1B[49m"),
      bgGreen: t("\x1B[42m", "\x1B[49m"),
      bgYellow: t("\x1B[43m", "\x1B[49m"),
      bgBlue: t("\x1B[44m", "\x1B[49m"),
      bgMagenta: t("\x1B[45m", "\x1B[49m"),
      bgCyan: t("\x1B[46m", "\x1B[49m"),
      bgWhite: t("\x1B[47m", "\x1B[49m"),
      blackBright: t("\x1B[90m", "\x1B[39m"),
      redBright: t("\x1B[91m", "\x1B[39m"),
      greenBright: t("\x1B[92m", "\x1B[39m"),
      yellowBright: t("\x1B[93m", "\x1B[39m"),
      blueBright: t("\x1B[94m", "\x1B[39m"),
      magentaBright: t("\x1B[95m", "\x1B[39m"),
      cyanBright: t("\x1B[96m", "\x1B[39m"),
      whiteBright: t("\x1B[97m", "\x1B[39m"),
      bgBlackBright: t("\x1B[100m", "\x1B[49m"),
      bgRedBright: t("\x1B[101m", "\x1B[49m"),
      bgGreenBright: t("\x1B[102m", "\x1B[49m"),
      bgYellowBright: t("\x1B[103m", "\x1B[49m"),
      bgBlueBright: t("\x1B[104m", "\x1B[49m"),
      bgMagentaBright: t("\x1B[105m", "\x1B[49m"),
      bgCyanBright: t("\x1B[106m", "\x1B[49m"),
      bgWhiteBright: t("\x1B[107m", "\x1B[49m")
    };
  }, "createColors");
  lt.exports = Ir();
  lt.exports.createColors = Ir;
});

// ../node_modules/isexe/windows.js
var Dr = p((jl, Br) => {
  Br.exports = Nr;
  Nr.sync = Ms;
  var _r = require("fs");
  function Ds(e, t) {
    var r = t.pathExt !== void 0 ? t.pathExt : process.env.PATHEXT;
    if (!r || (r = r.split(";"), r.indexOf("") !== -1))
      return !0;
    for (var n = 0; n < r.length; n++) {
      var i = r[n].toLowerCase();
      if (i && e.substr(-i.length).toLowerCase() === i)
        return !0;
    }
    return !1;
  }
  o(Ds, "checkPathExt");
  function jr(e, t, r) {
    return !e.isSymbolicLink() && !e.isFile() ? !1 : Ds(t, r);
  }
  o(jr, "checkStat");
  function Nr(e, t, r) {
    _r.stat(e, function(n, i) {
      r(n, n ? !1 : jr(i, e, t));
    });
  }
  o(Nr, "isexe");
  function Ms(e, t) {
    return jr(_r.statSync(e), e, t);
  }
  o(Ms, "sync");
});

// ../node_modules/isexe/mode.js
var $r = p((Bl, Ur) => {
  Ur.exports = Lr;
  Lr.sync = Ls;
  var Mr = require("fs");
  function Lr(e, t, r) {
    Mr.stat(e, function(n, i) {
      r(n, n ? !1 : Fr(i, t));
    });
  }
  o(Lr, "isexe");
  function Ls(e, t) {
    return Fr(Mr.statSync(e), t);
  }
  o(Ls, "sync");
  function Fr(e, t) {
    return e.isFile() && Fs(e, t);
  }
  o(Fr, "checkStat");
  function Fs(e, t) {
    var r = e.mode, n = e.uid, i = e.gid, s = t.uid !== void 0 ? t.uid : process.getuid && process.getuid(), a = t.gid !== void 0 ? t.gid : process.
    getgid && process.getgid(), c = parseInt("100", 8), u = parseInt("010", 8), l = parseInt("001", 8), f = c | u, x = r & l || r & u && i ===
    a || r & c && n === s || r & f && s === 0;
    return x;
  }
  o(Fs, "checkMode");
});

// ../node_modules/isexe/index.js
var Wr = p((Ll, qr) => {
  var Ml = require("fs"), Se;
  process.platform === "win32" || global.TESTING_WINDOWS ? Se = Dr() : Se = $r();
  qr.exports = dt;
  dt.sync = Us;
  function dt(e, t, r) {
    if (typeof t == "function" && (r = t, t = {}), !r) {
      if (typeof Promise != "function")
        throw new TypeError("callback not provided");
      return new Promise(function(n, i) {
        dt(e, t || {}, function(s, a) {
          s ? i(s) : n(a);
        });
      });
    }
    Se(e, t || {}, function(n, i) {
      n && (n.code === "EACCES" || t && t.ignoreErrors) && (n = null, i = !1), r(n, i);
    });
  }
  o(dt, "isexe");
  function Us(e, t) {
    try {
      return Se.sync(e, t || {});
    } catch (r) {
      if (t && t.ignoreErrors || r.code === "EACCES")
        return !1;
      throw r;
    }
  }
  o(Us, "sync");
});

// ../node_modules/cross-spawn/node_modules/which/which.js
var Yr = p((Ul, Xr) => {
  var F = process.platform === "win32" || process.env.OSTYPE === "cygwin" || process.env.OSTYPE === "msys", Hr = require("path"), $s = F ? "\
;" : ":", Vr = Wr(), zr = /* @__PURE__ */ o((e) => Object.assign(new Error(`not found: ${e}`), { code: "ENOENT" }), "getNotFoundError"), Jr = /* @__PURE__ */ o(
  (e, t) => {
    let r = t.colon || $s, n = e.match(/\//) || F && e.match(/\\/) ? [""] : [
      // windows always checks the cwd first
      ...F ? [process.cwd()] : [],
      ...(t.path || process.env.PATH || /* istanbul ignore next: very unusual */
      "").split(r)
    ], i = F ? t.pathExt || process.env.PATHEXT || ".EXE;.CMD;.BAT;.COM" : "", s = F ? i.split(r) : [""];
    return F && e.indexOf(".") !== -1 && s[0] !== "" && s.unshift(""), {
      pathEnv: n,
      pathExt: s,
      pathExtExe: i
    };
  }, "getPathInfo"), Kr = /* @__PURE__ */ o((e, t, r) => {
    typeof t == "function" && (r = t, t = {}), t || (t = {});
    let { pathEnv: n, pathExt: i, pathExtExe: s } = Jr(e, t), a = [], c = /* @__PURE__ */ o((l) => new Promise((f, x) => {
      if (l === n.length)
        return t.all && a.length ? f(a) : x(zr(e));
      let g = n[l], m = /^".*"$/.test(g) ? g.slice(1, -1) : g, y = Hr.join(m, e), h = !m && /^\.[\\\/]/.test(e) ? e.slice(0, 2) + y : y;
      f(u(h, l, 0));
    }), "step"), u = /* @__PURE__ */ o((l, f, x) => new Promise((g, m) => {
      if (x === i.length)
        return g(c(f + 1));
      let y = i[x];
      Vr(l + y, { pathExt: s }, (h, S) => {
        if (!h && S)
          if (t.all)
            a.push(l + y);
          else
            return g(l + y);
        return g(u(l, f, x + 1));
      });
    }), "subStep");
    return r ? c(0).then((l) => r(null, l), r) : c(0);
  }, "which"), qs = /* @__PURE__ */ o((e, t) => {
    t = t || {};
    let { pathEnv: r, pathExt: n, pathExtExe: i } = Jr(e, t), s = [];
    for (let a = 0; a < r.length; a++) {
      let c = r[a], u = /^".*"$/.test(c) ? c.slice(1, -1) : c, l = Hr.join(u, e), f = !u && /^\.[\\\/]/.test(e) ? e.slice(0, 2) + l : l;
      for (let x = 0; x < n.length; x++) {
        let g = f + n[x];
        try {
          if (Vr.sync(g, { pathExt: i }))
            if (t.all)
              s.push(g);
            else
              return g;
        } catch {
        }
      }
    }
    if (t.all && s.length)
      return s;
    if (t.nothrow)
      return null;
    throw zr(e);
  }, "whichSync");
  Xr.exports = Kr;
  Kr.sync = qs;
});

// ../node_modules/path-key/index.js
var ht = p((ql, mt) => {
  "use strict";
  var Zr = /* @__PURE__ */ o((e = {}) => {
    let t = e.env || process.env;
    return (e.platform || process.platform) !== "win32" ? "PATH" : Object.keys(t).reverse().find((n) => n.toUpperCase() === "PATH") || "Path";
  }, "pathKey");
  mt.exports = Zr;
  mt.exports.default = Zr;
});

// ../node_modules/cross-spawn/lib/util/resolveCommand.js
var rn = p((Hl, tn) => {
  "use strict";
  var Qr = require("path"), Ws = Yr(), Hs = ht();
  function en(e, t) {
    let r = e.options.env || process.env, n = process.cwd(), i = e.options.cwd != null, s = i && process.chdir !== void 0 && !process.chdir.
    disabled;
    if (s)
      try {
        process.chdir(e.options.cwd);
      } catch {
      }
    let a;
    try {
      a = Ws.sync(e.command, {
        path: r[Hs({ env: r })],
        pathExt: t ? Qr.delimiter : void 0
      });
    } catch {
    } finally {
      s && process.chdir(n);
    }
    return a && (a = Qr.resolve(i ? e.options.cwd : "", a)), a;
  }
  o(en, "resolveCommandAttempt");
  function Vs(e) {
    return en(e) || en(e, !0);
  }
  o(Vs, "resolveCommand");
  tn.exports = Vs;
});

// ../node_modules/cross-spawn/lib/util/escape.js
var nn = p((zl, gt) => {
  "use strict";
  var yt = /([()\][%!^"`<>&|;, *?])/g;
  function zs(e) {
    return e = e.replace(yt, "^$1"), e;
  }
  o(zs, "escapeCommand");
  function Js(e, t) {
    return e = `${e}`, e = e.replace(/(\\*)"/g, '$1$1\\"'), e = e.replace(/(\\*)$/, "$1$1"), e = `"${e}"`, e = e.replace(yt, "^$1"), t && (e =
    e.replace(yt, "^$1")), e;
  }
  o(Js, "escapeArgument");
  gt.exports.command = zs;
  gt.exports.argument = Js;
});

// ../node_modules/shebang-regex/index.js
var sn = p((Kl, on) => {
  "use strict";
  on.exports = /^#!(.*)/;
});

// ../node_modules/shebang-command/index.js
var cn = p((Xl, an) => {
  "use strict";
  var Ks = sn();
  an.exports = (e = "") => {
    let t = e.match(Ks);
    if (!t)
      return null;
    let [r, n] = t[0].replace(/#! ?/, "").split(" "), i = r.split("/").pop();
    return i === "env" ? n : n ? `${i} ${n}` : i;
  };
});

// ../node_modules/cross-spawn/lib/util/readShebang.js
var ln = p((Yl, un) => {
  "use strict";
  var bt = require("fs"), Xs = cn();
  function Ys(e) {
    let r = Buffer.alloc(150), n;
    try {
      n = bt.openSync(e, "r"), bt.readSync(n, r, 0, 150, 0), bt.closeSync(n);
    } catch {
    }
    return Xs(r.toString());
  }
  o(Ys, "readShebang");
  un.exports = Ys;
});

// ../node_modules/cross-spawn/lib/parse.js
var mn = p((Ql, dn) => {
  "use strict";
  var Zs = require("path"), fn = rn(), pn = nn(), Qs = ln(), ea = process.platform === "win32", ta = /\.(?:com|exe)$/i, ra = /node_modules[\\/].bin[\\/][^\\/]+\.cmd$/i;
  function na(e) {
    e.file = fn(e);
    let t = e.file && Qs(e.file);
    return t ? (e.args.unshift(e.file), e.command = t, fn(e)) : e.file;
  }
  o(na, "detectShebang");
  function oa(e) {
    if (!ea)
      return e;
    let t = na(e), r = !ta.test(t);
    if (e.options.forceShell || r) {
      let n = ra.test(t);
      e.command = Zs.normalize(e.command), e.command = pn.command(e.command), e.args = e.args.map((s) => pn.argument(s, n));
      let i = [e.command].concat(e.args).join(" ");
      e.args = ["/d", "/s", "/c", `"${i}"`], e.command = process.env.comspec || "cmd.exe", e.options.windowsVerbatimArguments = !0;
    }
    return e;
  }
  o(oa, "parseNonShell");
  function ia(e, t, r) {
    t && !Array.isArray(t) && (r = t, t = null), t = t ? t.slice(0) : [], r = Object.assign({}, r);
    let n = {
      command: e,
      args: t,
      options: r,
      file: void 0,
      original: {
        command: e,
        args: t
      }
    };
    return r.shell ? n : oa(n);
  }
  o(ia, "parse");
  dn.exports = ia;
});

// ../node_modules/cross-spawn/lib/enoent.js
var gn = p((tf, yn) => {
  "use strict";
  var xt = process.platform === "win32";
  function St(e, t) {
    return Object.assign(new Error(`${t} ${e.command} ENOENT`), {
      code: "ENOENT",
      errno: "ENOENT",
      syscall: `${t} ${e.command}`,
      path: e.command,
      spawnargs: e.args
    });
  }
  o(St, "notFoundError");
  function sa(e, t) {
    if (!xt)
      return;
    let r = e.emit;
    e.emit = function(n, i) {
      if (n === "exit") {
        let s = hn(i, t, "spawn");
        if (s)
          return r.call(e, "error", s);
      }
      return r.apply(e, arguments);
    };
  }
  o(sa, "hookChildProcess");
  function hn(e, t) {
    return xt && e === 1 && !t.file ? St(t.original, "spawn") : null;
  }
  o(hn, "verifyENOENT");
  function aa(e, t) {
    return xt && e === 1 && !t.file ? St(t.original, "spawnSync") : null;
  }
  o(aa, "verifyENOENTSync");
  yn.exports = {
    hookChildProcess: sa,
    verifyENOENT: hn,
    verifyENOENTSync: aa,
    notFoundError: St
  };
});

// ../node_modules/cross-spawn/index.js
var Pt = p((nf, U) => {
  "use strict";
  var bn = require("child_process"), wt = mn(), vt = gn();
  function xn(e, t, r) {
    let n = wt(e, t, r), i = bn.spawn(n.command, n.args, n.options);
    return vt.hookChildProcess(i, n), i;
  }
  o(xn, "spawn");
  function ca(e, t, r) {
    let n = wt(e, t, r), i = bn.spawnSync(n.command, n.args, n.options);
    return i.error = i.error || vt.verifyENOENTSync(i.status, n), i;
  }
  o(ca, "spawnSync");
  U.exports = xn;
  U.exports.spawn = xn;
  U.exports.sync = ca;
  U.exports._parse = wt;
  U.exports._enoent = vt;
});

// ../node_modules/execa/node_modules/strip-final-newline/index.js
var wn = p((sf, Sn) => {
  "use strict";
  Sn.exports = (e) => {
    let t = typeof e == "string" ? `
` : 10, r = typeof e == "string" ? "\r" : 13;
    return e[e.length - 1] === t && (e = e.slice(0, e.length - 1)), e[e.length - 1] === r && (e = e.slice(0, e.length - 1)), e;
  };
});

// ../node_modules/npm-run-path/index.js
var En = p((af, te) => {
  "use strict";
  var ee = require("path"), vn = ht(), Pn = /* @__PURE__ */ o((e) => {
    e = {
      cwd: process.cwd(),
      path: process.env[vn()],
      execPath: process.execPath,
      ...e
    };
    let t, r = ee.resolve(e.cwd), n = [];
    for (; t !== r; )
      n.push(ee.join(r, "node_modules/.bin")), t = r, r = ee.resolve(r, "..");
    let i = ee.resolve(e.cwd, e.execPath, "..");
    return n.push(i), n.concat(e.path).join(ee.delimiter);
  }, "npmRunPath");
  te.exports = Pn;
  te.exports.default = Pn;
  te.exports.env = (e) => {
    e = {
      env: process.env,
      ...e
    };
    let t = { ...e.env }, r = vn({ env: t });
    return e.path = t[r], t[r] = te.exports(e), t;
  };
});

// ../node_modules/mimic-fn/index.js
var kn = p((uf, Et) => {
  "use strict";
  var Tn = /* @__PURE__ */ o((e, t) => {
    for (let r of Reflect.ownKeys(t))
      Object.defineProperty(e, r, Object.getOwnPropertyDescriptor(t, r));
    return e;
  }, "mimicFn");
  Et.exports = Tn;
  Et.exports.default = Tn;
});

// ../node_modules/onetime/index.js
var Cn = p((ff, ve) => {
  "use strict";
  var ua = kn(), we = /* @__PURE__ */ new WeakMap(), In = /* @__PURE__ */ o((e, t = {}) => {
    if (typeof e != "function")
      throw new TypeError("Expected a function");
    let r, n = 0, i = e.displayName || e.name || "<anonymous>", s = /* @__PURE__ */ o(function(...a) {
      if (we.set(s, ++n), n === 1)
        r = e.apply(this, a), e = null;
      else if (t.throw === !0)
        throw new Error(`Function \`${i}\` can only be called once`);
      return r;
    }, "onetime");
    return ua(s, e), we.set(s, n), s;
  }, "onetime");
  ve.exports = In;
  ve.exports.default = In;
  ve.exports.callCount = (e) => {
    if (!we.has(e))
      throw new Error(`The given function \`${e.name}\` is not wrapped by the \`onetime\` package`);
    return we.get(e);
  };
});

// ../node_modules/execa/node_modules/human-signals/build/src/core.js
var On = p((Pe) => {
  "use strict";
  Object.defineProperty(Pe, "__esModule", { value: !0 });
  Pe.SIGNALS = void 0;
  var la = [
    {
      name: "SIGHUP",
      number: 1,
      action: "terminate",
      description: "Terminal closed",
      standard: "posix"
    },
    {
      name: "SIGINT",
      number: 2,
      action: "terminate",
      description: "User interruption with CTRL-C",
      standard: "ansi"
    },
    {
      name: "SIGQUIT",
      number: 3,
      action: "core",
      description: "User interruption with CTRL-\\",
      standard: "posix"
    },
    {
      name: "SIGILL",
      number: 4,
      action: "core",
      description: "Invalid machine instruction",
      standard: "ansi"
    },
    {
      name: "SIGTRAP",
      number: 5,
      action: "core",
      description: "Debugger breakpoint",
      standard: "posix"
    },
    {
      name: "SIGABRT",
      number: 6,
      action: "core",
      description: "Aborted",
      standard: "ansi"
    },
    {
      name: "SIGIOT",
      number: 6,
      action: "core",
      description: "Aborted",
      standard: "bsd"
    },
    {
      name: "SIGBUS",
      number: 7,
      action: "core",
      description: "Bus error due to misaligned, non-existing address or paging error",
      standard: "bsd"
    },
    {
      name: "SIGEMT",
      number: 7,
      action: "terminate",
      description: "Command should be emulated but is not implemented",
      standard: "other"
    },
    {
      name: "SIGFPE",
      number: 8,
      action: "core",
      description: "Floating point arithmetic error",
      standard: "ansi"
    },
    {
      name: "SIGKILL",
      number: 9,
      action: "terminate",
      description: "Forced termination",
      standard: "posix",
      forced: !0
    },
    {
      name: "SIGUSR1",
      number: 10,
      action: "terminate",
      description: "Application-specific signal",
      standard: "posix"
    },
    {
      name: "SIGSEGV",
      number: 11,
      action: "core",
      description: "Segmentation fault",
      standard: "ansi"
    },
    {
      name: "SIGUSR2",
      number: 12,
      action: "terminate",
      description: "Application-specific signal",
      standard: "posix"
    },
    {
      name: "SIGPIPE",
      number: 13,
      action: "terminate",
      description: "Broken pipe or socket",
      standard: "posix"
    },
    {
      name: "SIGALRM",
      number: 14,
      action: "terminate",
      description: "Timeout or timer",
      standard: "posix"
    },
    {
      name: "SIGTERM",
      number: 15,
      action: "terminate",
      description: "Termination",
      standard: "ansi"
    },
    {
      name: "SIGSTKFLT",
      number: 16,
      action: "terminate",
      description: "Stack is empty or overflowed",
      standard: "other"
    },
    {
      name: "SIGCHLD",
      number: 17,
      action: "ignore",
      description: "Child process terminated, paused or unpaused",
      standard: "posix"
    },
    {
      name: "SIGCLD",
      number: 17,
      action: "ignore",
      description: "Child process terminated, paused or unpaused",
      standard: "other"
    },
    {
      name: "SIGCONT",
      number: 18,
      action: "unpause",
      description: "Unpaused",
      standard: "posix",
      forced: !0
    },
    {
      name: "SIGSTOP",
      number: 19,
      action: "pause",
      description: "Paused",
      standard: "posix",
      forced: !0
    },
    {
      name: "SIGTSTP",
      number: 20,
      action: "pause",
      description: 'Paused using CTRL-Z or "suspend"',
      standard: "posix"
    },
    {
      name: "SIGTTIN",
      number: 21,
      action: "pause",
      description: "Background process cannot read terminal input",
      standard: "posix"
    },
    {
      name: "SIGBREAK",
      number: 21,
      action: "terminate",
      description: "User interruption with CTRL-BREAK",
      standard: "other"
    },
    {
      name: "SIGTTOU",
      number: 22,
      action: "pause",
      description: "Background process cannot write to terminal output",
      standard: "posix"
    },
    {
      name: "SIGURG",
      number: 23,
      action: "ignore",
      description: "Socket received out-of-band data",
      standard: "bsd"
    },
    {
      name: "SIGXCPU",
      number: 24,
      action: "core",
      description: "Process timed out",
      standard: "bsd"
    },
    {
      name: "SIGXFSZ",
      number: 25,
      action: "core",
      description: "File too big",
      standard: "bsd"
    },
    {
      name: "SIGVTALRM",
      number: 26,
      action: "terminate",
      description: "Timeout or timer",
      standard: "bsd"
    },
    {
      name: "SIGPROF",
      number: 27,
      action: "terminate",
      description: "Timeout or timer",
      standard: "bsd"
    },
    {
      name: "SIGWINCH",
      number: 28,
      action: "ignore",
      description: "Terminal window size changed",
      standard: "bsd"
    },
    {
      name: "SIGIO",
      number: 29,
      action: "terminate",
      description: "I/O is available",
      standard: "other"
    },
    {
      name: "SIGPOLL",
      number: 29,
      action: "terminate",
      description: "Watched event",
      standard: "other"
    },
    {
      name: "SIGINFO",
      number: 29,
      action: "ignore",
      description: "Request for process information",
      standard: "other"
    },
    {
      name: "SIGPWR",
      number: 30,
      action: "terminate",
      description: "Device running out of power",
      standard: "systemv"
    },
    {
      name: "SIGSYS",
      number: 31,
      action: "core",
      description: "Invalid system call",
      standard: "other"
    },
    {
      name: "SIGUNUSED",
      number: 31,
      action: "terminate",
      description: "Invalid system call",
      standard: "other"
    }
  ];
  Pe.SIGNALS = la;
});

// ../node_modules/execa/node_modules/human-signals/build/src/realtime.js
var Tt = p(($) => {
  "use strict";
  Object.defineProperty($, "__esModule", { value: !0 });
  $.SIGRTMAX = $.getRealtimeSignals = void 0;
  var fa = /* @__PURE__ */ o(function() {
    let e = Rn - An + 1;
    return Array.from({ length: e }, pa);
  }, "getRealtimeSignals");
  $.getRealtimeSignals = fa;
  var pa = /* @__PURE__ */ o(function(e, t) {
    return {
      name: `SIGRT${t + 1}`,
      number: An + t,
      action: "terminate",
      description: "Application-specific signal (realtime)",
      standard: "posix"
    };
  }, "getRealtimeSignal"), An = 34, Rn = 64;
  $.SIGRTMAX = Rn;
});

// ../node_modules/execa/node_modules/human-signals/build/src/signals.js
var Gn = p((Ee) => {
  "use strict";
  Object.defineProperty(Ee, "__esModule", { value: !0 });
  Ee.getSignals = void 0;
  var da = require("os"), ma = On(), ha = Tt(), ya = /* @__PURE__ */ o(function() {
    let e = (0, ha.getRealtimeSignals)();
    return [...ma.SIGNALS, ...e].map(ga);
  }, "getSignals");
  Ee.getSignals = ya;
  var ga = /* @__PURE__ */ o(function({
    name: e,
    number: t,
    description: r,
    action: n,
    forced: i = !1,
    standard: s
  }) {
    let {
      signals: { [e]: a }
    } = da.constants, c = a !== void 0;
    return { name: e, number: c ? a : t, description: r, supported: c, action: n, forced: i, standard: s };
  }, "normalizeSignal");
});

// ../node_modules/execa/node_modules/human-signals/build/src/main.js
var jn = p((q) => {
  "use strict";
  Object.defineProperty(q, "__esModule", { value: !0 });
  q.signalsByNumber = q.signalsByName = void 0;
  var ba = require("os"), _n = Gn(), xa = Tt(), Sa = /* @__PURE__ */ o(function() {
    return (0, _n.getSignals)().reduce(wa, {});
  }, "getSignalsByName"), wa = /* @__PURE__ */ o(function(e, { name: t, number: r, description: n, supported: i, action: s, forced: a, standard: c }) {
    return {
      ...e,
      [t]: { name: t, number: r, description: n, supported: i, action: s, forced: a, standard: c }
    };
  }, "getSignalByName"), va = Sa();
  q.signalsByName = va;
  var Pa = /* @__PURE__ */ o(function() {
    let e = (0, _n.getSignals)(), t = xa.SIGRTMAX + 1, r = Array.from({ length: t }, (n, i) => Ea(i, e));
    return Object.assign({}, ...r);
  }, "getSignalsByNumber"), Ea = /* @__PURE__ */ o(function(e, t) {
    let r = Ta(e, t);
    if (r === void 0)
      return {};
    let { name: n, description: i, supported: s, action: a, forced: c, standard: u } = r;
    return {
      [e]: {
        name: n,
        number: e,
        description: i,
        supported: s,
        action: a,
        forced: c,
        standard: u
      }
    };
  }, "getSignalByNumber"), Ta = /* @__PURE__ */ o(function(e, t) {
    let r = t.find(({ name: n }) => ba.constants.signals[n] === e);
    return r !== void 0 ? r : t.find((n) => n.number === e);
  }, "findSignalByNumber"), ka = Pa();
  q.signalsByNumber = ka;
});

// ../node_modules/execa/lib/error.js
var Bn = p((Sf, Nn) => {
  "use strict";
  var { signalsByName: Ia } = jn(), Ca = /* @__PURE__ */ o(({ timedOut: e, timeout: t, errorCode: r, signal: n, signalDescription: i, exitCode: s,
  isCanceled: a }) => e ? `timed out after ${t} milliseconds` : a ? "was canceled" : r !== void 0 ? `failed with ${r}` : n !== void 0 ? `was\
 killed with ${n} (${i})` : s !== void 0 ? `failed with exit code ${s}` : "failed", "getErrorPrefix"), Oa = /* @__PURE__ */ o(({
    stdout: e,
    stderr: t,
    all: r,
    error: n,
    signal: i,
    exitCode: s,
    command: a,
    escapedCommand: c,
    timedOut: u,
    isCanceled: l,
    killed: f,
    parsed: { options: { timeout: x } }
  }) => {
    s = s === null ? void 0 : s, i = i === null ? void 0 : i;
    let g = i === void 0 ? void 0 : Ia[i].description, m = n && n.code, h = `Command ${Ca({ timedOut: u, timeout: x, errorCode: m, signal: i,
    signalDescription: g, exitCode: s, isCanceled: l })}: ${a}`, S = Object.prototype.toString.call(n) === "[object Error]", k = S ? `${h}
${n.message}` : h, E = [k, t, e].filter(Boolean).join(`
`);
    return S ? (n.originalMessage = n.message, n.message = E) : n = new Error(E), n.shortMessage = k, n.command = a, n.escapedCommand = c, n.
    exitCode = s, n.signal = i, n.signalDescription = g, n.stdout = e, n.stderr = t, r !== void 0 && (n.all = r), "bufferedData" in n && delete n.
    bufferedData, n.failed = !0, n.timedOut = !!u, n.isCanceled = l, n.killed = f && !u, n;
  }, "makeError");
  Nn.exports = Oa;
});

// ../node_modules/execa/lib/stdio.js
var Mn = p((vf, kt) => {
  "use strict";
  var Te = ["stdin", "stdout", "stderr"], Aa = /* @__PURE__ */ o((e) => Te.some((t) => e[t] !== void 0), "hasAlias"), Dn = /* @__PURE__ */ o(
  (e) => {
    if (!e)
      return;
    let { stdio: t } = e;
    if (t === void 0)
      return Te.map((n) => e[n]);
    if (Aa(e))
      throw new Error(`It's not possible to provide \`stdio\` in combination with one of ${Te.map((n) => `\`${n}\``).join(", ")}`);
    if (typeof t == "string")
      return t;
    if (!Array.isArray(t))
      throw new TypeError(`Expected \`stdio\` to be of type \`string\` or \`Array\`, got \`${typeof t}\``);
    let r = Math.max(t.length, Te.length);
    return Array.from({ length: r }, (n, i) => t[i]);
  }, "normalizeStdio");
  kt.exports = Dn;
  kt.exports.node = (e) => {
    let t = Dn(e);
    return t === "ipc" ? "ipc" : t === void 0 || typeof t == "string" ? [t, t, t, "ipc"] : t.includes("ipc") ? t : [...t, "ipc"];
  };
});

// ../node_modules/signal-exit/signals.js
var Ln = p((Ef, ke) => {
  ke.exports = [
    "SIGABRT",
    "SIGALRM",
    "SIGHUP",
    "SIGINT",
    "SIGTERM"
  ];
  process.platform !== "win32" && ke.exports.push(
    "SIGVTALRM",
    "SIGXCPU",
    "SIGXFSZ",
    "SIGUSR2",
    "SIGTRAP",
    "SIGSYS",
    "SIGQUIT",
    "SIGIOT"
    // should detect profiler and enable/disable accordingly.
    // see #21
    // 'SIGPROF'
  );
  process.platform === "linux" && ke.exports.push(
    "SIGIO",
    "SIGPOLL",
    "SIGPWR",
    "SIGSTKFLT",
    "SIGUNUSED"
  );
});

// ../node_modules/signal-exit/index.js
var Wn = p((Tf, V) => {
  var w = global.process, j = /* @__PURE__ */ o(function(e) {
    return e && typeof e == "object" && typeof e.removeListener == "function" && typeof e.emit == "function" && typeof e.reallyExit == "func\
tion" && typeof e.listeners == "function" && typeof e.kill == "function" && typeof e.pid == "number" && typeof e.on == "function";
  }, "processOk");
  j(w) ? (Fn = require("assert"), W = Ln(), Un = /^win/i.test(w.platform), re = require("events"), typeof re != "function" && (re = re.EventEmitter),
  w.__signal_exit_emitter__ ? P = w.__signal_exit_emitter__ : (P = w.__signal_exit_emitter__ = new re(), P.count = 0, P.emitted = {}), P.infinite ||
  (P.setMaxListeners(1 / 0), P.infinite = !0), V.exports = function(e, t) {
    if (!j(global.process))
      return function() {
      };
    Fn.equal(typeof e, "function", "a callback must be provided for exit handler"), H === !1 && It();
    var r = "exit";
    t && t.alwaysLast && (r = "afterexit");
    var n = /* @__PURE__ */ o(function() {
      P.removeListener(r, e), P.listeners("exit").length === 0 && P.listeners("afterexit").length === 0 && Ie();
    }, "remove");
    return P.on(r, e), n;
  }, Ie = /* @__PURE__ */ o(function() {
    !H || !j(global.process) || (H = !1, W.forEach(function(t) {
      try {
        w.removeListener(t, Ce[t]);
      } catch {
      }
    }), w.emit = Oe, w.reallyExit = Ct, P.count -= 1);
  }, "unload"), V.exports.unload = Ie, N = /* @__PURE__ */ o(function(t, r, n) {
    P.emitted[t] || (P.emitted[t] = !0, P.emit(t, r, n));
  }, "emit"), Ce = {}, W.forEach(function(e) {
    Ce[e] = /* @__PURE__ */ o(function() {
      if (j(global.process)) {
        var r = w.listeners(e);
        r.length === P.count && (Ie(), N("exit", null, e), N("afterexit", null, e), Un && e === "SIGHUP" && (e = "SIGINT"), w.kill(w.pid, e));
      }
    }, "listener");
  }), V.exports.signals = function() {
    return W;
  }, H = !1, It = /* @__PURE__ */ o(function() {
    H || !j(global.process) || (H = !0, P.count += 1, W = W.filter(function(t) {
      try {
        return w.on(t, Ce[t]), !0;
      } catch {
        return !1;
      }
    }), w.emit = qn, w.reallyExit = $n);
  }, "load"), V.exports.load = It, Ct = w.reallyExit, $n = /* @__PURE__ */ o(function(t) {
    j(global.process) && (w.exitCode = t || /* istanbul ignore next */
    0, N("exit", w.exitCode, null), N("afterexit", w.exitCode, null), Ct.call(w, w.exitCode));
  }, "processReallyExit"), Oe = w.emit, qn = /* @__PURE__ */ o(function(t, r) {
    if (t === "exit" && j(global.process)) {
      r !== void 0 && (w.exitCode = r);
      var n = Oe.apply(this, arguments);
      return N("exit", w.exitCode, null), N("afterexit", w.exitCode, null), n;
    } else
      return Oe.apply(this, arguments);
  }, "processEmit")) : V.exports = function() {
    return function() {
    };
  };
  var Fn, W, Un, re, P, Ie, N, Ce, H, It, Ct, $n, Oe, qn;
});

// ../node_modules/execa/lib/kill.js
var Vn = p((If, Hn) => {
  "use strict";
  var Ra = require("os"), Ga = Wn(), _a = 1e3 * 5, ja = /* @__PURE__ */ o((e, t = "SIGTERM", r = {}) => {
    let n = e(t);
    return Na(e, t, r, n), n;
  }, "spawnedKill"), Na = /* @__PURE__ */ o((e, t, r, n) => {
    if (!Ba(t, r, n))
      return;
    let i = Ma(r), s = setTimeout(() => {
      e("SIGKILL");
    }, i);
    s.unref && s.unref();
  }, "setKillTimeout"), Ba = /* @__PURE__ */ o((e, { forceKillAfterTimeout: t }, r) => Da(e) && t !== !1 && r, "shouldForceKill"), Da = /* @__PURE__ */ o(
  (e) => e === Ra.constants.signals.SIGTERM || typeof e == "string" && e.toUpperCase() === "SIGTERM", "isSigterm"), Ma = /* @__PURE__ */ o(({
  forceKillAfterTimeout: e = !0 }) => {
    if (e === !0)
      return _a;
    if (!Number.isFinite(e) || e < 0)
      throw new TypeError(`Expected the \`forceKillAfterTimeout\` option to be a non-negative integer, got \`${e}\` (${typeof e})`);
    return e;
  }, "getForceKillAfterTimeout"), La = /* @__PURE__ */ o((e, t) => {
    e.kill() && (t.isCanceled = !0);
  }, "spawnedCancel"), Fa = /* @__PURE__ */ o((e, t, r) => {
    e.kill(t), r(Object.assign(new Error("Timed out"), { timedOut: !0, signal: t }));
  }, "timeoutKill"), Ua = /* @__PURE__ */ o((e, { timeout: t, killSignal: r = "SIGTERM" }, n) => {
    if (t === 0 || t === void 0)
      return n;
    let i, s = new Promise((c, u) => {
      i = setTimeout(() => {
        Fa(e, r, u);
      }, t);
    }), a = n.finally(() => {
      clearTimeout(i);
    });
    return Promise.race([s, a]);
  }, "setupTimeout"), $a = /* @__PURE__ */ o(({ timeout: e }) => {
    if (e !== void 0 && (!Number.isFinite(e) || e < 0))
      throw new TypeError(`Expected the \`timeout\` option to be a non-negative integer, got \`${e}\` (${typeof e})`);
  }, "validateTimeout"), qa = /* @__PURE__ */ o(async (e, { cleanup: t, detached: r }, n) => {
    if (!t || r)
      return n;
    let i = Ga(() => {
      e.kill();
    });
    return n.finally(() => {
      i();
    });
  }, "setExitHandler");
  Hn.exports = {
    spawnedKill: ja,
    spawnedCancel: La,
    setupTimeout: Ua,
    validateTimeout: $a,
    setExitHandler: qa
  };
});

// ../node_modules/is-stream/index.js
var Jn = p((Of, zn) => {
  "use strict";
  var C = /* @__PURE__ */ o((e) => e !== null && typeof e == "object" && typeof e.pipe == "function", "isStream");
  C.writable = (e) => C(e) && e.writable !== !1 && typeof e._write == "function" && typeof e._writableState == "object";
  C.readable = (e) => C(e) && e.readable !== !1 && typeof e._read == "function" && typeof e._readableState == "object";
  C.duplex = (e) => C.writable(e) && C.readable(e);
  C.transform = (e) => C.duplex(e) && typeof e._transform == "function";
  zn.exports = C;
});

// ../node_modules/get-stream/buffer-stream.js
var Xn = p((Rf, Kn) => {
  "use strict";
  var { PassThrough: Wa } = require("stream");
  Kn.exports = (e) => {
    e = { ...e };
    let { array: t } = e, { encoding: r } = e, n = r === "buffer", i = !1;
    t ? i = !(r || n) : r = r || "utf8", n && (r = null);
    let s = new Wa({ objectMode: i });
    r && s.setEncoding(r);
    let a = 0, c = [];
    return s.on("data", (u) => {
      c.push(u), i ? a = c.length : a += u.length;
    }), s.getBufferedValue = () => t ? c : n ? Buffer.concat(c, a) : c.join(""), s.getBufferedLength = () => a, s;
  };
});

// ../node_modules/get-stream/index.js
var Yn = p((Gf, ne) => {
  "use strict";
  var { constants: Ha } = require("buffer"), Va = require("stream"), { promisify: za } = require("util"), Ja = Xn(), Ka = za(Va.pipeline), Ae = class extends Error {
    static {
      o(this, "MaxBufferError");
    }
    constructor() {
      super("maxBuffer exceeded"), this.name = "MaxBufferError";
    }
  };
  async function Ot(e, t) {
    if (!e)
      throw new Error("Expected a stream");
    t = {
      maxBuffer: 1 / 0,
      ...t
    };
    let { maxBuffer: r } = t, n = Ja(t);
    return await new Promise((i, s) => {
      let a = /* @__PURE__ */ o((c) => {
        c && n.getBufferedLength() <= Ha.MAX_LENGTH && (c.bufferedData = n.getBufferedValue()), s(c);
      }, "rejectPromise");
      (async () => {
        try {
          await Ka(e, n), i();
        } catch (c) {
          a(c);
        }
      })(), n.on("data", () => {
        n.getBufferedLength() > r && a(new Ae());
      });
    }), n.getBufferedValue();
  }
  o(Ot, "getStream");
  ne.exports = Ot;
  ne.exports.buffer = (e, t) => Ot(e, { ...t, encoding: "buffer" });
  ne.exports.array = (e, t) => Ot(e, { ...t, array: !0 });
  ne.exports.MaxBufferError = Ae;
});

// ../node_modules/merge-stream/index.js
var At = p((jf, Zn) => {
  "use strict";
  var { PassThrough: Xa } = require("stream");
  Zn.exports = function() {
    var e = [], t = new Xa({ objectMode: !0 });
    return t.setMaxListeners(0), t.add = r, t.isEmpty = n, t.on("unpipe", i), Array.prototype.slice.call(arguments).forEach(r), t;
    function r(s) {
      return Array.isArray(s) ? (s.forEach(r), this) : (e.push(s), s.once("end", i.bind(null, s)), s.once("error", t.emit.bind(t, "error")),
      s.pipe(t, { end: !1 }), this);
    }
    o(r, "add");
    function n() {
      return e.length == 0;
    }
    o(n, "isEmpty");
    function i(s) {
      e = e.filter(function(a) {
        return a !== s;
      }), !e.length && t.readable && t.end();
    }
    o(i, "remove");
  };
});

// ../node_modules/execa/lib/stream.js
var ro = p((Bf, to) => {
  "use strict";
  var eo = Jn(), Qn = Yn(), Ya = At(), Za = /* @__PURE__ */ o((e, t) => {
    t === void 0 || e.stdin === void 0 || (eo(t) ? t.pipe(e.stdin) : e.stdin.end(t));
  }, "handleInput"), Qa = /* @__PURE__ */ o((e, { all: t }) => {
    if (!t || !e.stdout && !e.stderr)
      return;
    let r = Ya();
    return e.stdout && r.add(e.stdout), e.stderr && r.add(e.stderr), r;
  }, "makeAllStream"), Rt = /* @__PURE__ */ o(async (e, t) => {
    if (e) {
      e.destroy();
      try {
        return await t;
      } catch (r) {
        return r.bufferedData;
      }
    }
  }, "getBufferedData"), Gt = /* @__PURE__ */ o((e, { encoding: t, buffer: r, maxBuffer: n }) => {
    if (!(!e || !r))
      return t ? Qn(e, { encoding: t, maxBuffer: n }) : Qn.buffer(e, { maxBuffer: n });
  }, "getStreamPromise"), ec = /* @__PURE__ */ o(async ({ stdout: e, stderr: t, all: r }, { encoding: n, buffer: i, maxBuffer: s }, a) => {
    let c = Gt(e, { encoding: n, buffer: i, maxBuffer: s }), u = Gt(t, { encoding: n, buffer: i, maxBuffer: s }), l = Gt(r, { encoding: n, buffer: i,
    maxBuffer: s * 2 });
    try {
      return await Promise.all([a, c, u, l]);
    } catch (f) {
      return Promise.all([
        { error: f, signal: f.signal, timedOut: f.timedOut },
        Rt(e, c),
        Rt(t, u),
        Rt(r, l)
      ]);
    }
  }, "getSpawnedResult"), tc = /* @__PURE__ */ o(({ input: e }) => {
    if (eo(e))
      throw new TypeError("The `input` option cannot be a stream in sync mode");
  }, "validateInputSync");
  to.exports = {
    handleInput: Za,
    makeAllStream: Qa,
    getSpawnedResult: ec,
    validateInputSync: tc
  };
});

// ../node_modules/execa/lib/promise.js
var oo = p((Mf, no) => {
  "use strict";
  var rc = (async () => {
  })().constructor.prototype, nc = ["then", "catch", "finally"].map((e) => [
    e,
    Reflect.getOwnPropertyDescriptor(rc, e)
  ]), oc = /* @__PURE__ */ o((e, t) => {
    for (let [r, n] of nc) {
      let i = typeof t == "function" ? (...s) => Reflect.apply(n.value, t(), s) : n.value.bind(t);
      Reflect.defineProperty(e, r, { ...n, value: i });
    }
    return e;
  }, "mergePromise"), ic = /* @__PURE__ */ o((e) => new Promise((t, r) => {
    e.on("exit", (n, i) => {
      t({ exitCode: n, signal: i });
    }), e.on("error", (n) => {
      r(n);
    }), e.stdin && e.stdin.on("error", (n) => {
      r(n);
    });
  }), "getSpawnedPromise");
  no.exports = {
    mergePromise: oc,
    getSpawnedPromise: ic
  };
});

// ../node_modules/execa/lib/command.js
var ao = p((Ff, so) => {
  "use strict";
  var io = /* @__PURE__ */ o((e, t = []) => Array.isArray(t) ? [e, ...t] : [e], "normalizeArgs"), sc = /^[\w.-]+$/, ac = /"/g, cc = /* @__PURE__ */ o(
  (e) => typeof e != "string" || sc.test(e) ? e : `"${e.replace(ac, '\\"')}"`, "escapeArg"), uc = /* @__PURE__ */ o((e, t) => io(e, t).join(
  " "), "joinCommand"), lc = /* @__PURE__ */ o((e, t) => io(e, t).map((r) => cc(r)).join(" "), "getEscapedCommand"), fc = / +/g, pc = /* @__PURE__ */ o(
  (e) => {
    let t = [];
    for (let r of e.trim().split(fc)) {
      let n = t[t.length - 1];
      n && n.endsWith("\\") ? t[t.length - 1] = `${n.slice(0, -1)} ${r}` : t.push(r);
    }
    return t;
  }, "parseCommand");
  so.exports = {
    joinCommand: uc,
    getEscapedCommand: lc,
    parseCommand: pc
  };
});

// ../node_modules/execa/index.js
var ho = p(($f, z) => {
  "use strict";
  var dc = require("path"), _t = require("child_process"), mc = Pt(), hc = wn(), yc = En(), gc = Cn(), Re = Bn(), uo = Mn(), { spawnedKill: bc,
  spawnedCancel: xc, setupTimeout: Sc, validateTimeout: wc, setExitHandler: vc } = Vn(), { handleInput: Pc, getSpawnedResult: Ec, makeAllStream: Tc,
  validateInputSync: kc } = ro(), { mergePromise: co, getSpawnedPromise: Ic } = oo(), { joinCommand: lo, parseCommand: fo, getEscapedCommand: po } = ao(),
  Cc = 1e3 * 1e3 * 100, Oc = /* @__PURE__ */ o(({ env: e, extendEnv: t, preferLocal: r, localDir: n, execPath: i }) => {
    let s = t ? { ...process.env, ...e } : e;
    return r ? yc.env({ env: s, cwd: n, execPath: i }) : s;
  }, "getEnv"), mo = /* @__PURE__ */ o((e, t, r = {}) => {
    let n = mc._parse(e, t, r);
    return e = n.command, t = n.args, r = n.options, r = {
      maxBuffer: Cc,
      buffer: !0,
      stripFinalNewline: !0,
      extendEnv: !0,
      preferLocal: !1,
      localDir: r.cwd || process.cwd(),
      execPath: process.execPath,
      encoding: "utf8",
      reject: !0,
      cleanup: !0,
      all: !1,
      windowsHide: !0,
      ...r
    }, r.env = Oc(r), r.stdio = uo(r), process.platform === "win32" && dc.basename(e, ".exe") === "cmd" && t.unshift("/q"), { file: e, args: t,
    options: r, parsed: n };
  }, "handleArguments"), oe = /* @__PURE__ */ o((e, t, r) => typeof t != "string" && !Buffer.isBuffer(t) ? r === void 0 ? void 0 : "" : e.stripFinalNewline ?
  hc(t) : t, "handleOutput"), Ge = /* @__PURE__ */ o((e, t, r) => {
    let n = mo(e, t, r), i = lo(e, t), s = po(e, t);
    wc(n.options);
    let a;
    try {
      a = _t.spawn(n.file, n.args, n.options);
    } catch (m) {
      let y = new _t.ChildProcess(), h = Promise.reject(Re({
        error: m,
        stdout: "",
        stderr: "",
        all: "",
        command: i,
        escapedCommand: s,
        parsed: n,
        timedOut: !1,
        isCanceled: !1,
        killed: !1
      }));
      return co(y, h);
    }
    let c = Ic(a), u = Sc(a, n.options, c), l = vc(a, n.options, u), f = { isCanceled: !1 };
    a.kill = bc.bind(null, a.kill.bind(a)), a.cancel = xc.bind(null, a, f);
    let g = gc(/* @__PURE__ */ o(async () => {
      let [{ error: m, exitCode: y, signal: h, timedOut: S }, k, E, O] = await Ec(a, n.options, l), G = oe(n.options, k), _ = oe(n.options, E),
      d = oe(n.options, O);
      if (m || y !== 0 || h !== null) {
        let v = Re({
          error: m,
          exitCode: y,
          signal: h,
          stdout: G,
          stderr: _,
          all: d,
          command: i,
          escapedCommand: s,
          parsed: n,
          timedOut: S,
          isCanceled: f.isCanceled,
          killed: a.killed
        });
        if (!n.options.reject)
          return v;
        throw v;
      }
      return {
        command: i,
        escapedCommand: s,
        exitCode: 0,
        stdout: G,
        stderr: _,
        all: d,
        failed: !1,
        timedOut: !1,
        isCanceled: !1,
        killed: !1
      };
    }, "handlePromise"));
    return Pc(a, n.options.input), a.all = Tc(a, n.options), co(a, g);
  }, "execa");
  z.exports = Ge;
  z.exports.sync = (e, t, r) => {
    let n = mo(e, t, r), i = lo(e, t), s = po(e, t);
    kc(n.options);
    let a;
    try {
      a = _t.spawnSync(n.file, n.args, n.options);
    } catch (l) {
      throw Re({
        error: l,
        stdout: "",
        stderr: "",
        all: "",
        command: i,
        escapedCommand: s,
        parsed: n,
        timedOut: !1,
        isCanceled: !1,
        killed: !1
      });
    }
    let c = oe(n.options, a.stdout, a.error), u = oe(n.options, a.stderr, a.error);
    if (a.error || a.status !== 0 || a.signal !== null) {
      let l = Re({
        stdout: c,
        stderr: u,
        error: a.error,
        signal: a.signal,
        exitCode: a.status,
        command: i,
        escapedCommand: s,
        parsed: n,
        timedOut: a.error && a.error.code === "ETIMEDOUT",
        isCanceled: !1,
        killed: a.signal !== null
      });
      if (!n.options.reject)
        return l;
      throw l;
    }
    return {
      command: i,
      escapedCommand: s,
      exitCode: 0,
      stdout: c,
      stderr: u,
      failed: !1,
      timedOut: !1,
      isCanceled: !1,
      killed: !1
    };
  };
  z.exports.command = (e, t) => {
    let [r, ...n] = fo(e);
    return Ge(r, n, t);
  };
  z.exports.commandSync = (e, t) => {
    let [r, ...n] = fo(e);
    return Ge.sync(r, n, t);
  };
  z.exports.node = (e, t, r = {}) => {
    t && !Array.isArray(t) && typeof t == "object" && (r = t, t = []);
    let n = uo.node(r), i = process.execArgv.filter((c) => !c.startsWith("--inspect")), {
      nodePath: s = process.execPath,
      nodeOptions: a = i
    } = r;
    return Ge(
      s,
      [
        ...a,
        e,
        ...Array.isArray(t) ? t : []
      ],
      {
        ...r,
        stdin: void 0,
        stdout: void 0,
        stderr: void 0,
        stdio: n,
        shell: !1
      }
    );
  };
});

// ../node_modules/detect-package-manager/dist/index.js
var xo = p((bo) => {
  var Ac = Object.create, Ne = Object.defineProperty, Rc = Object.getOwnPropertyDescriptor, Gc = Object.getOwnPropertyNames, _c = Object.getPrototypeOf,
  jc = Object.prototype.hasOwnProperty, yo = /* @__PURE__ */ o((e) => Ne(e, "__esModule", { value: !0 }), "__markAsModule"), Nc = /* @__PURE__ */ o(
  (e, t) => {
    yo(e);
    for (var r in t)
      Ne(e, r, { get: t[r], enumerable: !0 });
  }, "__export"), Bc = /* @__PURE__ */ o((e, t, r) => {
    if (t && typeof t == "object" || typeof t == "function")
      for (let n of Gc(t))
        !jc.call(e, n) && n !== "default" && Ne(e, n, { get: /* @__PURE__ */ o(() => t[n], "get"), enumerable: !(r = Rc(t, n)) || r.enumerable });
    return e;
  }, "__reExport"), Nt = /* @__PURE__ */ o((e) => Bc(yo(Ne(e != null ? Ac(_c(e)) : {}, "default", e && e.__esModule && "default" in e ? { get: /* @__PURE__ */ o(
  () => e.default, "get"), enumerable: !0 } : { value: e, enumerable: !0 })), e), "__toModule");
  Nc(bo, {
    clearCache: /* @__PURE__ */ o(() => Uc, "clearCache"),
    detect: /* @__PURE__ */ o(() => Lc, "detect"),
    getNpmVersion: /* @__PURE__ */ o(() => Fc, "getNpmVersion")
  });
  var Dc = Nt(require("fs")), _e = Nt(require("path")), go = Nt(ho());
  async function je(e) {
    try {
      return await Dc.promises.access(e), !0;
    } catch {
      return !1;
    }
  }
  o(je, "pathExists");
  var B = /* @__PURE__ */ new Map();
  function jt(e) {
    let t = `has_global_${e}`;
    return B.has(t) ? Promise.resolve(B.get(t)) : (0, go.default)(e, ["--version"]).then((r) => /^\d+.\d+.\d+$/.test(r.stdout)).then((r) => (B.
    set(t, r), r)).catch(() => !1);
  }
  o(jt, "hasGlobalInstallation");
  function Mc(e = ".") {
    let t = `lockfile_${e}`;
    return B.has(t) ? Promise.resolve(B.get(t)) : Promise.all([
      je((0, _e.resolve)(e, "yarn.lock")),
      je((0, _e.resolve)(e, "package-lock.json")),
      je((0, _e.resolve)(e, "pnpm-lock.yaml")),
      je((0, _e.resolve)(e, "bun.lockb"))
    ]).then(([r, n, i, s]) => {
      let a = null;
      return r ? a = "yarn" : i ? a = "pnpm" : s ? a = "bun" : n && (a = "npm"), B.set(t, a), a;
    });
  }
  o(Mc, "getTypeofLockFile");
  var Lc = /* @__PURE__ */ o(async ({
    cwd: e,
    includeGlobalBun: t
  } = {}) => {
    let r = await Mc(e);
    if (r)
      return r;
    let [n, i, s] = await Promise.all([
      jt("yarn"),
      jt("pnpm"),
      t && jt("bun")
    ]);
    return n ? "yarn" : i ? "pnpm" : s ? "bun" : "npm";
  }, "detect");
  function Fc(e) {
    return (0, go.default)(e || "npm", ["--version"]).then((t) => t.stdout);
  }
  o(Fc, "getNpmVersion");
  function Uc() {
    return B.clear();
  }
  o(Uc, "clearCache");
});

// ../node_modules/walk-up-path/dist/cjs/index.js
var wo = p((Be) => {
  "use strict";
  Object.defineProperty(Be, "__esModule", { value: !0 });
  Be.walkUp = void 0;
  var So = require("path"), $c = /* @__PURE__ */ o(function* (e) {
    for (e = (0, So.resolve)(e); e; ) {
      yield e;
      let t = (0, So.dirname)(e);
      if (t === e)
        break;
      e = t;
    }
  }, "walkUp");
  Be.walkUp = $c;
});

// ../node_modules/common-path-prefix/index.js
var Gi = p((qd, Ri) => {
  "use strict";
  var { sep: Vu } = require("path"), zu = /* @__PURE__ */ o((e) => {
    for (let t of e) {
      let r = /(\/|\\)/.exec(t);
      if (r !== null) return r[0];
    }
    return Vu;
  }, "determineSeparator");
  Ri.exports = /* @__PURE__ */ o(function(t, r = zu(t)) {
    let [n = "", ...i] = t;
    if (n === "" || i.length === 0) return "";
    let s = n.split(r), a = s.length;
    for (let u of i) {
      let l = u.split(r);
      for (let f = 0; f < a; f++)
        l[f] !== s[f] && (a = f);
      if (a === 0) return "";
    }
    let c = s.slice(0, a).join(r);
    return c.endsWith(r) ? c : c + r;
  }, "commonPathPrefix");
});

// ../node_modules/fetch-retry/index.js
var ps = p((Hh, fs) => {
  "use strict";
  fs.exports = function(e, t) {
    if (t = t || {}, typeof e != "function")
      throw new R("fetch must be a function");
    if (typeof t != "object")
      throw new R("defaults must be an object");
    if (t.retries !== void 0 && !it(t.retries))
      throw new R("retries must be a positive integer");
    if (t.retryDelay !== void 0 && !it(t.retryDelay) && typeof t.retryDelay != "function")
      throw new R("retryDelay must be a positive integer or a function returning a positive integer");
    if (t.retryOn !== void 0 && !Array.isArray(t.retryOn) && typeof t.retryOn != "function")
      throw new R("retryOn property expects an array or function");
    var r = {
      retries: 3,
      retryDelay: 1e3,
      retryOn: []
    };
    return t = Object.assign(r, t), /* @__PURE__ */ o(function(i, s) {
      var a = t.retries, c = t.retryDelay, u = t.retryOn;
      if (s && s.retries !== void 0)
        if (it(s.retries))
          a = s.retries;
        else
          throw new R("retries must be a positive integer");
      if (s && s.retryDelay !== void 0)
        if (it(s.retryDelay) || typeof s.retryDelay == "function")
          c = s.retryDelay;
        else
          throw new R("retryDelay must be a positive integer or a function returning a positive integer");
      if (s && s.retryOn)
        if (Array.isArray(s.retryOn) || typeof s.retryOn == "function")
          u = s.retryOn;
        else
          throw new R("retryOn property expects an array or function");
      return new Promise(function(l, f) {
        var x = /* @__PURE__ */ o(function(m) {
          var y = typeof Request < "u" && i instanceof Request ? i.clone() : i;
          e(y, s).then(function(h) {
            if (Array.isArray(u) && u.indexOf(h.status) === -1)
              l(h);
            else if (typeof u == "function")
              try {
                return Promise.resolve(u(m, null, h)).then(function(S) {
                  S ? g(m, null, h) : l(h);
                }).catch(f);
              } catch (S) {
                f(S);
              }
            else
              m < a ? g(m, null, h) : l(h);
          }).catch(function(h) {
            if (typeof u == "function")
              try {
                Promise.resolve(u(m, h, null)).then(function(S) {
                  S ? g(m, h, null) : f(h);
                }).catch(function(S) {
                  f(S);
                });
              } catch (S) {
                f(S);
              }
            else m < a ? g(m, h, null) : f(h);
          });
        }, "wrappedFetch");
        function g(m, y, h) {
          var S = typeof c == "function" ? c(m, y, h) : c;
          setTimeout(function() {
            x(++m);
          }, S);
        }
        o(g, "retry"), x(0);
      });
    }, "fetchRetry");
  };
  function it(e) {
    return Number.isInteger(e) && e >= 0;
  }
  o(it, "isPositiveInteger");
  function R(e) {
    this.name = "ArgumentError", this.message = e;
  }
  o(R, "ArgumentError");
});

// src/telemetry/index.ts
var kl = {};
Gs(kl, {
  addToGlobalContext: () => Ts,
  computeStorybookMetadata: () => ls,
  getPrecedingUpgrade: () => ws,
  getStorybookMetadata: () => br,
  isExampleStoryId: () => El,
  metaFrameworks: () => yr,
  oneWayHash: () => st,
  sanitizeAddonName: () => gr,
  telemetry: () => Tl
});
module.exports = _s(kl);
var Er = require("@storybook/core/node-logger");

// src/telemetry/notify.ts
var ft = require("@storybook/core/common"), be = b(Cr(), 1);
var Or = "telemetry-notification-date", L = console, Ar = /* @__PURE__ */ o(async () => {
  await ft.cache.get(Or, null) || (ft.cache.set(Or, Date.now()), L.log(), L.log(
    `${be.default.magenta(
      be.default.bold("attention")
    )} => Storybook now collects completely anonymous telemetry regarding usage.`
  ), L.log("This information is used to shape Storybook's roadmap and prioritize features."), L.log(
    "You can learn more, including how to opt-out if you'd not like to participate in this anonymous program, by visiting the following URL:"
  ), L.log(be.default.cyan("https://storybook.js.org/telemetry")), L.log());
}, "notify");

// src/telemetry/sanitize.ts
var pt = b(require("node:path"), 1);
function Rr(e) {
  return e.replace(/[-[/{}()*+?.\\^$|]/g, "\\$&");
}
o(Rr, "regexpEscape");
function Gr(e = "") {
  return e.replace(/\u001B\[[0-9;]*m/g, "");
}
o(Gr, "removeAnsiEscapeCodes");
function Q(e, t = pt.default.sep) {
  if (!e)
    return e;
  let r = process.cwd().split(t);
  for (; r.length > 1; ) {
    let n = r.join(t), i = new RegExp(Rr(n), "gi");
    e = e.replace(i, "$SNIP");
    let s = r.join(t + t), a = new RegExp(Rr(s), "gi");
    e = e.replace(a, "$SNIP"), r.pop();
  }
  return e;
}
o(Q, "cleanPaths");
function xe(e, t = pt.default.sep) {
  try {
    e = {
      ...JSON.parse(JSON.stringify(e)),
      message: Gr(e.message),
      stack: Gr(e.stack),
      cause: e.cause,
      name: e.name
    };
    let r = Q(JSON.stringify(e), t);
    return JSON.parse(r);
  } catch (r) {
    return `Sanitization error: ${r?.message}`;
  }
}
o(xe, "sanitizeError");

// src/telemetry/storybook-metadata.ts
var cs = require("node:path"), A = require("@storybook/core/common"), us = require("@storybook/core/csf-tools"), ot = b(xo(), 1);

// ../node_modules/fd-package-json/dist/esm/main.js
var vo = b(wo(), 1), Po = require("node:path"), De = require("node:fs/promises"), Eo = require("node:fs");
async function qc(e) {
  try {
    return (await (0, De.stat)(e)).isFile();
  } catch {
    return !1;
  }
}
o(qc, "fileExists");
async function Bt(e) {
  for (let t of (0, vo.walkUp)(e)) {
    let r = (0, Po.resolve)(t, "package.json");
    if (await qc(r))
      return r;
  }
  return null;
}
o(Bt, "findPackagePath");
async function To(e) {
  let t = await Bt(e);
  if (!t)
    return null;
  try {
    let r = await (0, De.readFile)(t, { encoding: "utf8" });
    return JSON.parse(r);
  } catch {
    return null;
  }
}
o(To, "findPackage");

// src/telemetry/get-application-file-count.ts
var Ji = require("node:path");

// src/telemetry/exec-command-count-lines.ts
var Oi = require("node:readline");

// node_modules/execa/index.js
var vi = require("node:buffer"), Pi = b(require("node:path"), 1), Ye = b(require("node:child_process"), 1), ce = b(require("node:process"), 1),
Ei = b(Pt(), 1);

// ../node_modules/strip-final-newline/index.js
function Dt(e) {
  let t = typeof e == "string" ? `
` : 10, r = typeof e == "string" ? "\r" : 13;
  return e[e.length - 1] === t && (e = e.slice(0, -1)), e[e.length - 1] === r && (e = e.slice(0, -1)), e;
}
o(Dt, "stripFinalNewline");

// node_modules/npm-run-path/index.js
var ie = b(require("node:process"), 1), J = b(require("node:path"), 1), ko = b(require("node:url"), 1);

// node_modules/path-key/index.js
function Me(e = {}) {
  let {
    env: t = process.env,
    platform: r = process.platform
  } = e;
  return r !== "win32" ? "PATH" : Object.keys(t).reverse().find((n) => n.toUpperCase() === "PATH") || "Path";
}
o(Me, "pathKey");

// node_modules/npm-run-path/index.js
function Wc(e = {}) {
  let {
    cwd: t = ie.default.cwd(),
    path: r = ie.default.env[Me()],
    execPath: n = ie.default.execPath
  } = e, i, s = t instanceof URL ? ko.default.fileURLToPath(t) : t, a = J.default.resolve(s), c = [];
  for (; i !== a; )
    c.push(J.default.join(a, "node_modules/.bin")), i = a, a = J.default.resolve(a, "..");
  return c.push(J.default.resolve(s, n, "..")), [...c, r].join(J.default.delimiter);
}
o(Wc, "npmRunPath");
function Io({ env: e = ie.default.env, ...t } = {}) {
  e = { ...e };
  let r = Me({ env: e });
  return t.path = e[r], e[r] = Wc(t), e;
}
o(Io, "npmRunPathEnv");

// node_modules/mimic-fn/index.js
var Hc = /* @__PURE__ */ o((e, t, r, n) => {
  if (r === "length" || r === "prototype" || r === "arguments" || r === "caller")
    return;
  let i = Object.getOwnPropertyDescriptor(e, r), s = Object.getOwnPropertyDescriptor(t, r);
  !Vc(i, s) && n || Object.defineProperty(e, r, s);
}, "copyProperty"), Vc = /* @__PURE__ */ o(function(e, t) {
  return e === void 0 || e.configurable || e.writable === t.writable && e.enumerable === t.enumerable && e.configurable === t.configurable &&
  (e.writable || e.value === t.value);
}, "canCopyProperty"), zc = /* @__PURE__ */ o((e, t) => {
  let r = Object.getPrototypeOf(t);
  r !== Object.getPrototypeOf(e) && Object.setPrototypeOf(e, r);
}, "changePrototype"), Jc = /* @__PURE__ */ o((e, t) => `/* Wrapped ${e}*/
${t}`, "wrappedToString"), Kc = Object.getOwnPropertyDescriptor(Function.prototype, "toString"), Xc = Object.getOwnPropertyDescriptor(Function.
prototype.toString, "name"), Yc = /* @__PURE__ */ o((e, t, r) => {
  let n = r === "" ? "" : `with ${r.trim()}() `, i = Jc.bind(null, n, t.toString());
  Object.defineProperty(i, "name", Xc), Object.defineProperty(e, "toString", { ...Kc, value: i });
}, "changeToString");
function Mt(e, t, { ignoreNonConfigurable: r = !1 } = {}) {
  let { name: n } = e;
  for (let i of Reflect.ownKeys(t))
    Hc(e, t, i, r);
  return zc(e, t), Yc(e, t, n), e;
}
o(Mt, "mimicFunction");

// node_modules/onetime/index.js
var Le = /* @__PURE__ */ new WeakMap(), Co = /* @__PURE__ */ o((e, t = {}) => {
  if (typeof e != "function")
    throw new TypeError("Expected a function");
  let r, n = 0, i = e.displayName || e.name || "<anonymous>", s = /* @__PURE__ */ o(function(...a) {
    if (Le.set(s, ++n), n === 1)
      r = e.apply(this, a), e = null;
    else if (t.throw === !0)
      throw new Error(`Function \`${i}\` can only be called once`);
    return r;
  }, "onetime");
  return Mt(s, e), Le.set(s, n), s;
}, "onetime");
Co.callCount = (e) => {
  if (!Le.has(e))
    throw new Error(`The given function \`${e.name}\` is not wrapped by the \`onetime\` package`);
  return Le.get(e);
};
var Oo = Co;

// node_modules/execa/lib/error.js
var Bo = b(require("node:process"), 1);

// node_modules/human-signals/build/src/main.js
var jo = require("node:os");

// node_modules/human-signals/build/src/realtime.js
var Ao = /* @__PURE__ */ o(() => {
  let e = Lt - Ro + 1;
  return Array.from({ length: e }, Zc);
}, "getRealtimeSignals"), Zc = /* @__PURE__ */ o((e, t) => ({
  name: `SIGRT${t + 1}`,
  number: Ro + t,
  action: "terminate",
  description: "Application-specific signal (realtime)",
  standard: "posix"
}), "getRealtimeSignal"), Ro = 34, Lt = 64;

// node_modules/human-signals/build/src/signals.js
var _o = require("node:os");

// node_modules/human-signals/build/src/core.js
var Go = [
  {
    name: "SIGHUP",
    number: 1,
    action: "terminate",
    description: "Terminal closed",
    standard: "posix"
  },
  {
    name: "SIGINT",
    number: 2,
    action: "terminate",
    description: "User interruption with CTRL-C",
    standard: "ansi"
  },
  {
    name: "SIGQUIT",
    number: 3,
    action: "core",
    description: "User interruption with CTRL-\\",
    standard: "posix"
  },
  {
    name: "SIGILL",
    number: 4,
    action: "core",
    description: "Invalid machine instruction",
    standard: "ansi"
  },
  {
    name: "SIGTRAP",
    number: 5,
    action: "core",
    description: "Debugger breakpoint",
    standard: "posix"
  },
  {
    name: "SIGABRT",
    number: 6,
    action: "core",
    description: "Aborted",
    standard: "ansi"
  },
  {
    name: "SIGIOT",
    number: 6,
    action: "core",
    description: "Aborted",
    standard: "bsd"
  },
  {
    name: "SIGBUS",
    number: 7,
    action: "core",
    description: "Bus error due to misaligned, non-existing address or paging error",
    standard: "bsd"
  },
  {
    name: "SIGEMT",
    number: 7,
    action: "terminate",
    description: "Command should be emulated but is not implemented",
    standard: "other"
  },
  {
    name: "SIGFPE",
    number: 8,
    action: "core",
    description: "Floating point arithmetic error",
    standard: "ansi"
  },
  {
    name: "SIGKILL",
    number: 9,
    action: "terminate",
    description: "Forced termination",
    standard: "posix",
    forced: !0
  },
  {
    name: "SIGUSR1",
    number: 10,
    action: "terminate",
    description: "Application-specific signal",
    standard: "posix"
  },
  {
    name: "SIGSEGV",
    number: 11,
    action: "core",
    description: "Segmentation fault",
    standard: "ansi"
  },
  {
    name: "SIGUSR2",
    number: 12,
    action: "terminate",
    description: "Application-specific signal",
    standard: "posix"
  },
  {
    name: "SIGPIPE",
    number: 13,
    action: "terminate",
    description: "Broken pipe or socket",
    standard: "posix"
  },
  {
    name: "SIGALRM",
    number: 14,
    action: "terminate",
    description: "Timeout or timer",
    standard: "posix"
  },
  {
    name: "SIGTERM",
    number: 15,
    action: "terminate",
    description: "Termination",
    standard: "ansi"
  },
  {
    name: "SIGSTKFLT",
    number: 16,
    action: "terminate",
    description: "Stack is empty or overflowed",
    standard: "other"
  },
  {
    name: "SIGCHLD",
    number: 17,
    action: "ignore",
    description: "Child process terminated, paused or unpaused",
    standard: "posix"
  },
  {
    name: "SIGCLD",
    number: 17,
    action: "ignore",
    description: "Child process terminated, paused or unpaused",
    standard: "other"
  },
  {
    name: "SIGCONT",
    number: 18,
    action: "unpause",
    description: "Unpaused",
    standard: "posix",
    forced: !0
  },
  {
    name: "SIGSTOP",
    number: 19,
    action: "pause",
    description: "Paused",
    standard: "posix",
    forced: !0
  },
  {
    name: "SIGTSTP",
    number: 20,
    action: "pause",
    description: 'Paused using CTRL-Z or "suspend"',
    standard: "posix"
  },
  {
    name: "SIGTTIN",
    number: 21,
    action: "pause",
    description: "Background process cannot read terminal input",
    standard: "posix"
  },
  {
    name: "SIGBREAK",
    number: 21,
    action: "terminate",
    description: "User interruption with CTRL-BREAK",
    standard: "other"
  },
  {
    name: "SIGTTOU",
    number: 22,
    action: "pause",
    description: "Background process cannot write to terminal output",
    standard: "posix"
  },
  {
    name: "SIGURG",
    number: 23,
    action: "ignore",
    description: "Socket received out-of-band data",
    standard: "bsd"
  },
  {
    name: "SIGXCPU",
    number: 24,
    action: "core",
    description: "Process timed out",
    standard: "bsd"
  },
  {
    name: "SIGXFSZ",
    number: 25,
    action: "core",
    description: "File too big",
    standard: "bsd"
  },
  {
    name: "SIGVTALRM",
    number: 26,
    action: "terminate",
    description: "Timeout or timer",
    standard: "bsd"
  },
  {
    name: "SIGPROF",
    number: 27,
    action: "terminate",
    description: "Timeout or timer",
    standard: "bsd"
  },
  {
    name: "SIGWINCH",
    number: 28,
    action: "ignore",
    description: "Terminal window size changed",
    standard: "bsd"
  },
  {
    name: "SIGIO",
    number: 29,
    action: "terminate",
    description: "I/O is available",
    standard: "other"
  },
  {
    name: "SIGPOLL",
    number: 29,
    action: "terminate",
    description: "Watched event",
    standard: "other"
  },
  {
    name: "SIGINFO",
    number: 29,
    action: "ignore",
    description: "Request for process information",
    standard: "other"
  },
  {
    name: "SIGPWR",
    number: 30,
    action: "terminate",
    description: "Device running out of power",
    standard: "systemv"
  },
  {
    name: "SIGSYS",
    number: 31,
    action: "core",
    description: "Invalid system call",
    standard: "other"
  },
  {
    name: "SIGUNUSED",
    number: 31,
    action: "terminate",
    description: "Invalid system call",
    standard: "other"
  }
];

// node_modules/human-signals/build/src/signals.js
var Ft = /* @__PURE__ */ o(() => {
  let e = Ao();
  return [...Go, ...e].map(Qc);
}, "getSignals"), Qc = /* @__PURE__ */ o(({
  name: e,
  number: t,
  description: r,
  action: n,
  forced: i = !1,
  standard: s
}) => {
  let {
    signals: { [e]: a }
  } = _o.constants, c = a !== void 0;
  return { name: e, number: c ? a : t, description: r, supported: c, action: n, forced: i, standard: s };
}, "normalizeSignal");

// node_modules/human-signals/build/src/main.js
var eu = /* @__PURE__ */ o(() => {
  let e = Ft();
  return Object.fromEntries(e.map(tu));
}, "getSignalsByName"), tu = /* @__PURE__ */ o(({
  name: e,
  number: t,
  description: r,
  supported: n,
  action: i,
  forced: s,
  standard: a
}) => [e, { name: e, number: t, description: r, supported: n, action: i, forced: s, standard: a }], "getSignalByName"), No = eu(), ru = /* @__PURE__ */ o(
() => {
  let e = Ft(), t = Lt + 1, r = Array.from(
    { length: t },
    (n, i) => nu(i, e)
  );
  return Object.assign({}, ...r);
}, "getSignalsByNumber"), nu = /* @__PURE__ */ o((e, t) => {
  let r = ou(e, t);
  if (r === void 0)
    return {};
  let { name: n, description: i, supported: s, action: a, forced: c, standard: u } = r;
  return {
    [e]: {
      name: n,
      number: e,
      description: i,
      supported: s,
      action: a,
      forced: c,
      standard: u
    }
  };
}, "getSignalByNumber"), ou = /* @__PURE__ */ o((e, t) => {
  let r = t.find(({ name: n }) => jo.constants.signals[n] === e);
  return r !== void 0 ? r : t.find((n) => n.number === e);
}, "findSignalByNumber"), gp = ru();

// node_modules/execa/lib/error.js
var iu = /* @__PURE__ */ o(({ timedOut: e, timeout: t, errorCode: r, signal: n, signalDescription: i, exitCode: s, isCanceled: a }) => e ? `\
timed out after ${t} milliseconds` : a ? "was canceled" : r !== void 0 ? `failed with ${r}` : n !== void 0 ? `was killed with ${n} (${i})` :
s !== void 0 ? `failed with exit code ${s}` : "failed", "getErrorPrefix"), se = /* @__PURE__ */ o(({
  stdout: e,
  stderr: t,
  all: r,
  error: n,
  signal: i,
  exitCode: s,
  command: a,
  escapedCommand: c,
  timedOut: u,
  isCanceled: l,
  killed: f,
  parsed: { options: { timeout: x, cwd: g = Bo.default.cwd() } }
}) => {
  s = s === null ? void 0 : s, i = i === null ? void 0 : i;
  let m = i === void 0 ? void 0 : No[i].description, y = n && n.code, S = `Command ${iu({ timedOut: u, timeout: x, errorCode: y, signal: i, signalDescription: m,
  exitCode: s, isCanceled: l })}: ${a}`, k = Object.prototype.toString.call(n) === "[object Error]", E = k ? `${S}
${n.message}` : S, O = [E, t, e].filter(Boolean).join(`
`);
  return k ? (n.originalMessage = n.message, n.message = O) : n = new Error(O), n.shortMessage = E, n.command = a, n.escapedCommand = c, n.exitCode =
  s, n.signal = i, n.signalDescription = m, n.stdout = e, n.stderr = t, n.cwd = g, r !== void 0 && (n.all = r), "bufferedData" in n && delete n.
  bufferedData, n.failed = !0, n.timedOut = !!u, n.isCanceled = l, n.killed = f && !u, n;
}, "makeError");

// node_modules/execa/lib/stdio.js
var Fe = ["stdin", "stdout", "stderr"], su = /* @__PURE__ */ o((e) => Fe.some((t) => e[t] !== void 0), "hasAlias"), Do = /* @__PURE__ */ o((e) => {
  if (!e)
    return;
  let { stdio: t } = e;
  if (t === void 0)
    return Fe.map((n) => e[n]);
  if (su(e))
    throw new Error(`It's not possible to provide \`stdio\` in combination with one of ${Fe.map((n) => `\`${n}\``).join(", ")}`);
  if (typeof t == "string")
    return t;
  if (!Array.isArray(t))
    throw new TypeError(`Expected \`stdio\` to be of type \`string\` or \`Array\`, got \`${typeof t}\``);
  let r = Math.max(t.length, Fe.length);
  return Array.from({ length: r }, (n, i) => t[i]);
}, "normalizeStdio");

// node_modules/execa/lib/kill.js
var Lo = b(require("node:os"), 1);

// node_modules/signal-exit/dist/mjs/signals.js
var D = [];
D.push("SIGHUP", "SIGINT", "SIGTERM");
process.platform !== "win32" && D.push(
  "SIGALRM",
  "SIGABRT",
  "SIGVTALRM",
  "SIGXCPU",
  "SIGXFSZ",
  "SIGUSR2",
  "SIGTRAP",
  "SIGSYS",
  "SIGQUIT",
  "SIGIOT"
  // should detect profiler and enable/disable accordingly.
  // see #21
  // 'SIGPROF'
);
process.platform === "linux" && D.push("SIGIO", "SIGPOLL", "SIGPWR", "SIGSTKFLT");

// node_modules/signal-exit/dist/mjs/index.js
var Ue = /* @__PURE__ */ o((e) => !!e && typeof e == "object" && typeof e.removeListener == "function" && typeof e.emit == "function" && typeof e.
reallyExit == "function" && typeof e.listeners == "function" && typeof e.kill == "function" && typeof e.pid == "number" && typeof e.on == "f\
unction", "processOk"), Ut = Symbol.for("signal-exit emitter"), $t = globalThis, au = Object.defineProperty.bind(Object), qt = class {
  static {
    o(this, "Emitter");
  }
  emitted = {
    afterExit: !1,
    exit: !1
  };
  listeners = {
    afterExit: [],
    exit: []
  };
  count = 0;
  id = Math.random();
  constructor() {
    if ($t[Ut])
      return $t[Ut];
    au($t, Ut, {
      value: this,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
  }
  on(t, r) {
    this.listeners[t].push(r);
  }
  removeListener(t, r) {
    let n = this.listeners[t], i = n.indexOf(r);
    i !== -1 && (i === 0 && n.length === 1 ? n.length = 0 : n.splice(i, 1));
  }
  emit(t, r, n) {
    if (this.emitted[t])
      return !1;
    this.emitted[t] = !0;
    let i = !1;
    for (let s of this.listeners[t])
      i = s(r, n) === !0 || i;
    return t === "exit" && (i = this.emit("afterExit", r, n) || i), i;
  }
}, $e = class {
  static {
    o(this, "SignalExitBase");
  }
}, cu = /* @__PURE__ */ o((e) => ({
  onExit(t, r) {
    return e.onExit(t, r);
  },
  load() {
    return e.load();
  },
  unload() {
    return e.unload();
  }
}), "signalExitWrap"), Wt = class extends $e {
  static {
    o(this, "SignalExitFallback");
  }
  onExit() {
    return () => {
    };
  }
  load() {
  }
  unload() {
  }
}, Ht = class extends $e {
  static {
    o(this, "SignalExit");
  }
  // "SIGHUP" throws an `ENOSYS` error on Windows,
  // so use a supported signal instead
  /* c8 ignore start */
  #s = Vt.platform === "win32" ? "SIGINT" : "SIGHUP";
  /* c8 ignore stop */
  #t = new qt();
  #e;
  #o;
  #i;
  #n = {};
  #r = !1;
  constructor(t) {
    super(), this.#e = t, this.#n = {};
    for (let r of D)
      this.#n[r] = () => {
        let n = this.#e.listeners(r), { count: i } = this.#t, s = t;
        if (typeof s.__signal_exit_emitter__ == "object" && typeof s.__signal_exit_emitter__.count == "number" && (i += s.__signal_exit_emitter__.
        count), n.length === i) {
          this.unload();
          let a = this.#t.emit("exit", null, r), c = r === "SIGHUP" ? this.#s : r;
          a || t.kill(t.pid, c);
        }
      };
    this.#i = t.reallyExit, this.#o = t.emit;
  }
  onExit(t, r) {
    if (!Ue(this.#e))
      return () => {
      };
    this.#r === !1 && this.load();
    let n = r?.alwaysLast ? "afterExit" : "exit";
    return this.#t.on(n, t), () => {
      this.#t.removeListener(n, t), this.#t.listeners.exit.length === 0 && this.#t.listeners.afterExit.length === 0 && this.unload();
    };
  }
  load() {
    if (!this.#r) {
      this.#r = !0, this.#t.count += 1;
      for (let t of D)
        try {
          let r = this.#n[t];
          r && this.#e.on(t, r);
        } catch {
        }
      this.#e.emit = (t, ...r) => this.#c(t, ...r), this.#e.reallyExit = (t) => this.#a(t);
    }
  }
  unload() {
    this.#r && (this.#r = !1, D.forEach((t) => {
      let r = this.#n[t];
      if (!r)
        throw new Error("Listener not defined for signal: " + t);
      try {
        this.#e.removeListener(t, r);
      } catch {
      }
    }), this.#e.emit = this.#o, this.#e.reallyExit = this.#i, this.#t.count -= 1);
  }
  #a(t) {
    return Ue(this.#e) ? (this.#e.exitCode = t || 0, this.#t.emit("exit", this.#e.exitCode, null), this.#i.call(this.#e, this.#e.exitCode)) :
    0;
  }
  #c(t, ...r) {
    let n = this.#o;
    if (t === "exit" && Ue(this.#e)) {
      typeof r[0] == "number" && (this.#e.exitCode = r[0]);
      let i = n.call(this.#e, t, ...r);
      return this.#t.emit("exit", this.#e.exitCode, null), i;
    } else
      return n.call(this.#e, t, ...r);
  }
}, Vt = globalThis.process, {
  /**
   * Called when the process is exiting, whether via signal, explicit
   * exit, or running out of stuff to do.
   *
   * If the global process object is not suitable for instrumentation,
   * then this will be a no-op.
   *
   * Returns a function that may be used to unload signal-exit.
   */
  onExit: Mo,
  /**
   * Load the listeners.  Likely you never need to call this, unless
   * doing a rather deep integration with signal-exit functionality.
   * Mostly exposed for the benefit of testing.
   *
   * @internal
   */
  load: Ip,
  /**
   * Unload the listeners.  Likely you never need to call this, unless
   * doing a rather deep integration with signal-exit functionality.
   * Mostly exposed for the benefit of testing.
   *
   * @internal
   */
  unload: Cp
} = cu(Ue(Vt) ? new Ht(Vt) : new Wt());

// node_modules/execa/lib/kill.js
var uu = 1e3 * 5, Fo = /* @__PURE__ */ o((e, t = "SIGTERM", r = {}) => {
  let n = e(t);
  return lu(e, t, r, n), n;
}, "spawnedKill"), lu = /* @__PURE__ */ o((e, t, r, n) => {
  if (!fu(t, r, n))
    return;
  let i = du(r), s = setTimeout(() => {
    e("SIGKILL");
  }, i);
  s.unref && s.unref();
}, "setKillTimeout"), fu = /* @__PURE__ */ o((e, { forceKillAfterTimeout: t }, r) => pu(e) && t !== !1 && r, "shouldForceKill"), pu = /* @__PURE__ */ o(
(e) => e === Lo.default.constants.signals.SIGTERM || typeof e == "string" && e.toUpperCase() === "SIGTERM", "isSigterm"), du = /* @__PURE__ */ o(
({ forceKillAfterTimeout: e = !0 }) => {
  if (e === !0)
    return uu;
  if (!Number.isFinite(e) || e < 0)
    throw new TypeError(`Expected the \`forceKillAfterTimeout\` option to be a non-negative integer, got \`${e}\` (${typeof e})`);
  return e;
}, "getForceKillAfterTimeout"), Uo = /* @__PURE__ */ o((e, t) => {
  e.kill() && (t.isCanceled = !0);
}, "spawnedCancel"), mu = /* @__PURE__ */ o((e, t, r) => {
  e.kill(t), r(Object.assign(new Error("Timed out"), { timedOut: !0, signal: t }));
}, "timeoutKill"), $o = /* @__PURE__ */ o((e, { timeout: t, killSignal: r = "SIGTERM" }, n) => {
  if (t === 0 || t === void 0)
    return n;
  let i, s = new Promise((c, u) => {
    i = setTimeout(() => {
      mu(e, r, u);
    }, t);
  }), a = n.finally(() => {
    clearTimeout(i);
  });
  return Promise.race([s, a]);
}, "setupTimeout"), qo = /* @__PURE__ */ o(({ timeout: e }) => {
  if (e !== void 0 && (!Number.isFinite(e) || e < 0))
    throw new TypeError(`Expected the \`timeout\` option to be a non-negative integer, got \`${e}\` (${typeof e})`);
}, "validateTimeout"), Wo = /* @__PURE__ */ o(async (e, { cleanup: t, detached: r }, n) => {
  if (!t || r)
    return n;
  let i = Mo(() => {
    e.kill();
  });
  return n.finally(() => {
    i();
  });
}, "setExitHandler");

// node_modules/execa/lib/pipe.js
var Ho = require("node:fs"), Vo = require("node:child_process");

// node_modules/is-stream/index.js
function qe(e) {
  return e !== null && typeof e == "object" && typeof e.pipe == "function";
}
o(qe, "isStream");
function zt(e) {
  return qe(e) && e.writable !== !1 && typeof e._write == "function" && typeof e._writableState == "object";
}
o(zt, "isWritableStream");

// node_modules/execa/lib/pipe.js
var hu = /* @__PURE__ */ o((e) => e instanceof Vo.ChildProcess && typeof e.then == "function", "isExecaChildProcess"), Jt = /* @__PURE__ */ o(
(e, t, r) => {
  if (typeof r == "string")
    return e[t].pipe((0, Ho.createWriteStream)(r)), e;
  if (zt(r))
    return e[t].pipe(r), e;
  if (!hu(r))
    throw new TypeError("The second argument must be a string, a stream or an Execa child process.");
  if (!zt(r.stdin))
    throw new TypeError("The target child process's stdin must be available.");
  return e[t].pipe(r.stdin), r;
}, "pipeToTarget"), zo = /* @__PURE__ */ o((e) => {
  e.stdout !== null && (e.pipeStdout = Jt.bind(void 0, e, "stdout")), e.stderr !== null && (e.pipeStderr = Jt.bind(void 0, e, "stderr")), e.
  all !== void 0 && (e.pipeAll = Jt.bind(void 0, e, "all"));
}, "addPipeMethods");

// node_modules/execa/lib/stream.js
var Ke = require("node:fs"), ni = require("node:timers/promises");

// node_modules/get-stream/source/contents.js
var ae = /* @__PURE__ */ o(async (e, { init: t, convertChunk: r, getSize: n, truncateChunk: i, addChunk: s, getFinalChunk: a, finalize: c }, {
maxBuffer: u = Number.POSITIVE_INFINITY } = {}) => {
  if (!gu(e))
    throw new Error("The first argument must be a Readable, a ReadableStream, or an async iterable.");
  let l = t();
  l.length = 0;
  try {
    for await (let f of e) {
      let x = bu(f), g = r[x](f, l);
      Xo({ convertedChunk: g, state: l, getSize: n, truncateChunk: i, addChunk: s, maxBuffer: u });
    }
    return yu({ state: l, convertChunk: r, getSize: n, truncateChunk: i, addChunk: s, getFinalChunk: a, maxBuffer: u }), c(l);
  } catch (f) {
    throw f.bufferedData = c(l), f;
  }
}, "getStreamContents"), yu = /* @__PURE__ */ o(({ state: e, getSize: t, truncateChunk: r, addChunk: n, getFinalChunk: i, maxBuffer: s }) => {
  let a = i(e);
  a !== void 0 && Xo({ convertedChunk: a, state: e, getSize: t, truncateChunk: r, addChunk: n, maxBuffer: s });
}, "appendFinalChunk"), Xo = /* @__PURE__ */ o(({ convertedChunk: e, state: t, getSize: r, truncateChunk: n, addChunk: i, maxBuffer: s }) => {
  let a = r(e), c = t.length + a;
  if (c <= s) {
    Jo(e, t, i, c);
    return;
  }
  let u = n(e, s - t.length);
  throw u !== void 0 && Jo(u, t, i, s), new We();
}, "appendChunk"), Jo = /* @__PURE__ */ o((e, t, r, n) => {
  t.contents = r(e, t, n), t.length = n;
}, "addNewChunk"), gu = /* @__PURE__ */ o((e) => typeof e == "object" && e !== null && typeof e[Symbol.asyncIterator] == "function", "isAsyn\
cIterable"), bu = /* @__PURE__ */ o((e) => {
  let t = typeof e;
  if (t === "string")
    return "string";
  if (t !== "object" || e === null)
    return "others";
  if (globalThis.Buffer?.isBuffer(e))
    return "buffer";
  let r = Ko.call(e);
  return r === "[object ArrayBuffer]" ? "arrayBuffer" : r === "[object DataView]" ? "dataView" : Number.isInteger(e.byteLength) && Number.isInteger(
  e.byteOffset) && Ko.call(e.buffer) === "[object ArrayBuffer]" ? "typedArray" : "others";
}, "getChunkType"), { toString: Ko } = Object.prototype, We = class extends Error {
  static {
    o(this, "MaxBufferError");
  }
  name = "MaxBufferError";
  constructor() {
    super("maxBuffer exceeded");
  }
};

// node_modules/get-stream/source/utils.js
var Kt = /* @__PURE__ */ o((e) => e, "identity"), Xt = /* @__PURE__ */ o(() => {
}, "noop"), Yt = /* @__PURE__ */ o(({ contents: e }) => e, "getContentsProp"), He = /* @__PURE__ */ o((e) => {
  throw new Error(`Streams in object mode are not supported: ${String(e)}`);
}, "throwObjectStream"), Ve = /* @__PURE__ */ o((e) => e.length, "getLengthProp");

// node_modules/get-stream/source/array-buffer.js
async function Zt(e, t) {
  return ae(e, Iu, t);
}
o(Zt, "getStreamAsArrayBuffer");
var xu = /* @__PURE__ */ o(() => ({ contents: new ArrayBuffer(0) }), "initArrayBuffer"), Su = /* @__PURE__ */ o((e) => wu.encode(e), "useTex\
tEncoder"), wu = new TextEncoder(), Yo = /* @__PURE__ */ o((e) => new Uint8Array(e), "useUint8Array"), Zo = /* @__PURE__ */ o((e) => new Uint8Array(
e.buffer, e.byteOffset, e.byteLength), "useUint8ArrayWithOffset"), vu = /* @__PURE__ */ o((e, t) => e.slice(0, t), "truncateArrayBufferChunk"),
Pu = /* @__PURE__ */ o((e, { contents: t, length: r }, n) => {
  let i = ti() ? Tu(t, n) : Eu(t, n);
  return new Uint8Array(i).set(e, r), i;
}, "addArrayBufferChunk"), Eu = /* @__PURE__ */ o((e, t) => {
  if (t <= e.byteLength)
    return e;
  let r = new ArrayBuffer(ei(t));
  return new Uint8Array(r).set(new Uint8Array(e), 0), r;
}, "resizeArrayBufferSlow"), Tu = /* @__PURE__ */ o((e, t) => {
  if (t <= e.maxByteLength)
    return e.resize(t), e;
  let r = new ArrayBuffer(t, { maxByteLength: ei(t) });
  return new Uint8Array(r).set(new Uint8Array(e), 0), r;
}, "resizeArrayBuffer"), ei = /* @__PURE__ */ o((e) => Qo ** Math.ceil(Math.log(e) / Math.log(Qo)), "getNewContentsLength"), Qo = 2, ku = /* @__PURE__ */ o(
({ contents: e, length: t }) => ti() ? e : e.slice(0, t), "finalizeArrayBuffer"), ti = /* @__PURE__ */ o(() => "resize" in ArrayBuffer.prototype,
"hasArrayBufferResize"), Iu = {
  init: xu,
  convertChunk: {
    string: Su,
    buffer: Yo,
    arrayBuffer: Yo,
    dataView: Zo,
    typedArray: Zo,
    others: He
  },
  getSize: Ve,
  truncateChunk: vu,
  addChunk: Pu,
  getFinalChunk: Xt,
  finalize: ku
};

// node_modules/get-stream/source/buffer.js
async function ze(e, t) {
  if (!("Buffer" in globalThis))
    throw new Error("getStreamAsBuffer() is only supported in Node.js");
  try {
    return ri(await Zt(e, t));
  } catch (r) {
    throw r.bufferedData !== void 0 && (r.bufferedData = ri(r.bufferedData)), r;
  }
}
o(ze, "getStreamAsBuffer");
var ri = /* @__PURE__ */ o((e) => globalThis.Buffer.from(e), "arrayBufferToNodeBuffer");

// node_modules/get-stream/source/string.js
async function Qt(e, t) {
  return ae(e, Gu, t);
}
o(Qt, "getStreamAsString");
var Cu = /* @__PURE__ */ o(() => ({ contents: "", textDecoder: new TextDecoder() }), "initString"), Je = /* @__PURE__ */ o((e, { textDecoder: t }) => t.
decode(e, { stream: !0 }), "useTextDecoder"), Ou = /* @__PURE__ */ o((e, { contents: t }) => t + e, "addStringChunk"), Au = /* @__PURE__ */ o(
(e, t) => e.slice(0, t), "truncateStringChunk"), Ru = /* @__PURE__ */ o(({ textDecoder: e }) => {
  let t = e.decode();
  return t === "" ? void 0 : t;
}, "getFinalStringChunk"), Gu = {
  init: Cu,
  convertChunk: {
    string: Kt,
    buffer: Je,
    arrayBuffer: Je,
    dataView: Je,
    typedArray: Je,
    others: He
  },
  getSize: Ve,
  truncateChunk: Au,
  addChunk: Ou,
  getFinalChunk: Ru,
  finalize: Yt
};

// node_modules/execa/lib/stream.js
var oi = b(At(), 1);
var ii = /* @__PURE__ */ o((e) => {
  if (e !== void 0)
    throw new TypeError("The `input` and `inputFile` options cannot be both set.");
}, "validateInputOptions"), _u = /* @__PURE__ */ o(({ input: e, inputFile: t }) => typeof t != "string" ? e : (ii(e), (0, Ke.readFileSync)(t)),
"getInputSync"), si = /* @__PURE__ */ o((e) => {
  let t = _u(e);
  if (qe(t))
    throw new TypeError("The `input` option cannot be a stream in sync mode");
  return t;
}, "handleInputSync"), ju = /* @__PURE__ */ o(({ input: e, inputFile: t }) => typeof t != "string" ? e : (ii(e), (0, Ke.createReadStream)(t)),
"getInput"), ai = /* @__PURE__ */ o((e, t) => {
  let r = ju(t);
  r !== void 0 && (qe(r) ? r.pipe(e.stdin) : e.stdin.end(r));
}, "handleInput"), ci = /* @__PURE__ */ o((e, { all: t }) => {
  if (!t || !e.stdout && !e.stderr)
    return;
  let r = (0, oi.default)();
  return e.stdout && r.add(e.stdout), e.stderr && r.add(e.stderr), r;
}, "makeAllStream"), er = /* @__PURE__ */ o(async (e, t) => {
  if (!(!e || t === void 0)) {
    await (0, ni.setTimeout)(0), e.destroy();
    try {
      return await t;
    } catch (r) {
      return r.bufferedData;
    }
  }
}, "getBufferedData"), tr = /* @__PURE__ */ o((e, { encoding: t, buffer: r, maxBuffer: n }) => {
  if (!(!e || !r))
    return t === "utf8" || t === "utf-8" ? Qt(e, { maxBuffer: n }) : t === null || t === "buffer" ? ze(e, { maxBuffer: n }) : Nu(e, n, t);
}, "getStreamPromise"), Nu = /* @__PURE__ */ o(async (e, t, r) => (await ze(e, { maxBuffer: t })).toString(r), "applyEncoding"), ui = /* @__PURE__ */ o(
async ({ stdout: e, stderr: t, all: r }, { encoding: n, buffer: i, maxBuffer: s }, a) => {
  let c = tr(e, { encoding: n, buffer: i, maxBuffer: s }), u = tr(t, { encoding: n, buffer: i, maxBuffer: s }), l = tr(r, { encoding: n, buffer: i,
  maxBuffer: s * 2 });
  try {
    return await Promise.all([a, c, u, l]);
  } catch (f) {
    return Promise.all([
      { error: f, signal: f.signal, timedOut: f.timedOut },
      er(e, c),
      er(t, u),
      er(r, l)
    ]);
  }
}, "getSpawnedResult");

// node_modules/execa/lib/promise.js
var Bu = (async () => {
})().constructor.prototype, Du = ["then", "catch", "finally"].map((e) => [
  e,
  Reflect.getOwnPropertyDescriptor(Bu, e)
]), rr = /* @__PURE__ */ o((e, t) => {
  for (let [r, n] of Du) {
    let i = typeof t == "function" ? (...s) => Reflect.apply(n.value, t(), s) : n.value.bind(t);
    Reflect.defineProperty(e, r, { ...n, value: i });
  }
}, "mergePromise"), li = /* @__PURE__ */ o((e) => new Promise((t, r) => {
  e.on("exit", (n, i) => {
    t({ exitCode: n, signal: i });
  }), e.on("error", (n) => {
    r(n);
  }), e.stdin && e.stdin.on("error", (n) => {
    r(n);
  });
}), "getSpawnedPromise");

// node_modules/execa/lib/command.js
var di = require("node:buffer"), mi = require("node:child_process");
var hi = /* @__PURE__ */ o((e, t = []) => Array.isArray(t) ? [e, ...t] : [e], "normalizeArgs"), Mu = /^[\w.-]+$/, Lu = /* @__PURE__ */ o((e) => typeof e !=
"string" || Mu.test(e) ? e : `"${e.replaceAll('"', '\\"')}"`, "escapeArg"), nr = /* @__PURE__ */ o((e, t) => hi(e, t).join(" "), "joinComman\
d"), or = /* @__PURE__ */ o((e, t) => hi(e, t).map((r) => Lu(r)).join(" "), "getEscapedCommand"), yi = / +/g, gi = /* @__PURE__ */ o((e) => {
  let t = [];
  for (let r of e.trim().split(yi)) {
    let n = t.at(-1);
    n && n.endsWith("\\") ? t[t.length - 1] = `${n.slice(0, -1)} ${r}` : t.push(r);
  }
  return t;
}, "parseCommand"), fi = /* @__PURE__ */ o((e) => {
  let t = typeof e;
  if (t === "string")
    return e;
  if (t === "number")
    return String(e);
  if (t === "object" && e !== null && !(e instanceof mi.ChildProcess) && "stdout" in e) {
    let r = typeof e.stdout;
    if (r === "string")
      return e.stdout;
    if (di.Buffer.isBuffer(e.stdout))
      return e.stdout.toString();
    throw new TypeError(`Unexpected "${r}" stdout in template expression`);
  }
  throw new TypeError(`Unexpected "${t}" in template expression`);
}, "parseExpression"), pi = /* @__PURE__ */ o((e, t, r) => r || e.length === 0 || t.length === 0 ? [...e, ...t] : [
  ...e.slice(0, -1),
  `${e.at(-1)}${t[0]}`,
  ...t.slice(1)
], "concatTokens"), Fu = /* @__PURE__ */ o(({ templates: e, expressions: t, tokens: r, index: n, template: i }) => {
  let s = i ?? e.raw[n], a = s.split(yi).filter(Boolean), c = pi(
    r,
    a,
    s.startsWith(" ")
  );
  if (n === t.length)
    return c;
  let u = t[n], l = Array.isArray(u) ? u.map((f) => fi(f)) : [fi(u)];
  return pi(
    c,
    l,
    s.endsWith(" ")
  );
}, "parseTemplate"), ir = /* @__PURE__ */ o((e, t) => {
  let r = [];
  for (let [n, i] of e.entries())
    r = Fu({ templates: e, expressions: t, tokens: r, index: n, template: i });
  return r;
}, "parseTemplates");

// node_modules/execa/lib/verbose.js
var bi = require("node:util"), xi = b(require("node:process"), 1);
var Si = (0, bi.debuglog)("execa").enabled, Xe = /* @__PURE__ */ o((e, t) => String(e).padStart(t, "0"), "padField"), Uu = /* @__PURE__ */ o(
() => {
  let e = /* @__PURE__ */ new Date();
  return `${Xe(e.getHours(), 2)}:${Xe(e.getMinutes(), 2)}:${Xe(e.getSeconds(), 2)}.${Xe(e.getMilliseconds(), 3)}`;
}, "getTimestamp"), sr = /* @__PURE__ */ o((e, { verbose: t }) => {
  t && xi.default.stderr.write(`[${Uu()}] ${e}
`);
}, "logCommand");

// node_modules/execa/index.js
var $u = 1e3 * 1e3 * 100, qu = /* @__PURE__ */ o(({ env: e, extendEnv: t, preferLocal: r, localDir: n, execPath: i }) => {
  let s = t ? { ...ce.default.env, ...e } : e;
  return r ? Io({ env: s, cwd: n, execPath: i }) : s;
}, "getEnv"), Ti = /* @__PURE__ */ o((e, t, r = {}) => {
  let n = Ei.default._parse(e, t, r);
  return e = n.command, t = n.args, r = n.options, r = {
    maxBuffer: $u,
    buffer: !0,
    stripFinalNewline: !0,
    extendEnv: !0,
    preferLocal: !1,
    localDir: r.cwd || ce.default.cwd(),
    execPath: ce.default.execPath,
    encoding: "utf8",
    reject: !0,
    cleanup: !0,
    all: !1,
    windowsHide: !0,
    verbose: Si,
    ...r
  }, r.env = qu(r), r.stdio = Do(r), ce.default.platform === "win32" && Pi.default.basename(e, ".exe") === "cmd" && t.unshift("/q"), { file: e,
  args: t, options: r, parsed: n };
}, "handleArguments"), ue = /* @__PURE__ */ o((e, t, r) => typeof t != "string" && !vi.Buffer.isBuffer(t) ? r === void 0 ? void 0 : "" : e.stripFinalNewline ?
Dt(t) : t, "handleOutput");
function ki(e, t, r) {
  let n = Ti(e, t, r), i = nr(e, t), s = or(e, t);
  sr(s, n.options), qo(n.options);
  let a;
  try {
    a = Ye.default.spawn(n.file, n.args, n.options);
  } catch (m) {
    let y = new Ye.default.ChildProcess(), h = Promise.reject(se({
      error: m,
      stdout: "",
      stderr: "",
      all: "",
      command: i,
      escapedCommand: s,
      parsed: n,
      timedOut: !1,
      isCanceled: !1,
      killed: !1
    }));
    return rr(y, h), y;
  }
  let c = li(a), u = $o(a, n.options, c), l = Wo(a, n.options, u), f = { isCanceled: !1 };
  a.kill = Fo.bind(null, a.kill.bind(a)), a.cancel = Uo.bind(null, a, f);
  let g = Oo(/* @__PURE__ */ o(async () => {
    let [{ error: m, exitCode: y, signal: h, timedOut: S }, k, E, O] = await ui(a, n.options, l), G = ue(n.options, k), _ = ue(n.options, E),
    d = ue(n.options, O);
    if (m || y !== 0 || h !== null) {
      let v = se({
        error: m,
        exitCode: y,
        signal: h,
        stdout: G,
        stderr: _,
        all: d,
        command: i,
        escapedCommand: s,
        parsed: n,
        timedOut: S,
        isCanceled: f.isCanceled || (n.options.signal ? n.options.signal.aborted : !1),
        killed: a.killed
      });
      if (!n.options.reject)
        return v;
      throw v;
    }
    return {
      command: i,
      escapedCommand: s,
      exitCode: 0,
      stdout: G,
      stderr: _,
      all: d,
      failed: !1,
      timedOut: !1,
      isCanceled: !1,
      killed: !1
    };
  }, "handlePromise"));
  return ai(a, n.options), a.all = ci(a, n.options), zo(a), rr(a, g), a;
}
o(ki, "execa");
function Wu(e, t, r) {
  let n = Ti(e, t, r), i = nr(e, t), s = or(e, t);
  sr(s, n.options);
  let a = si(n.options), c;
  try {
    c = Ye.default.spawnSync(n.file, n.args, { ...n.options, input: a });
  } catch (f) {
    throw se({
      error: f,
      stdout: "",
      stderr: "",
      all: "",
      command: i,
      escapedCommand: s,
      parsed: n,
      timedOut: !1,
      isCanceled: !1,
      killed: !1
    });
  }
  let u = ue(n.options, c.stdout, c.error), l = ue(n.options, c.stderr, c.error);
  if (c.error || c.status !== 0 || c.signal !== null) {
    let f = se({
      stdout: u,
      stderr: l,
      error: c.error,
      signal: c.signal,
      exitCode: c.status,
      command: i,
      escapedCommand: s,
      parsed: n,
      timedOut: c.error && c.error.code === "ETIMEDOUT",
      isCanceled: !1,
      killed: c.signal !== null
    });
    if (!n.options.reject)
      return f;
    throw f;
  }
  return {
    command: i,
    escapedCommand: s,
    exitCode: 0,
    stdout: u,
    stderr: l,
    failed: !1,
    timedOut: !1,
    isCanceled: !1,
    killed: !1
  };
}
o(Wu, "execaSync");
var Hu = /* @__PURE__ */ o(({ input: e, inputFile: t, stdio: r }) => e === void 0 && t === void 0 && r === void 0 ? { stdin: "inherit" } : {},
"normalizeScriptStdin"), wi = /* @__PURE__ */ o((e = {}) => ({
  preferLocal: !0,
  ...Hu(e),
  ...e
}), "normalizeScriptOptions");
function Ii(e) {
  function t(r, ...n) {
    if (!Array.isArray(r))
      return Ii({ ...e, ...r });
    let [i, ...s] = ir(r, n);
    return ki(i, s, wi(e));
  }
  return o(t, "$"), t.sync = (r, ...n) => {
    if (!Array.isArray(r))
      throw new TypeError("Please use $(options).sync`command` instead of $.sync(options)`command`.");
    let [i, ...s] = ir(r, n);
    return Wu(i, s, wi(e));
  }, t;
}
o(Ii, "create$");
var _d = Ii();
function Ci(e, t) {
  let [r, ...n] = gi(e);
  return ki(r, n, t);
}
o(Ci, "execaCommand");

// src/telemetry/exec-command-count-lines.ts
async function Ze(e, t) {
  let r = Ci(e, { shell: !0, buffer: !1, ...t });
  if (!r.stdout)
    throw new Error("Unexpected missing stdout");
  let n = 0, i = (0, Oi.createInterface)(r.stdout);
  return i.on("line", () => {
    n += 1;
  }), await r, i.close(), n;
}
o(Ze, "execCommandCountLines");

// ../node_modules/slash/index.js
function ar(e) {
  return e.startsWith("\\\\?\\") ? e : e.replace(/\\/g, "/");
}
o(ar, "slash");

// src/common/utils/file-cache.ts
var le = require("node:crypto"), T = require("node:fs"), I = require("node:fs/promises"), Ai = require("node:os"), K = require("node:path");
var cr = class {
  static {
    o(this, "FileSystemCache");
  }
  constructor(t = {}) {
    this.prefix = (t.ns || t.prefix || "") + "-", this.hash_alg = t.hash_alg || "md5", this.cache_dir = t.basePath || (0, K.join)((0, Ai.tmpdir)(),
    (0, le.randomBytes)(15).toString("base64").replace(/\//g, "-")), this.ttl = t.ttl || 0, (0, le.createHash)(this.hash_alg), (0, T.mkdirSync)(
    this.cache_dir, { recursive: !0 });
  }
  generateHash(t) {
    return (0, K.join)(this.cache_dir, this.prefix + (0, le.createHash)(this.hash_alg).update(t).digest("hex"));
  }
  isExpired(t, r) {
    return t.ttl != null && r > t.ttl;
  }
  parseCacheData(t, r) {
    let n = JSON.parse(t);
    return this.isExpired(n, Date.now()) ? r : n.content;
  }
  parseSetData(t, r, n = {}) {
    let i = n.ttl ?? this.ttl;
    return JSON.stringify({ key: t, content: r, ...i && { ttl: Date.now() + i * 1e3 } });
  }
  async get(t, r) {
    try {
      let n = await (0, I.readFile)(this.generateHash(t), "utf8");
      return this.parseCacheData(n, r);
    } catch {
      return r;
    }
  }
  getSync(t, r) {
    try {
      let n = (0, T.readFileSync)(this.generateHash(t), "utf8");
      return this.parseCacheData(n, r);
    } catch {
      return r;
    }
  }
  async set(t, r, n = {}) {
    let i = typeof n == "number" ? { ttl: n } : n;
    (0, T.mkdirSync)(this.cache_dir, { recursive: !0 }), await (0, I.writeFile)(this.generateHash(t), this.parseSetData(t, r, i), {
      encoding: i.encoding || "utf8"
    });
  }
  setSync(t, r, n = {}) {
    let i = typeof n == "number" ? { ttl: n } : n;
    (0, T.mkdirSync)(this.cache_dir, { recursive: !0 }), (0, T.writeFileSync)(this.generateHash(t), this.parseSetData(t, r, i), {
      encoding: i.encoding || "utf8"
    });
  }
  async setMany(t, r) {
    await Promise.all(t.map((n) => this.set(n.key, n.content ?? n.value, r)));
  }
  setManySync(t, r) {
    t.forEach((n) => this.setSync(n.key, n.content ?? n.value, r));
  }
  async remove(t) {
    await (0, I.rm)(this.generateHash(t), { force: !0 });
  }
  removeSync(t) {
    (0, T.rmSync)(this.generateHash(t), { force: !0 });
  }
  async clear() {
    let t = await (0, I.readdir)(this.cache_dir);
    await Promise.all(
      t.filter((r) => r.startsWith(this.prefix)).map((r) => (0, I.rm)((0, K.join)(this.cache_dir, r), { force: !0 }))
    );
  }
  clearSync() {
    (0, T.readdirSync)(this.cache_dir).filter((t) => t.startsWith(this.prefix)).forEach((t) => (0, T.rmSync)((0, K.join)(this.cache_dir, t),
    { force: !0 }));
  }
  async getAll() {
    let t = Date.now(), r = await (0, I.readdir)(this.cache_dir);
    return (await Promise.all(
      r.filter((i) => i.startsWith(this.prefix)).map((i) => (0, I.readFile)((0, K.join)(this.cache_dir, i), "utf8"))
    )).map((i) => JSON.parse(i)).filter((i) => i.content && !this.isExpired(i, t));
  }
  async load() {
    return {
      files: (await this.getAll()).map((r) => ({
        path: this.generateHash(r.key),
        value: r.content,
        key: r.key
      }))
    };
  }
};
function ur(e) {
  return new cr(e);
}
o(ur, "createFileSystemCache");

// src/common/utils/resolve-path-in-sb-cache.ts
var dr = require("node:path");

// ../node_modules/find-cache-dir/index.js
var Wi = b(require("node:process"), 1), X = b(require("node:path"), 1), pe = b(require("node:fs"), 1), Hi = b(Gi(), 1);

// ../node_modules/pkg-dir/index.js
var Fi = b(require("node:path"), 1);

// ../node_modules/pkg-dir/node_modules/find-up/index.js
var fe = b(require("node:path"), 1), Mi = require("node:url");

// ../node_modules/locate-path/index.js
var _i = b(require("node:process"), 1), ji = b(require("node:path"), 1), Qe = b(require("node:fs"), 1), Ni = require("node:url");
var Bi = {
  directory: "isDirectory",
  file: "isFile"
};
function Ju(e) {
  if (!Object.hasOwnProperty.call(Bi, e))
    throw new Error(`Invalid type specified: ${e}`);
}
o(Ju, "checkType");
var Ku = /* @__PURE__ */ o((e, t) => t[Bi[e]](), "matchType"), Xu = /* @__PURE__ */ o((e) => e instanceof URL ? (0, Ni.fileURLToPath)(e) : e,
"toPath");
function lr(e, {
  cwd: t = _i.default.cwd(),
  type: r = "file",
  allowSymlinks: n = !0
} = {}) {
  Ju(r), t = Xu(t);
  let i = n ? Qe.default.statSync : Qe.default.lstatSync;
  for (let s of e)
    try {
      let a = i(ji.default.resolve(t, s), {
        throwIfNoEntry: !1
      });
      if (!a)
        continue;
      if (Ku(r, a))
        return s;
    } catch {
    }
}
o(lr, "locatePathSync");

// ../node_modules/pkg-dir/node_modules/path-exists/index.js
var Di = b(require("node:fs"), 1);

// ../node_modules/pkg-dir/node_modules/find-up/index.js
var Yu = /* @__PURE__ */ o((e) => e instanceof URL ? (0, Mi.fileURLToPath)(e) : e, "toPath"), Zu = Symbol("findUpStop");
function Qu(e, t = {}) {
  let r = fe.default.resolve(Yu(t.cwd) || ""), { root: n } = fe.default.parse(r), i = t.stopAt || n, s = t.limit || Number.POSITIVE_INFINITY,
  a = [e].flat(), c = /* @__PURE__ */ o((l) => {
    if (typeof e != "function")
      return lr(a, l);
    let f = e(l.cwd);
    return typeof f == "string" ? lr([f], l) : f;
  }, "runMatcher"), u = [];
  for (; ; ) {
    let l = c({ ...t, cwd: r });
    if (l === Zu || (l && u.push(fe.default.resolve(r, l)), r === i || u.length >= s))
      break;
    r = fe.default.dirname(r);
  }
  return u;
}
o(Qu, "findUpMultipleSync");
function Li(e, t = {}) {
  return Qu(e, { ...t, limit: 1 })[0];
}
o(Li, "findUpSync");

// ../node_modules/pkg-dir/index.js
function Ui({ cwd: e } = {}) {
  let t = Li("package.json", { cwd: e });
  return t && Fi.default.dirname(t);
}
o(Ui, "packageDirectorySync");

// ../node_modules/find-cache-dir/index.js
var { env: fr, cwd: el } = Wi.default, $i = /* @__PURE__ */ o((e) => {
  try {
    return pe.default.accessSync(e, pe.default.constants.W_OK), !0;
  } catch {
    return !1;
  }
}, "isWritable");
function qi(e, t) {
  return t.create && pe.default.mkdirSync(e, { recursive: !0 }), e;
}
o(qi, "useDirectory");
function tl(e) {
  let t = X.default.join(e, "node_modules");
  if (!(!$i(t) && (pe.default.existsSync(t) || !$i(X.default.join(e)))))
    return t;
}
o(tl, "getNodeModuleDirectory");
function pr(e = {}) {
  if (fr.CACHE_DIR && !["true", "false", "1", "0"].includes(fr.CACHE_DIR))
    return qi(X.default.join(fr.CACHE_DIR, e.name), e);
  let { cwd: t = el(), files: r } = e;
  if (r) {
    if (!Array.isArray(r))
      throw new TypeError(`Expected \`files\` option to be an array, got \`${typeof r}\`.`);
    t = (0, Hi.default)(r.map((i) => X.default.resolve(t, i)));
  }
  if (t = Ui({ cwd: t }), !(!t || !tl(t)))
    return qi(X.default.join(t, "node_modules", ".cache", e.name), e);
}
o(pr, "findCacheDirectory");

// src/common/utils/resolve-path-in-sb-cache.ts
function Vi(e, t = "default") {
  let r = pr({ name: "storybook" });
  return r ||= (0, dr.join)(process.cwd(), ".cache", "storybook"), (0, dr.join)(r, t, e);
}
o(Vi, "resolvePathInStorybookCache");

// src/telemetry/run-telemetry-operation.ts
var zi = ur({
  basePath: Vi("telemetry"),
  ns: "storybook",
  ttl: 24 * 60 * 60 * 1e3
  // 24h
}), et = /* @__PURE__ */ o(async (e, t) => {
  let r = await zi.get(e);
  return r === void 0 && (r = await t(), r !== void 0 && await zi.set(e, r)), r;
}, "runTelemetryOperation");

// src/telemetry/get-application-file-count.ts
var rl = ["page", "screen"], nl = ["js", "jsx", "ts", "tsx"], ol = /* @__PURE__ */ o(async (e) => {
  let r = rl.flatMap((n) => [
    n,
    [n[0].toUpperCase(), ...n.slice(1)].join("")
  ]).flatMap(
    (n) => nl.map((i) => `"${e}${Ji.sep}*${n}*.${i}"`)
  );
  try {
    let n = `git ls-files -- ${r.join(" ")}`;
    return await Ze(n);
  } catch {
    return;
  }
}, "getApplicationFilesCountUncached"), Ki = /* @__PURE__ */ o(async (e) => et(
  "applicationFiles",
  async () => ol(e)
), "getApplicationFileCount");

// src/telemetry/get-chromatic-version.ts
function Xi(e) {
  let t = e.dependencies?.chromatic || e.devDependencies?.chromatic || e.peerDependencies?.chromatic;
  return t || (e.scripts && Object.values(e.scripts).find((r) => r?.match(/chromatic/)) ? "latest" : void 0);
}
o(Xi, "getChromaticVersionSpecifier");

// src/telemetry/get-framework-info.ts
var es = require("node:path"), ts = require("@storybook/core/common");

// src/telemetry/package-json.ts
var Yi = require("node:fs/promises"), Zi = require("node:path");
var mr = /* @__PURE__ */ o(async (e) => {
  let t = Object.keys(e);
  return Promise.all(t.map(tt));
}, "getActualPackageVersions"), tt = /* @__PURE__ */ o(async (e) => {
  try {
    let t = await hr(e);
    return {
      name: e,
      version: t.version
    };
  } catch {
    return { name: e, version: null };
  }
}, "getActualPackageVersion"), hr = /* @__PURE__ */ o(async (e) => {
  let t = require.resolve((0, Zi.join)(e, "package.json"), {
    paths: [process.cwd()]
  });
  return JSON.parse(await (0, Yi.readFile)(t, { encoding: "utf8" }));
}, "getActualPackageJson");

// src/telemetry/get-framework-info.ts
var il = [
  "html",
  "react",
  "svelte",
  "vue3",
  "preact",
  "server",
  "vue",
  "web-components",
  "angular",
  "ember"
], sl = ["builder-webpack5", "builder-vite"];
function Qi(e, t) {
  let { name: r = "", version: n, dependencies: i, devDependencies: s, peerDependencies: a } = e, c = {
    // We include the framework itself because it may be a renderer too (e.g. angular)
    [r]: n,
    ...i,
    ...s,
    ...a
  };
  return t.map((u) => `@storybook/${u}`).find((u) => c[u]);
}
o(Qi, "findMatchingPackage");
var al = /* @__PURE__ */ o((e) => {
  let t = (0, es.normalize)(e).replace(new RegExp(/\\/, "g"), "/");
  return Object.keys(ts.frameworkPackages).find((n) => t.endsWith(n)) || Q(e).replace(/.*node_modules[\\/]/, "");
}, "getFrameworkPackageName");
async function rs(e) {
  if (!e?.framework)
    return {};
  let t = typeof e.framework == "string" ? e.framework : e.framework?.name;
  if (!t)
    return {};
  let r = await hr(t);
  if (!r)
    return {};
  let n = Qi(r, sl), i = Qi(r, il), s = al(t), a = typeof e.framework == "object" ? e.framework.options : {};
  return {
    framework: {
      name: s,
      options: a
    },
    builder: n,
    renderer: i
  };
}
o(rs, "getFrameworkInfo");

// src/telemetry/get-has-router-package.ts
var cl = /* @__PURE__ */ new Set([
  "react-router",
  "react-router-dom",
  "remix",
  "@tanstack/react-router",
  "expo-router",
  "@reach/router",
  "react-easy-router",
  "@remix-run/router",
  "wouter",
  "wouter-preact",
  "preact-router",
  "vue-router",
  "unplugin-vue-router",
  "@angular/router",
  "@solidjs/router",
  // metaframeworks that imply routing
  "next",
  "react-scripts",
  "gatsby",
  "nuxt",
  "@sveltejs/kit"
]);
function ns(e) {
  return Object.keys(e?.dependencies ?? {}).some(
    (t) => cl.has(t)
  );
}
o(ns, "getHasRouterPackage");

// src/telemetry/get-monorepo-type.ts
var de = require("node:fs"), rt = require("node:path"), is = require("@storybook/core/common");
var os = {
  Nx: "nx.json",
  Turborepo: "turbo.json",
  Lerna: "lerna.json",
  Rush: "rush.json",
  Lage: "lage.config.json"
}, ss = /* @__PURE__ */ o(() => {
  let e = (0, is.getProjectRoot)();
  if (!e)
    return;
  let r = Object.keys(os).find((i) => {
    let s = (0, rt.join)(e, os[i]);
    return (0, de.existsSync)(s);
  });
  if (r)
    return r;
  if (!(0, de.existsSync)((0, rt.join)(e, "package.json")))
    return;
  if (JSON.parse(
    (0, de.readFileSync)((0, rt.join)(e, "package.json"), { encoding: "utf8" })
  )?.workspaces)
    return "Workspaces";
}, "getMonorepoType");

// src/telemetry/get-portable-stories-usage.ts
var ul = /* @__PURE__ */ o(async (e) => {
  try {
    let t = "git grep -l composeStor" + (e ? ` -- ${e}` : "");
    return await Ze(t);
  } catch (t) {
    return t.exitCode === 1 ? 0 : void 0;
  }
}, "getPortableStoriesFileCountUncached"), as = /* @__PURE__ */ o(async (e) => et(
  "portableStories",
  async () => ul(e)
), "getPortableStoriesFileCount");

// src/telemetry/storybook-metadata.ts
var yr = {
  next: "Next",
  "react-scripts": "CRA",
  gatsby: "Gatsby",
  "@nuxtjs/storybook": "nuxt",
  "@nrwl/storybook": "nx",
  "@vue/cli-service": "vue-cli",
  "@sveltejs/kit": "sveltekit"
}, gr = /* @__PURE__ */ o((e) => Q(e).replace(/\/dist\/.*/, "").replace(/\.[mc]?[tj]?s[x]?$/, "").replace(/\/register$/, "").replace(/\/manager$/,
"").replace(/\/preset$/, ""), "sanitizeAddonName"), ls = /* @__PURE__ */ o(async ({
  packageJsonPath: e,
  packageJson: t,
  mainConfig: r
}) => {
  let n = {
    generatedAt: (/* @__PURE__ */ new Date()).getTime(),
    hasCustomBabel: !1,
    hasCustomWebpack: !1,
    hasStaticDirs: !1,
    hasStorybookEslint: !1,
    refCount: 0
  }, i = {
    ...t?.dependencies,
    ...t?.devDependencies,
    ...t?.peerDependencies
  }, s = Object.keys(i).find((d) => !!yr[d]);
  if (s) {
    let { version: d } = await tt(s);
    n.metaFramework = {
      name: yr[s],
      packageName: s,
      version: d
    };
  }
  let a = [
    "playwright",
    "vitest",
    "jest",
    "cypress",
    "nightwatch",
    "webdriver",
    "@web/test-runner",
    "puppeteer",
    "karma",
    "jasmine",
    "chai",
    "testing-library",
    "@ngneat/spectator",
    "wdio",
    "msw",
    "miragejs",
    "sinon"
  ], c = Object.keys(i).filter(
    (d) => a.find((v) => d.includes(v))
  );
  n.testPackages = Object.fromEntries(
    await Promise.all(
      c.map(async (d) => [d, (await tt(d))?.version])
    )
  ), n.hasRouterPackage = ns(t);
  let u = ss();
  u && (n.monorepo = u);
  try {
    let d = await (0, ot.detect)({ cwd: (0, A.getProjectRoot)() }), v = await (0, ot.getNpmVersion)(d);
    n.packageManager = {
      type: d,
      version: v
    };
  } catch {
  }
  n.hasCustomBabel = !!r.babel, n.hasCustomWebpack = !!r.webpackFinal, n.hasStaticDirs = !!r.staticDirs, typeof r.typescript == "object" && (n.
  typescriptOptions = r.typescript);
  let l = await rs(r);
  typeof r.refs == "object" && (n.refCount = Object.keys(r.refs).length), typeof r.features == "object" && (n.features = r.features);
  let f = {};
  r.addons && r.addons.forEach((d) => {
    let v, ye;
    typeof d == "string" ? v = gr(d) : (d.name.includes("addon-essentials") && (ye = d.options), v = gr(d.name)), f[v] = {
      options: ye,
      version: void 0
    };
  });
  let x = Xi(t);
  x && (f.chromatic = {
    version: void 0,
    versionSpecifier: x,
    options: void 0
  }), (await mr(f)).forEach(({ name: d, version: v }) => {
    f[d].version = v;
  });
  let m = Object.keys(f), y = Object.keys(i).filter((d) => d.includes("storybook") && !m.includes(d)).reduce((d, v) => ({
    ...d,
    [v]: { version: void 0 }
  }), {});
  (await mr(y)).forEach(({ name: d, version: v }) => {
    y[d].version = v;
  });
  let S = i.typescript ? "typescript" : "javascript", k = !!i["eslint-plugin-storybook"], E = (0, A.getStorybookInfo)(t);
  try {
    let { previewConfig: d } = E;
    if (d) {
      let v = await (0, us.readConfig)(d), ye = !!(v.getFieldNode(["globals"]) || v.getFieldNode(["globalTypes"]));
      n.preview = { ...n.preview, usesGlobals: ye };
    }
  } catch {
  }
  let O = y[E.frameworkPackage]?.version, G = await as(), _ = await Ki((0, cs.dirname)(e));
  return {
    ...n,
    ...l,
    portableStoriesFileCount: G,
    applicationFileCount: _,
    storybookVersion: O,
    storybookVersionSpecifier: E.version,
    language: S,
    storybookPackages: y,
    addons: f,
    hasStorybookEslint: k
  };
}, "computeStorybookMetadata");
async function ll() {
  let e = await Bt(process.cwd());
  return e ? {
    packageJsonPath: e,
    packageJson: await To(e) || {}
  } : {
    packageJsonPath: process.cwd(),
    packageJson: {}
  };
}
o(ll, "getPackageJsonDetails");
var nt, br = /* @__PURE__ */ o(async (e) => {
  if (nt)
    return nt;
  let { packageJson: t, packageJsonPath: r } = await ll(), n = (e || (0, A.getStorybookConfiguration)(
    String(t?.scripts?.storybook || ""),
    "-c",
    "--config-dir"
  )) ?? ".storybook", i = await (0, A.loadMainConfig)({ configDir: n });
  return nt = await ls({ mainConfig: i, packageJson: t, packageJsonPath: r }), nt;
}, "getStorybookMetadata");

// src/telemetry/telemetry.ts
var Ps = b(require("node:os"), 1), Es = b(ps(), 1);

// ../node_modules/nanoid/index.js
var xr = require("crypto");

// ../node_modules/nanoid/url-alphabet/index.js
var ds = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict";

// ../node_modules/nanoid/index.js
var fl = 128, M, Y, pl = /* @__PURE__ */ o((e) => {
  !M || M.length < e ? (M = Buffer.allocUnsafe(e * fl), (0, xr.randomFillSync)(M), Y = 0) : Y + e > M.length && ((0, xr.randomFillSync)(M), Y =
  0), Y += e;
}, "fillPool");
var me = /* @__PURE__ */ o((e = 21) => {
  pl(e -= 0);
  let t = "";
  for (let r = Y - e; r < Y; r++)
    t += ds[M[r] & 63];
  return t;
}, "nanoid");

// src/telemetry/anonymous-id.ts
var hs = require("node:path"), ys = require("@storybook/core/common"), gs = require("child_process");

// src/telemetry/one-way-hash.ts
var ms = require("crypto");
var st = /* @__PURE__ */ o((e) => {
  let t = (0, ms.createHash)("sha256");
  return t.update("storybook-telemetry-salt"), t.update(e), t.digest("hex");
}, "oneWayHash");

// src/telemetry/anonymous-id.ts
function dl(e) {
  let n = e.trim().replace(/#.*$/, "").replace(/^.*@/, "").replace(/^.*\/\//, "");
  return (n.endsWith(".git") ? n : `${n}.git`).replace(":", "/");
}
o(dl, "normalizeGitUrl");
function ml(e, t) {
  return `${dl(e)}${ar(t)}`;
}
o(ml, "unhashedProjectId");
var at, bs = /* @__PURE__ */ o(() => {
  if (at)
    return at;
  try {
    let e = (0, ys.getProjectRoot)(), t = (0, hs.relative)(e, process.cwd()), r = (0, gs.execSync)("git config --local --get remote.origin.u\
rl", {
      timeout: 1e3,
      stdio: "pipe"
    });
    at = st(ml(String(r), t));
  } catch {
  }
  return at;
}, "getAnonymousProjectId");

// src/telemetry/event-cache.ts
var ct = require("@storybook/core/common");
var Sr = Promise.resolve(), hl = /* @__PURE__ */ o(async (e, t) => {
  let r = await ct.cache.get("lastEvents") || {};
  r[e] = { body: t, timestamp: Date.now() }, await ct.cache.set("lastEvents", r);
}, "setHelper"), Ss = /* @__PURE__ */ o(async (e, t) => (await Sr, Sr = hl(e, t), Sr), "set");
var yl = /* @__PURE__ */ o((e) => {
  let { body: t, timestamp: r } = e;
  return {
    timestamp: r,
    eventType: t?.eventType,
    eventId: t?.eventId,
    sessionId: t?.sessionId
  };
}, "upgradeFields"), gl = ["init", "upgrade"], bl = ["build", "dev", "error"], xs = /* @__PURE__ */ o((e, t) => {
  let r = t.map((n) => e?.[n]).filter(Boolean).sort((n, i) => i.timestamp - n.timestamp);
  return r.length > 0 ? r[0] : void 0;
}, "lastEvent"), ws = /* @__PURE__ */ o(async (e = void 0) => {
  let t = e || await ct.cache.get("lastEvents") || {}, r = xs(t, gl), n = xs(t, bl);
  if (r)
    return !n?.timestamp || r.timestamp > n.timestamp ? yl(r) : void 0;
}, "getPrecedingUpgrade");

// src/telemetry/fetch.ts
var vs = global.fetch;

// src/telemetry/session-id.ts
var wr = require("@storybook/core/common");
var xl = 1e3 * 60 * 60 * 2, he;
var vr = /* @__PURE__ */ o(async () => {
  let e = Date.now();
  if (!he) {
    let t = await wr.cache.get("session");
    t && t.lastUsed >= e - xl ? he = t.id : he = me();
  }
  return await wr.cache.set("session", { id: he, lastUsed: e }), he;
}, "getSessionId");

// src/telemetry/telemetry.ts
var Sl = (0, Es.default)(vs), wl = process.env.STORYBOOK_TELEMETRY_URL || "https://storybook.js.org/event-log", ut = [], Ts = /* @__PURE__ */ o(
(e, t) => {
  Pr[e] = t;
}, "addToGlobalContext"), vl = /* @__PURE__ */ o(() => {
  try {
    let e = Ps.platform();
    return e === "win32" ? "Windows" : e === "darwin" ? "macOS" : e === "linux" ? "Linux" : `Other: ${e}`;
  } catch {
    return "Unknown";
  }
}, "getOperatingSystem"), Pr = {
  inCI: !!process.env.CI,
  isTTY: process.stdout.isTTY,
  platform: vl(),
  nodeVersion: process.versions.node
}, Pl = /* @__PURE__ */ o(async (e, t, r) => {
  let { eventType: n, payload: i, metadata: s, ...a } = e, c = await vr(), u = me(), l = { ...a, eventType: n, eventId: u, sessionId: c, metadata: s,
  payload: i, context: t };
  return Sl(wl, {
    method: "post",
    body: JSON.stringify(l),
    headers: { "Content-Type": "application/json" },
    retries: 3,
    retryOn: [503, 504],
    retryDelay: /* @__PURE__ */ o((f) => 2 ** f * (typeof r?.retryDelay == "number" && !Number.isNaN(r?.retryDelay) ? r.retryDelay : 1e3), "\
retryDelay")
  });
}, "prepareRequest");
async function ks(e, t = { retryDelay: 1e3, immediate: !1 }) {
  let { eventType: r, payload: n, metadata: i, ...s } = e, a = t.stripMetadata ? Pr : {
    ...Pr,
    anonymousId: bs()
  }, c;
  try {
    c = Pl(e, a, t), ut.push(c), t.immediate ? await Promise.all(ut) : await c;
    let u = await vr(), l = me(), f = { ...s, eventType: r, eventId: l, sessionId: u, metadata: i, payload: n, context: a };
    await Ss(r, f);
  } catch {
  } finally {
    ut = ut.filter((u) => u !== c);
  }
}
o(ks, "sendTelemetry");

// src/telemetry/index.ts
var El = /* @__PURE__ */ o((e) => e.startsWith("example-button--") || e.startsWith("example-header--") || e.startsWith("example-page--"), "i\
sExampleStoryId"), Tl = /* @__PURE__ */ o(async (e, t = {}, r = {}) => {
  e !== "boot" && await Ar();
  let n = {
    eventType: e,
    payload: t
  };
  try {
    r?.stripMetadata || (n.metadata = await br(r?.configDir));
  } catch (i) {
    n.payload.metadataErrorMessage = xe(i).message, r?.enableCrashReports && (n.payload.metadataError = xe(i));
  } finally {
    let { error: i } = n.payload;
    i && (n.payload.error = xe(i)), (!n.payload.error || r?.enableCrashReports) && (process.env?.STORYBOOK_TELEMETRY_DEBUG && (Er.logger.info(
    `
[telemetry]`), Er.logger.info(JSON.stringify(n, null, 2))), await ks(n, r));
  }
}, "telemetry");
