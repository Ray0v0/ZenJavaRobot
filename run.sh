#!/bin/bash
# run.sh — 一键编译打包运行 ZenJavaRobot
set -e

cd "$(dirname "$0")"
MAIN=app.App
OUT=out

# ---------- 1. 编译主项目 ----------
need_compile=false
if [ ! -d "$OUT" ]; then
    need_compile=true
else
    for src in $(find src -name "*.java"); do
        class="$OUT/${src#src/}"
        class="${class%.java}.class"
        if [ ! -f "$class" ] || [ "$src" -nt "$class" ]; then
            need_compile=true
            break
        fi
    done
fi

if $need_compile; then
    echo "[main] Compiling..."
    rm -rf "$OUT" && mkdir -p "$OUT"
    javac -d "$OUT" $(find src -name "*.java")
    echo "[main] Done"
else
    echo "[main] Up to date"
fi

# ---------- 2. 编译打包插件 ----------
for dir in plugins/*/; do
    name=$(basename "$dir")
    src="$dir/src"
    [ -d "$src" ] || continue
    jarfile="plugins/${name}.jar"

    # 检查是否需要重新打包
    need_build=false
    if [ ! -f "$jarfile" ]; then
        need_build=true
    else
        for f in $(find "$src" -type f); do
            if [ "$f" -nt "$jarfile" ]; then
                need_build=true
                break
            fi
        done
    fi

    if $need_build; then
        echo "[$name] Building..."
        rm -rf "$dir/out" && mkdir -p "$dir/out"
        javac -cp "$OUT" -d "$dir/out" $(find "$src" -name "*.java")
        jar cf "$jarfile" -C "$dir/out" . $( [ -d "$src/META-INF" ] && echo "-C $src META-INF" )
        echo "[$name] -> $jarfile"
    else
        echo "[$name] Up to date"
    fi
done

# ---------- 3. 运行 ----------
echo ""
echo "[run] Starting..."
java -cp "$OUT:plugins/echo-plugin.jar:plugins/xiaoxin-plugin.jar" "$MAIN" "$@"
