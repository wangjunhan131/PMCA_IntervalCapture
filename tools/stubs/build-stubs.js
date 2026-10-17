// 构建索尼 PMCA 私有 API 的“编译期桩” jar。
// 仅在缺少官方 Camera Apps SDK 时使用；正式部署请把官方 SDK 的 jar 放进 app/libs。
// 本文件已随仓库归档到 tools/stubs/，修复过一次真实缺陷：
//   - jasmin 里 public static 的方法，旧版生成器会丢掉 static，导致运行时 NoSuchMethodError；
//   - jasmin 里没有 <init> 的类，旧版生成器凭空生成公开构造器，导致 new 出来即崩。
// 现在两个修饰符/构造器都严格按 jasmin 还原。
//
// 用法（在仓库根目录执行，先 export PMCA_STUB_ROOT 指向放 jasmin 的目录）：
//   node build-support/libs/sony-stubs-src/jasmin2java.js build-support/libs/sony-stubs-src build-support/libs/sony-stubs-java
//   javac -encoding UTF-8 -source 8 -target 8 -cp build-support/libs/android-4.1.1.4.jar `
//         -d build-support/libs/sony-stubs-classes build-support/libs/sony-stubs-java/*.java
//   jar --create --file build-support/libs/sony-pmca-stubs.jar -C build-support/libs/sony-stubs-classes .
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = process.env.PMCA_STUB_ROOT ? path.resolve(process.env.PMCA_STUB_ROOT) : path.resolve(__dirname);
const stubSrc = path.join(root, 'sony-stubs-src');
const stubJava = path.join(root, 'sony-stubs-java');
const stubClasses = path.join(root, 'sony-stubs-classes');
const androidJar = process.env.ANDROID_JAR
    || path.resolve(__dirname, '../../app/libs/android-4.1.1.4.jar');
const outJar = process.env.STUB_OUT_JAR || path.join(root, 'sony-pmca-stubs.jar');

function rm(p) { if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true }); }

rm(stubJava);
rm(stubClasses);
execFileSync(process.execPath, [path.join(stubSrc, 'jasmin2java.js'), stubSrc, stubJava], { stdio: 'inherit' });

fs.mkdirSync(stubClasses, { recursive: true });
const files = fs.readdirSync(stubJava).filter(f => f.endsWith('.java')).map(f => path.join(stubJava, f));
execFileSync('javac', ['-nowarn', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
  '-cp', androidJar, '-d', stubClasses].concat(files), { stdio: 'inherit' });

if (fs.existsSync(outJar)) fs.rmSync(outJar);
execFileSync('jar', ['--create', '--file', outJar, '-C', stubClasses, '.'], { stdio: 'inherit' });
console.log('OK -> ' + outJar);
