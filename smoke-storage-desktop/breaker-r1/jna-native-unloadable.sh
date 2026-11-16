#!/bin/bash
# Breaker r1 (group sto-smoke) negative control: runs the ALREADY BUILT jna app image (build it first with
# ./gradlew :smoke-storage-desktop:smokeJna -Psmoke.probeOnly=true) with JNA's native support library made unloadable.
# A broken binding must never read as a skip: the run MUST exit 1 with Unsupported(NATIVE_ACCESS_UNAVAILABLE), never
# PASS-WITH-SKIP. Probe only: no key is read or written. Usage: jna-native-unloadable.sh <directory for the verdict file>
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
app="$root/smoke-storage-desktop/jna/build/compose/binaries/main-release/app/kmptoolkit-smoke-storage-jna.app/Contents/MacOS/kmptoolkit-smoke-storage-jna"
out="${1:?verdict directory}"
mkdir -p "$out"
JAVA_TOOL_OPTIONS="-Djna.nounpack=true -Djna.noclasspath=true -Djna.boot.library.path=/nonexistent -Djna.nosys=true" \
    timeout 120 "$app" --variant=jna --result-file="$out/jna-native-unloadable.txt" --probe-only
code=$?
tail -1 "$out/jna-native-unloadable.txt"
if [ "$code" -eq 1 ] && grep -q 'SMOKE-RESULT: FAIL .*NATIVE_ACCESS_UNAVAILABLE' "$out/jna-native-unloadable.txt"; then
    echo "control OK: exit $code"
    exit 0
fi
echo "control BROKEN: exit $code"
exit 1
