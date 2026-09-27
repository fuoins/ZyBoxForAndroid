#!/usr/bin/env bash
# 把 sdkmanager/官方zip 解压出的 android-37 平台目录的 source.properties
# 修补为 AGP 可识别的稳定包属性（对齐本地真实 platform-37 安装包）
set -e
TARGET="${1:?usage: patch_android37.sh <platforms-dir>}"
PLATFORM_DIR="$TARGET/android-37"

if [ ! -f "$PLATFORM_DIR/source.properties" ]; then
  echo "no android-37 platform dir: $PLATFORM_DIR" >&2
  exit 1
fi

cat > "$PLATFORM_DIR/source.properties" <<'EOF'
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
EOF
echo "patched $PLATFORM_DIR/source.properties"
