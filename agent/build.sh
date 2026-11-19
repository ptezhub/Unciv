#!/usr/bin/env bash
# 编译 agent 宿主。Kotlin 先编（Kotlin 可以引用 Java，反之不行），再 javac。
# 依赖都在本地缓存里，不需要联网，也不需要 gradle。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JDK:-/home/node/.openclaw/cache/jdk/jdk-21.0.5+11}"
CORE="$ROOT/core/build/libs/core-5.0.1.jar"
GAME="${UNCIV_JAR:-/home/node/.openclaw/cache/unciv/Unciv.jar}"
OUT="$ROOT/agent-classes"

M2="$HOME/.gradle/caches/modules-2/files-2.1"
pick() { find "$M2/$1" -name "$2" 2>/dev/null | sort | tail -1; }
KC="$(pick org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-*.jar')"
STD="$(pick org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-*.jar')"
SCR="$(pick org.jetbrains.kotlin/kotlin-script-runtime 'kotlin-script-runtime-*.jar')"
COR="$(pick org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar')"
ANN="$(pick org.jetbrains/annotations 'annotations-*.jar')"

for f in "$KC" "$STD" "$CORE" "$GAME"; do
  [ -f "$f" ] || { echo "缺依赖: $f" >&2; exit 1; }
done

mkdir -p "$OUT"
CP="$CORE:$GAME"

KT_SRC=$(find "$ROOT/agent/src-kotlin" -name '*.kt' 2>/dev/null || true)
if [ -n "$KT_SRC" ]; then
  echo "kotlinc: $(echo "$KT_SRC" | wc -l) 个文件"
  "$JDK/bin/java" -cp "$KC:$STD:$SCR:$COR:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$CP:$STD" -d "$OUT" -no-reflect -nowarn $KT_SRC
fi

JAVA_SRC=$(find "$ROOT/agent/src-java" -name '*.java' 2>/dev/null || true)
if [ -n "$JAVA_SRC" ]; then
  echo "javac: $(echo "$JAVA_SRC" | wc -l) 个文件"
  "$JDK/bin/javac" -nowarn -d "$OUT" -cp "$CP:$OUT" $JAVA_SRC
fi

echo "ok -> $OUT"
