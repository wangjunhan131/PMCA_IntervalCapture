// 把 Sony PMCA jasmin 桩（*.j）转换为可编译的 Java 桩源码。
// 用法：node jasmin2java.js <jasminDir> <outJavaDir>
// 说明：仅用于本机自测编译（没有官方 Camera Apps SDK 时生成同签名桩类）。
const fs = require('fs');
const path = require('path');

const srcDir = process.argv[2];
const outDir = process.argv[3];
if (!srcDir || !outDir) {
  console.error('usage: node jasmin2java.js <jasminDir> <outJavaDir>');
  process.exit(1);
}
fs.mkdirSync(outDir, { recursive: true });

const extraInner = {
  CameraEx: ['OpenOptions', 'ParametersModifier', 'LensInfo', 'ShutterSpeedInfo', 'ApertureInfo'],
  Temperature: ['CountDownInfoListener', 'StatusCallback']
};

function fixName(n) { return n.split('$').join('.').split('/').join('.'); }

function parseType(t) {
  if (t[0] !== '[' && 'IJZBCSFDVL'.indexOf(t[0]) < 0) return fixName(t);
  let dim = 0;
  while (t[dim] === '[') dim++;
  const base = t.slice(dim);
  const map = { I: 'int', Z: 'boolean', B: 'byte', C: 'char', S: 'short', J: 'long', F: 'float', D: 'double', V: 'void' };
  const name = base[0] === 'L' ? fixName(base.slice(1, -1)) : map[base];
  if (!name) throw new Error('unknown type ' + t);
  return name + '[]'.repeat(dim);
}

function parseDesc(desc) {
  const i1 = desc.indexOf('('), i2 = desc.indexOf(')', i1);
  const argsStr = desc.slice(i1 + 1, i2), args = [];
  let i = 0;
  while (i < argsStr.length) {
    const st = i;
    while (argsStr[i] === '[') i++;
    if (argsStr[i] === 'L') { while (argsStr[i] !== ';') i++; i++; } else i++;
    args.push(argsStr.slice(st, i));
  }
  return { args, ret: desc.slice(i2 + 1) };
}

const entries = [];
for (const f of fs.readdirSync(srcDir).filter(x => x.endsWith('.j'))) {
  const text = fs.readFileSync(path.join(srcDir, f), 'utf8');
  let className = null, isIface = false, superName = 'java.lang.Object';
  const fields = [], methods = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.slice(0, 6) === '.class') {
      if (line.indexOf('interface') >= 0) isIface = true;
      className = line.split(/\s+/).pop();
    } else if (line.slice(0, 6) === '.super') {
      superName = parseType(line.split(/\s+/)[1]);
    } else if (line.slice(0, 6) === '.field') {
      const m = line.match(/^\.field\s+((?:[A-Za-z]+\s+)*)([A-Za-z0-9_$]+)\s+(\S+)(?:\s*=\s*(.*))?$/);
      if (m) {
        // ★ 关键：jasmin 的字段修饰符（static / final）必须保留。
        //   真实固件里 CameraEx$StoreImageInfo 的 FileName 等字段是【实例字段】，
        //   若误生成为 static，运行时读取会抛 NoSuchFieldError。
        const mods = m[1].trim().split(/\s+/).filter(Boolean);
        fields.push({
          name: m[2],
          type: parseType(m[3]),
          value: m[4] ? m[4].trim() : null,
          isStatic: mods.indexOf('static') >= 0,
          isFinal: mods.indexOf('final') >= 0
        });
      }
    } else if (line.slice(0, 7) === '.method') {
      const m = line.match(/^\.method\s+((?:[A-Za-z]+\s+)*)([A-Za-z0-9_$<>]+)(\(.*)$/);
      if (m && m[3].indexOf(')') >= 0) {
        // ★ 关键：方法修饰符必须保留。
        //   CameraEx.open(int, OpenOptions) 在真实固件里是 public static，
        //   若误生成为实例方法，调用方会编译出 invokevirtual，运行时抛 NoSuchMethodError。
        const mods = m[1].trim().split(/\s+/).filter(Boolean);
        methods.push({
          name: m[2],
          desc: m[3],
          isStatic: mods.indexOf('static') >= 0,
          isAbstract: mods.indexOf('abstract') >= 0,
          isFinal: mods.indexOf('final') >= 0
        });
      }
    }
  }
  if (!className) continue;
  const parts = className.split('/');
  const packed = parts.pop();
  const segs = packed.replace(/\$/g, '.').split('.');
  entries.push({
    pkg: parts.join('.'),
    top: segs[0],
    nested: segs.length > 1 ? segs.slice(1).join('.') : null,
    isIface, superName, fields, methods
  });
}

function defaultValue(type) {
  return { int: '0', long: '0L', float: '0f', double: '0d', short: '(short)0', byte: '(byte)0', char: '0', boolean: 'false' }[type] || 'null';
}

