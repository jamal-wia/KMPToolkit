#!/bin/bash
# Breaker r4 (group sto-smoke): SIGTERM against the REAL packaged launcher (probe-only image, no key read or written), recording what a
# stopped run PRINTS: the verdict line, the exit code, the hook's stderr lines, leftovers. Optional second signal (TERM or INT) shortly
# after the first, to attack a signal that arrives while the hook already runs.
# Build first: ./gradlew :smoke-storage-desktop:<variant>:createReleaseDistributable -Psmoke.probeOnly=true
# Usage: sigterm-verdicts.sh <variant> <work dir> <trials> <second signal: none|TERM|INT> [delays...]
set -u
variant="${1:?ffm|jna}"; work="${2:?work dir}"; trials="${3:?trials}"; second="${4:?none|TERM|INT}"; shift 4
delays=("$@"); [ ${#delays[@]} -eq 0 ] && delays=(0)
root="$(cd "$(dirname "$0")/../.." && pwd)"
app="$root/smoke-storage-desktop/$variant/build/compose/binaries/main-release/app/kmptoolkit-smoke-storage-$variant.app/Contents/MacOS/kmptoolkit-smoke-storage-$variant"
mkdir -p "$work"
: > "$work/verdicts.txt"
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
    [ "$second" != "none" ] && { sleep 0.005; kill -"$second" "$pid" 2>/dev/null; }
    rest="$(cat <&3)"
    exec 3<&-
    rm -f "$fifo"
    wait "$pid"; code=$?
    left="$(find "$tmp" -maxdepth 1 -name 'kmptoolkit-smoke-*' | wc -l | tr -d ' ')"
    verdict="$(printf '%s\n' "$rest" | grep '^SMOKE-RESULT' | tail -1 | cut -c1-60)"; [ -z "$verdict" ] && verdict="<no verdict>"
    hook="$(grep -c 'shutdown cleanup' "$tmp/stderr.txt")"
    echo "trial=$i delay=$d exit=$code left=$left hooklines=$hook verdict=[$verdict]" >> "$work/verdicts.txt"
done
echo "== variant=$variant second=$second trials=$trials"
awk '{print $3, $4, $5, substr($0, index($0,"verdict="))}' "$work/verdicts.txt" | sed -E 's/verdict=\[(SMOKE-RESULT: [A-Z-]+)[^]]*\]/\1/' | sort | uniq -c
