#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${APP_VERSION:-}" ]]; then
  APP_VERSION="$(sed -n 's/^version:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$ROOT_DIR/pubspec.yaml" | head -n1)"
  APP_VERSION="${APP_VERSION:-2.1.0}"
fi
ARCH="x64"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="${2:-x64}"
      shift 2
      ;;
    *)
      echo "用法: $0 [--arch x64|arm64]" >&2
      exit 1
      ;;
  esac
done

if [[ "$ARCH" != "x64" && "$ARCH" != "arm64" ]]; then
  echo "不支持的架构: $ARCH (可选: x64 | arm64)" >&2
  exit 1
fi

RUST_DIR="$ROOT_DIR/rust"
DIST_DIR="$ROOT_DIR/dist"
DEB_ARCH="amd64"
RUST_TARGET="x86_64-unknown-linux-gnu"
FLUTTER_ARCH="x64"
if [[ "$ARCH" == "arm64" ]]; then
  DEB_ARCH="arm64"
  RUST_TARGET="aarch64-unknown-linux-gnu"
  FLUTTER_ARCH="arm64"
fi

mkdir -p "$DIST_DIR"

echo "==> [1/4] ($ARCH) 编译 Rust 核心 (liblvguo_core.so)..."
cd "$RUST_DIR"
if command -v rustup >/dev/null 2>&1; then
  rustup target add "$RUST_TARGET" >/dev/null 2>&1 || true
fi
cargo build --release --target "$RUST_TARGET"
SO_PATH="$RUST_DIR/target/$RUST_TARGET/release/liblvguo_core.so"

echo "==> [2/4] ($ARCH) 编译 Flutter Linux 发布版..."
cd "$ROOT_DIR"
flutter build linux --release

BUNDLE_DIR="$ROOT_DIR/build/linux/$FLUTTER_ARCH/release/bundle"
if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "未找到 Linux bundle 目录: $BUNDLE_DIR" >&2
  exit 1
fi

echo "==> [3/4] ($ARCH) 嵌入 liblvguo_core.so..."
cp -f "$SO_PATH" "$BUNDLE_DIR/lib/liblvguo_core.so"

echo "==> [4/4] ($ARCH) 打包 Debian 安装包 (.deb)..."
PKG_DIR="$DIST_DIR/deb-stage-$ARCH"
rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/DEBIAN"
mkdir -p "$PKG_DIR/opt/lvguo-pc"
mkdir -p "$PKG_DIR/usr/bin"
mkdir -p "$PKG_DIR/usr/share/applications"
mkdir -p "$PKG_DIR/usr/share/icons/hicolor/256x256/apps"

cp -a "$BUNDLE_DIR/." "$PKG_DIR/opt/lvguo-pc/"
cp -f "$ROOT_DIR/assets/logo.png" "$PKG_DIR/usr/share/icons/hicolor/256x256/apps/lvguo-pc.png"

cat > "$PKG_DIR/usr/bin/lvguo-pc" <<'EOF'
#!/usr/bin/env bash
exec /opt/lvguo-pc/lvguo_desktop "$@"
EOF
chmod 0755 "$PKG_DIR/usr/bin/lvguo-pc"

cat > "$PKG_DIR/usr/share/applications/lvguo-pc.desktop" <<'EOF'
[Desktop Entry]
Name=绿果PC
Comment=绿果PC 独立桌面客户端
Exec=/usr/bin/lvguo-pc %U
Icon=lvguo-pc
Terminal=false
Type=Application
Categories=AudioVideo;Video;Player;
MimeType=x-scheme-handler/lvguo;
EOF

cat > "$PKG_DIR/DEBIAN/control" <<EOF
Package: lvguo-pc
Version: $APP_VERSION
Section: video
Priority: optional
Architecture: $DEB_ARCH
Depends: libgtk-3-0, libmpv2 | libmpv1
Maintainer: Pengfei <support@pengfei.ink>
Description: 绿果PC 独立桌面客户端
EOF

DEB_PATH="$DIST_DIR/LvguoPC-$APP_VERSION-linux-$ARCH.deb"
rm -f "$DEB_PATH"
dpkg-deb --build "$PKG_DIR" "$DEB_PATH"
rm -rf "$PKG_DIR"

echo "[$ARCH] 完成:"
ls -lh "$DEB_PATH"
