#!/usr/bin/env bash
# 把 sdkmanager/官方zip 解压出的 android-37 平台目录的 source.properties
# 修补为 AGP 可识别的稳定包属性（对齐本地真实 platform-37 安装包），并补 package.xml
set -e
TARGET="${1:?usage: patch_android37.sh <platforms-dir>}"
PLATFORM_DIR="$TARGET/android-37"

if [ ! -f "$PLATFORM_DIR/source.properties" ]; then
  echo "no android-37 platform dir: $PLATFORM_DIR" >&2
  exit 1
fi

cat > "$PLATFORM_DIR/source.properties" <<'SRCPROP'
Pkg.Desc=Android SDK Platform 17
Pkg.UserSrc=false
Platform.Version=17
Platform.CodeName=
Pkg.Revision=2
AndroidVersion.ApiLevel=37
AndroidVersion.CodeName=
AndroidVersion.ExtensionLevel=22
AndroidVersion.IsBaseSdk=true
AndroidVersion.PreviewSdkInt=0
AndroidVersion.BetaVersion=
Layoutlib.Api=15
Layoutlib.Revision=1
Platform.MinToolsRev=22
SRCPROP

# AGP 的 SdkLoader 校验 package.xml，官方 zip 不含，从仓库补入
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/platform37-package.xml" ]; then
  cp "$SCRIPT_DIR/platform37-package.xml" "$PLATFORM_DIR/package.xml"
fi

# kapt 需要 $ANDROID_HOME/tools/support/annotations.jar（新版 SDK 已移除 tools/，从仓库补入）
if [ -f "$SCRIPT_DIR/annotations.jar" ]; then
  mkdir -p "$TARGET/../tools/support"
  cp "$SCRIPT_DIR/annotations.jar" "$TARGET/../tools/support/annotations.jar"
fi

echo "patched $PLATFORM_DIR/source.properties + package.xml + annotations.jar"
