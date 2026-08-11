#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 /path/to/SomeClass.java" >&2
  exit 2
fi

FILE="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$FILE" ]; then
  echo "File not found: $FILE" >&2
  exit 2
fi

case "$FILE" in
  "$ROOT"/*) ;;
  *)
    echo "File is outside workspace: $FILE" >&2
    exit 2
    ;;
esac

PACKAGE="$(sed -n 's/^[[:space:]]*package[[:space:]]\{1,\}\([^;[:space:]]\{1,\}\)[[:space:]]*;.*/\1/p' "$FILE" | head -n 1)"
CLASS_NAME="$(basename "$FILE" .java)"

if [ -z "$PACKAGE" ]; then
  FQCN="$CLASS_NAME"
else
  FQCN="$PACKAGE.$CLASS_NAME"
fi

RELATIVE="${FILE#$ROOT/}"
MODULE="${RELATIVE%%/*}"

if [ ! -f "$ROOT/$MODULE/pom.xml" ]; then
  echo "Cannot locate module pom.xml for: $FILE" >&2
  exit 2
fi

echo "Workspace: $ROOT"
echo "Module:    $MODULE"
echo "Class:     $FQCN"
echo

cd "$ROOT"
mvn -q -pl "$MODULE" -am -DskipTests compile

CLASSPATH="$(find "$ROOT" -path '*/target/classes' -type d | paste -sd ':' -)"

if [ -z "$CLASSPATH" ]; then
  echo "No target/classes directories found after compile." >&2
  exit 1
fi

echo
echo "===== javap -c -l $FQCN ====="
javap -classpath "$CLASSPATH" -c -l "$FQCN"
