#!/bin/bash
# Windows SDK/JDK build, with official Media3 dependencies.
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
python "$SRC/build_local.py"
