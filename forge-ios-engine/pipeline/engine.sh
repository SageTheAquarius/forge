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
        (cd "$ROOT/forge-ios-engine" && mvn -B -ntp -e robovm:install --settings "$SETTINGS" \
            -Dmaven.repo.local="$CLONE" -DskipTests 2>&1 | grep -v '^\[INFO\] Compiling ' | tail -60)
        FW="$ROOT/forge-ios-engine/target/robovm/ForgeEngine.framework"
        if [ ! -d "$FW" ]; then
            echo "FRAMEWORK MISSING - build failed; target/robovm holds:"
            ls -R "$ROOT/forge-ios-engine/target/robovm" 2>/dev/null | head -40
            exit 1
        fi
        (cd "$ROOT/forge-ios-engine/target/robovm" && rm -f ForgeEngine.framework.zip \
            && zip -qry ForgeEngine.framework.zip ForgeEngine.framework)
        echo "FRAMEWORK: $FW"
        ls -la "$FW" | head -20
        ;;
    *)
        echo "usage: $0 [audit|framework]"; exit 1 ;;
esac
