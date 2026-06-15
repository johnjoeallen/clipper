#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v java &>/dev/null; then
  echo "Error: Java 17 or later is required."
  echo "Download from https://adoptium.net"
  exit 1
fi

JAVA_VER=$(java -version 2>&1 | awk -F[\".] '/version/ {print $2}')
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
  echo "Error: Java 17 or later is required (found Java $JAVA_VER)."
  echo "Download from https://adoptium.net"
  exit 1
fi

exec java -jar "$DIR/clipper.jar" "$@"
