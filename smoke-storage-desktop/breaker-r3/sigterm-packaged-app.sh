#!/bin/bash
# Breaker r3 (group sto-smoke): SIGTERM against the REAL packaged launcher (jpackage image, ProGuard), not a plain test JVM.
# Build first (probe-only image, so even the launcher's default arguments carry --probe-only):
#   ./gradlew :smoke-storage-desktop:<variant>:createReleaseDistributable -Psmoke.probeOnly=true
# Each trial starts the app with --probe-only (no key is read or written) and its own java.io.tmpdir, waits for the first line
# (printed after the temporary directory exists and the shutdown hook is installed), optionally waits <delay> more, sends SIGTERM,
# and judges: an app that ended by the signal (exit 143) must leave no kmptoolkit-smoke-* directory. An app that finished before the
# signal (exit 0) is not a trial of the hook and is counted apart. Usage: sigterm-packaged-app.sh <variant> <work dir> <trials> [delays...]
set -u
variant="${1:?ffm|jna}"; work="${2:?work dir}"; trials="${3:?trials}"; shift 3
delays=("$@"); [ ${#delays[@]} -eq 0 ] && delays=(0)
root="$(cd "$(dirname "$0")/../.." && pwd)"
app="$root/smoke-storage-desktop/$variant/build/compose/binaries/main-release/app/kmptoolkit-smoke-storage-$variant.app/Contents/MacOS/kmptoolkit-smoke-storage-$variant"
mkdir -p "$work"
killed=0; finished=0; leaks=0; other=0
for ((i = 0; i < trials; i++)); do
    d="${delays[$((i % ${#delays[@]}))]}"
    tmp="$work/t$i"; rm -rf "$tmp"; mkdir -p "$tmp"
    fifo="$work/fifo$i"; rm -f "$fifo"; mkfifo "$fifo"
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$tmp" "$app" --variant="$variant" --probe-only --result-file="$tmp/result.txt" >"$fifo" 2>"$tmp/stderr.txt" &
    pid=$!
    exec 3<"$fifo"
    IFS= read -r -t 60 first <&3 || first="<no line>"
    [ "$d" != "0" ] && sleep "$d"
    kill -TERM "$pid" 2>/dev/null
    rest="$(cat <&3)"
    exec 3<&-
    rm -f "$fifo"
    wait "$pid"; code=$?
    left="$(find "$tmp" -maxdepth 1 -name 'kmptoolkit-smoke-*' | wc -l | tr -d ' ')"
    case "$code" in
        143) killed=$((killed + 1)); if [ "$left" != "0" ]; then leaks=$((leaks + 1)); echo "LEAK trial=$i delay=$d left=$left: $(find "$tmp" -path '*kmptoolkit-smoke-*' | sed "s|$tmp/||" | tr '\n' ' ') stderr: $(grep -v 'Picked up' "$tmp/stderr.txt" | tr '\n' ' ')"; fi ;;
        0) finished=$((finished + 1)) ;;
        *) other=$((other + 1)); echo "OTHER trial=$i delay=$d exit=$code first=[$first] rest=[$(echo "$rest" | tail -2 | tr '\n' ' ')] stderr=[$(grep -v 'Picked up' "$tmp/stderr.txt" | tail -3 | tr '\n' ' ')]" ;;
    esac
done
echo "variant=$variant trials=$trials killed(143)=$killed finished-before-signal(0)=$finished other=$other leaks-after-SIGTERM=$leaks"
[ "$leaks" -eq 0 ] && [ "$other" -eq 0 ]
