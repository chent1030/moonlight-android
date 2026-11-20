#!/bin/bash
# 一键构建「预览版」CATL投屏 APK（无需本地 NDK）
# 原理：临时注释 externalNativeBuild，用 vendor/native-libs/ 里官方版预编译的
#       libmoonlight-core.so 代替 ndkBuild 产物，构建完成后自动还原。
# 产物：app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
set -e
cd "$(dirname "$0")/.."
APP_GRADLE="app/build.gradle"

if [ ! -d vendor/native-libs ]; then
    echo "错误：vendor/native-libs 缺失（需要官方 APK 里的 libmoonlight-core.so）"
    exit 1
fi

restore() {
python3 - "$APP_GRADLE" <<'PYEOF'
import sys
p = sys.argv[1]
lines = open(p).read().split('\n')
out, i, hits = [], 0, 0
while i < len(lines):
    s = lines[i].lstrip()
    if s.startswith('//') and s[2:].strip() == 'externalNativeBuild {':
        hits += 1; depth = 0
        while i < len(lines):
            out.append(lines[i].replace('//', '', 1))
            depth += lines[i].count('{') - lines[i].count('}')
            i += 1
            if depth == 0: break
    else:
        out.append(lines[i]); i += 1
open(p, 'w').write('\n'.join(out))
print('gradle restored:', hits)
PYEOF
}

trap restore EXIT

# 1) 临时注释 externalNativeBuild（3 处）
python3 - "$APP_GRADLE" <<'PYEOF'
import sys
p = sys.argv[1]
lines = open(p).read().split('\n')
out, i, hits = [], 0, 0
while i < len(lines):
    if lines[i].strip() == 'externalNativeBuild {':
        hits += 1; depth = 0
        while i < len(lines):
            depth += lines[i].count('{') - lines[i].count('}')
            out.append('//' + lines[i]); i += 1
            if depth == 0: break
    else:
        out.append(lines[i]); i += 1
open(p, 'w').write('\n'.join(out))
print('ndkBuild disabled:', hits)
PYEOF

# 2) 预置 so
rm -rf app/src/main/jniLibs
mkdir -p app/src/main/jniLibs
cp -R vendor/native-libs/* app/src/main/jniLibs/

# 3) 构建
./gradlew assembleNonRootDebug

# 4) 提醒 so 打包核验
echo "核验 so 打包："
unzip -l app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk | grep -c "\.so" | xargs -I{} echo "  APK 内 .so 数量: {} (应为 4)"
echo "产物: app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk"
