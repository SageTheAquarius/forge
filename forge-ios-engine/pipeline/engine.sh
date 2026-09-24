#!/bin/bash
# engine.sh — build ForgeEngine.framework (forge-core/game/ai/gui +
# forge-sim-server, headless) for iOS with MobiVM.
#
# Reuses forge-gui-ios/pipeline/ios-pipeline.sh (bootstrap, JvmDowngrader
# transform, MobiVmBridge, link audit, module build) with the module switched
# to forge-ios-engine. Modes:
#
#   audit      Linux or macOS, no Apple tooling: transform + link gate. The
#              cheap CI check (~5 min).
#   framework  macOS only: audit steps, then the RoboVM AOT build. Output:
#              forge-ios-engine/target/robovm/ForgeEngine.framework (+ .zip).
#              30-60 min cold on a hosted runner; SKIP_CACHE_CLEAR=1 keeps the
#              content-hashed AOT cache between runs.
set -e

MODE="${1:-audit}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

export IOS_MODULE=forge-ios-engine
export IOS_INSTALL_MODULES=".,forge-core,forge-game,forge-gui,forge-ai,forge-sim-server"
export APP_ID="${APP_ID:-com.economydraft.forgeengine}"

# shellcheck disable=SC1091
IOS_PIPELINE_LIB=1 source "$ROOT/forge-gui-ios/pipeline/ios-pipeline.sh"

bootstrap

case "$MODE" in
    audit)
        audit
        ;;
    framework)
        # ENGINE_ARCHS (comma list, e.g. "arm64" or "arm64-simulator,x86_64")
        # narrows robovm.xml's <arch> list for THIS build only, so CI can
        # compile the device slice and the simulator slices on two runners
        # in parallel instead of all three in a row (~7 min each).
        if [ -n "${ENGINE_ARCHS:-}" ]; then
            python3 - "$ROOT/forge-ios-engine/robovm.xml" "$ENGINE_ARCHS" <<'PY'
import re, sys
path, archs = sys.argv[1], sys.argv[2].split(",")
s = open(path, encoding="utf-8").read()
s = re.sub(r"[ \t]*<arch>[^<]*</arch>\n", "", s)
s = s.replace("<os>ios</os>\n", "<os>ios</os>\n" + "".join("  <arch>%s</arch>\n" % a.strip() for a in archs), 1)
open(path, "w", encoding="utf-8").write(s)
print("robovm.xml archs ->", archs)
PY
        fi
        echo "=== install forge modules ==="
        (cd "$ROOT" && mvn -B -ntp -q install -pl "$IOS_INSTALL_MODULES" -DskipTests)
        classpath
        if [ "${SKIP_CACHE_CLEAR:-0}" != "1" ]; then
            rm -rf ~/.robovm/cache
        fi
        build_module
        echo "=== class-file versions on the RoboVM classpath (fail fast) ==="
        # target/classes (transformed in build_module) + every dependency jar
        # as the clone repo now holds it: MobiVM only says "Unsupported class
        # file major version 61", never which file.
        python3 "$HERE/classver.py" "$ROOT/forge-ios-engine/target/classes" \
            $(tr ':' '\n' < "$CP_FILE" | sed "s#^$M2#$CLONE#" | tr '\n' ' ')
        echo "=== robovm:install (framework target) ==="
        # -Dmaven.main.skip: should any lifecycle compile sneak in, it must
        # NOT replace the transformed target/classes with fresh Java 17 ones.
        # Full output kept in target/robovm-install.log (uploaded by CI): the
        # "Compiling X" line right before a Soot/ASM failure names the class.
        MVNLOG="$ROOT/forge-ios-engine/target/robovm-install.log"
        (cd "$ROOT/forge-ios-engine" && mvn -B -ntp -e com.mobidevelop.robovm:robovm-maven-plugin:2.3.24:install --settings "$SETTINGS" \
            -Dmaven.repo.local="$CLONE" -DskipTests -Dmaven.main.skip=true > "$MVNLOG" 2>&1) || true
        grep -v '^\[INFO\] Compiling \|Downloading\|Downloaded\|Progress (' "$MVNLOG" | tail -40
        FW="$ROOT/forge-ios-engine/target/robovm/ForgeEngine.xcframework"
        if [ ! -d "$FW" ]; then
            echo "FRAMEWORK MISSING - build failed; target/robovm holds:"
            ls -R "$ROOT/forge-ios-engine/target/robovm" 2>/dev/null | head -40
            echo "=== last classes RoboVM was compiling before the failure ==="
            grep '^\[INFO\] Compiling ' "$MVNLOG" | tail -5
            echo "=== first error lines ==="
            grep -n -m1 -A25 'Caused by\|\[ERROR\] Failed' "$MVNLOG" | head -40
            echo "=== target/classes after the run (was it recompiled?) ==="
            python3 "$HERE/classver.py" "$ROOT/forge-ios-engine/target/classes" | tail -3
            CFG="$ROOT/forge-ios-engine/target/robovm.tmp/config.xml"
            if [ -f "$CFG" ]; then
                echo "=== RoboVM's own classpath (config.xml) checked for post-Java-8 bytecode ==="
                python3 "$HERE/classver.py" $(grep -o '<classpathentry>[^<]*' "$CFG" | sed 's/<classpathentry>//' | tr '\n' ' ') | tail -12
                grep -c '<classpathentry>' "$CFG"
            fi
            exit 1
        fi
        ZIP="${ENGINE_ZIP_NAME:-ForgeEngine.xcframework.zip}"
        (cd "$ROOT/forge-ios-engine/target/robovm" && rm -f "$ZIP" \
            && zip -qry "$ZIP" ForgeEngine.xcframework)
        echo "FRAMEWORK: $FW"
        find "$FW" -maxdepth 3 -not -path '*/Resources/*' | sort | head -30
        ;;
    *)
        echo "usage: $0 [audit|framework]"; exit 1 ;;
esac
