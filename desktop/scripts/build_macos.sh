#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${APP_VERSION:-}" ]]; then
  APP_VERSION="$(sed -n 's/^version:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$ROOT_DIR/pubspec.yaml" | head -n1)"
  APP_VERSION="${APP_VERSION:-2.1.0}"
fi
ARCH="universal"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="${2:-universal}"
      shift 2
      ;;
    *)
      echo "用法: $0 [--arch arm64|x64|universal]" >&2
      exit 1
      ;;
  esac
done

if [[ "$ARCH" != "arm64" && "$ARCH" != "x64" && "$ARCH" != "universal" ]]; then
  echo "不支持的架构: $ARCH (可选: arm64 | x64 | universal)" >&2
  exit 1
fi

RUST_DIR="$ROOT_DIR/rust"
DIST_DIR="$ROOT_DIR/dist"
APP_NAME="lvguo_desktop.app"
OUT_APP_NAME="绿果PC.app"
STAGE_DIR="$DIST_DIR/macos-stage-$ARCH"
DMG_PATH="$DIST_DIR/LvguoPC-$APP_VERSION-macOS-$ARCH.dmg"

mkdir -p "$DIST_DIR"

echo "==> [1/4] ($ARCH) 编译 Rust 核心 (liblvguo_core.dylib)..."
cd "$RUST_DIR"

build_rust_target() {
  local target="$1"
  if command -v rustup >/dev/null 2>&1; then
    rustup target add "$target" >/dev/null 2>&1 || true
  fi
  cargo build --release --target "$target"
}

DYLIB_PATH="$DIST_DIR/liblvguo_core-$ARCH.dylib"
if [[ "$ARCH" == "arm64" ]]; then
  build_rust_target "aarch64-apple-darwin"
  cp -f "$RUST_DIR/target/aarch64-apple-darwin/release/liblvguo_core.dylib" "$DYLIB_PATH"
elif [[ "$ARCH" == "x64" ]]; then
  build_rust_target "x86_64-apple-darwin"
  cp -f "$RUST_DIR/target/x86_64-apple-darwin/release/liblvguo_core.dylib" "$DYLIB_PATH"
else
  build_rust_target "aarch64-apple-darwin"
  build_rust_target "x86_64-apple-darwin"
  lipo -create \
    "$RUST_DIR/target/aarch64-apple-darwin/release/liblvguo_core.dylib" \
    "$RUST_DIR/target/x86_64-apple-darwin/release/liblvguo_core.dylib" \
    -output "$DYLIB_PATH"
fi
lipo -info "$DYLIB_PATH" || file "$DYLIB_PATH"

echo "==> [2/4] ($ARCH) 编译 Flutter macOS 发布版..."
cd "$ROOT_DIR"
flutter build macos --release

BUILD_APP="$ROOT_DIR/build/macos/Build/Products/Release/$APP_NAME"
if [[ ! -d "$BUILD_APP" ]]; then
  echo "未找到 $BUILD_APP" >&2
  exit 1
fi

echo "==> [3/4] ($ARCH) 组装 App 并嵌入 liblvguo_core.dylib..."
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
TARGET_APP="$STAGE_DIR/$OUT_APP_NAME"
cp -R "$BUILD_APP" "$TARGET_APP"

FRAMEWORKS_DIR="$TARGET_APP/Contents/Frameworks"
mkdir -p "$FRAMEWORKS_DIR"
cp -f "$DYLIB_PATH" "$FRAMEWORKS_DIR/liblvguo_core.dylib"
install_name_tool -id "@rpath/liblvguo_core.dylib" "$FRAMEWORKS_DIR/liblvguo_core.dylib" || true

# Ad-hoc sign frameworks + bundle so Apple Silicon runs modified Frameworks cleanly
codesign --force --deep --sign - "$TARGET_APP" >/dev/null 2>&1 || true

echo "==> [4/4] ($ARCH) 生成 macOS DMG 安装镜像..."
ln -s /Applications "$STAGE_DIR/Applications"
rm -f "$DMG_PATH"
hdiutil create -volname "绿果PC" -srcfolder "$STAGE_DIR" -ov -format UDZO "$DMG_PATH"
rm -rf "$STAGE_DIR"

echo "[$ARCH] 完成:"
ls -lh "$DMG_PATH"