function renderFields(e) {
  const out = [];
  for (const fl of e.fields) {
    let expr;
    if (fl.type === 'String' || fl.type === 'java.lang.String') expr = fl.value === null ? 'null' : JSON.stringify(fl.value);
    else if (fl.type === 'boolean') expr = fl.value === 'true' ? 'true' : 'false';
    else if (fl.value === null) expr = defaultValue(fl.type);
    else expr = fl.value + ({ long: 'L', float: 'f' }[fl.type] || '');
    if (fl.isStatic) {
      // 真实固件里的静态字段：static final 自带初值
      out.push('  public static' + (fl.isFinal ? ' final' : '') + ' ' + fl.type + ' ' + fl.name + ' = ' + expr + ';');
    } else {
      // 真实固件里的实例字段（例如 CameraEx$StoreImageInfo.FileName）：
      // 必须生成实例字段，否则调用方会被编译成 getstatic，运行时 NoSuchFieldError
      out.push('  public ' + fl.type + ' ' + fl.name + ';');
    }
  }
  return out;
}

function renderMethods(e, simple) {
  const out = [];
  for (const me of e.methods) {
    if (me.name === '<clinit>' || me.name.slice(0, 1) === '<') continue;
    const { args, ret } = parseDesc(me.desc);
    const argStr = args.map((t, k) => parseType(t) + ' a' + k).join(', ');
    if (e.isIface) {
      out.push('  ' + parseType(ret) + ' ' + me.name + '(' + argStr + ');');
    } else {
      // ★ static 必须保留（CameraEx.open 就是 static 工厂方法）
      out.push('  public ' + (me.isStatic ? 'static ' : '') + parseType(ret) + ' ' + me.name + '(' + argStr + ') { throw new UnsupportedOperationException(); }');
    }
  }
  return out;
}

// 构造方法：jasmin 里有 <init> 就生成同名公开构造器；
// 没有任何 <init> 的类（例如 CameraEx 本身）生成 private 构造器，
// 让“误用 new 创建实例”在编译期就报错，而不是运行时 NoSuchMethodError。
function renderCtors(e, simple) {
  const defaults = [];
  let hasInit = false;
  for (const me of e.methods) {
    if (me.name !== '<init>') continue;
    hasInit = true;
    const { args } = parseDesc(me.desc);
    defaults.push('  public ' + simple + '(' + args.map((t, k) => parseType(t) + ' a' + k).join(', ') + ') {}');
  }
  if (!hasInit && !e.isIface) {
    defaults.push('  private ' + simple + '() {}');
  }
  return defaults;
}

const tops = new Map();
for (const e of entries) {
  const key = e.pkg + '|' + e.top;
  if (!tops.has(key)) tops.set(key, { pkg: e.pkg, top: e.top, root: null, inners: [] });
  const t = tops.get(key);
  if (e.nested === null) { if (!t.root) t.root = e; } else { t.inners.push(e); }
}

function collectNested(e, prefix, self) {
  const found = [];
  const scan = (t) => {
    const n = parseType(t).split('[]').join('');
    if (n.slice(0, prefix.length) !== prefix) return;
    const rest = n.slice(prefix.length);
    if (rest !== self && rest.indexOf('.') < 0 && found.indexOf(rest) < 0) found.push(rest);
  };
  for (const me of e.methods) { const d = parseDesc(me.desc); d.args.forEach(scan); scan(d.ret); }
  for (const fl of e.fields) scan(fl.type);
  return found;
}

for (const t of tops.values()) {
  if (!t.root) continue;
  const pkg = t.pkg, top = t.top, root = t.root;
  const out = ['package ' + pkg + ';', '// compile-time stub (generated) - not packaged into the APK', '@SuppressWarnings("all")'];
  out.push('public ' + (root.isIface ? 'interface ' : 'class ') + top + (root.isIface ? '' : ' extends ' + root.superName) + ' {');
  const existing = t.inners.map(i => i.nested.split('.')[0]);
  const prefix = pkg + '.' + top + '.';
  const refs = [];
  const addRef = (n) => { if (existing.indexOf(n) < 0 && refs.indexOf(n) < 0) refs.push(n); };
  collectNested(root, prefix, top).forEach(addRef);
  t.inners.forEach(i => collectNested(i, prefix, top).forEach(addRef));
  (extraInner[top] || []).forEach(addRef);
  refs.forEach(n => out.push('  public static class ' + n + ' { public ' + n + '() {} }'));
  renderFields(root).forEach(l => out.push(l));
  renderCtors(root, top).forEach(l => out.push(l));
  renderMethods(root, top).forEach(l => out.push(l));
  const seen = {};
  for (const inner of t.inners) {
    const short = inner.nested.split('.')[0];
    if (seen[short]) continue;
    seen[short] = true;
    if (inner.isIface) {
      out.push('  public static interface ' + short + ' {');
      renderFields(inner).forEach(l => out.push(l));
      renderCtors(inner, short).forEach(l => out.push(l));
      renderMethods(inner, short).forEach(l => out.push(l));
      out.push('  }');
    } else {
      out.push('  public static class ' + short + (inner.superName === 'java.lang.Object' ? '' : ' extends ' + inner.superName) + ' {');
      renderFields(inner).forEach(l => out.push(l));
      renderCtors(inner, short).forEach(l => out.push(l));
      renderMethods(inner, short).forEach(l => out.push(l));
      out.push('  }');
    }
  }
  out.push('}');
  fs.writeFileSync(path.join(outDir, top + '.java'), out.join('\n') + '\n', 'utf8');
  console.log('generated ' + pkg + '.' + top + ' inners=[' + Object.keys(seen).join(',') + ']');
}
