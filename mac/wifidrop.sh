#!/bin/bash
# WifiDrop sender: copies a folder from this Mac to the WifiDrop app on a phone over Wi-Fi.
# It can be stopped and started again at any time; it compares both sides and continues.
#
#   bash wifidrop.sh <phone-address> <code> [folder]          send (folder defaults to ~/Downloads)
#   bash wifidrop.sh verify <phone-address> <code> [folder]   re-read every file from the disk and compare checksums
#
# To leave one top-level folder out, put WIFIDROP_EXCLUDE=<folder name> in front of either command.

set -u
export LC_ALL=C

MODE=send
if [ "${1:-}" = verify ]; then MODE=verify; shift; fi
if [ $# -lt 2 ]; then
  echo "Usage: bash wifidrop.sh [verify] <phone-address> <code> [folder]" >&2
  exit 64
fi
IP=$1
CODE=$2
SRC=${3:-$HOME/Downloads}
PORT=${WIFIDROP_PORT:-8765}
BASE="http://$IP:$PORT"
STATE=${WIFIDROP_STATE:-$HOME/.wifidrop}
TAB=$'\t'
EXCL=( ! -path "./${WIFIDROP_EXCLUDE:-.wifidrop-none}/*" )
HTTP=000
POLLER=

mkdir -p "$STATE" || exit 1
cd "$SRC" || { echo "Cannot open $SRC" >&2; exit 1; }
if stat -f %z . >/dev/null 2>&1; then BSD=1; else BSD=0; fi

say() { printf '\r\033[K%s\n' "$*" >&2; }
die() { stop_poller; say "$*"; exit 1; }

fsize() { if [ $BSD = 1 ]; then stat -f %z "$1"; else stat -c %s "$1"; fi; }

human() {
  awk -v b="$1" 'BEGIN { if (b >= 1073741824) printf "%.2f GB", b / 1073741824;
                         else if (b >= 1048576) printf "%.1f MB", b / 1048576;
                         else printf "%d KB", (b + 1023) / 1024 }'
}

# Every byte becomes %XX, so any file name survives the trip.
urlenc() { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n' | sed 's/\(..\)/%\1/g'; }

# call <output-file> <curl arguments...>   sets HTTP to the status code, 000 if the phone was unreachable
call() {
  local out=$1
  shift
  HTTP=$(curl -sS ${WIFIDROP_CURL_EXTRA:-} --connect-timeout 10 -H "X-Code: $CODE" -o "$out" -w '%{http_code}' "$@" 2>"$STATE/curl.err") || HTTP=000
}

build_manifest() {
  local nl=$'\n' cr=$'\r'
  if [ $BSD = 1 ]; then
    find . -type f ! -name .DS_Store ! -name '._*' ! -name .localized "${EXCL[@]}" \
      ! -path "*${TAB}*" ! -path "*${nl}*" ! -path "*${cr}*" -exec stat -f "%z${TAB}%N" {} + 2>"$STATE/find.err"
  else
    find . -type f ! -name .DS_Store ! -name '._*' ! -name .localized "${EXCL[@]}" \
      ! -path "*${TAB}*" ! -path "*${nl}*" ! -path "*${cr}*" -exec stat -c "%s${TAB}%n" {} + 2>"$STATE/find.err"
  fi | sed "s/${TAB}\.\//${TAB}/" | sort -t "$TAB" -k2 >"$STATE/manifest.tsv"
  local odd
  odd=$(find . -type f \( -path "*${TAB}*" -o -path "*${nl}*" -o -path "*${cr}*" \) 2>/dev/null | wc -l | tr -d ' ')
  [ "$odd" != 0 ] && say "Note: $odd file(s) with tabs or line breaks in their names are skipped."
  if [ -s "$STATE/find.err" ]; then
    say "Note: some items could not be read (see $STATE/find.err). If this says 'Operation not permitted',"
    say "allow Terminal to access the folder in System Settings > Privacy & Security > Files and Folders."
  fi
}

# compare <remote-list> <manifest>   prints "offset<TAB>size<TAB>path" for every file that still needs sending;
# writes "files bytes donefiles donebytes" to $STATE/summary. Names are matched the way the phone stores them.
compare() {
  awk -v summary="$STATE/summary" '
    function san(p,   n, i, parts, c, out) {
      n = split(p, parts, "/"); out = ""
      for (i = 1; i <= n; i++) {
        c = parts[i]
        gsub(/[\\:*?"<>|]/, "_", c)
        gsub(/[[:cntrl:]]/, "_", c)
        while (c ~ /[. ]$/) { sub(/[. ]$/, "", c); tail = tail "_" }
        c = c tail; tail = ""
        out = out (i > 1 ? "/" : "") c
      }
      return out
    }
    { t = index($0, "\t"); if (!t) next; s = substr($0, 1, t - 1) + 0; p = substr($0, t + 1) }
    FILENAME == ARGV[1] { r[p] = s; have[p] = 1; next }
    {
      files++; bytes += s; k = san(p); off = 0
      if (k in have) {
        if (r[k] == s) { donefiles++; donebytes += s; next }
        if (r[k] < s) off = r[k]
      }
      donebytes += off
      printf "%d\t%d\t%s\n", off, s, p
    }
    END { printf "%d %.0f %d %.0f\n", files, bytes, donefiles, donebytes > summary }
  ' "$1" "$2"
}

poller() {
  local s d i n
  while :; do
    sleep 2
    s=$(curl -s --max-time 5 -H "X-Code: $CODE" "$BASE/status" 2>/dev/null) || continue
    read -r d i n <"$STATE/progress" 2>/dev/null || continue
    # shellcheck disable=SC2086
    set -- $s
    awk -v d="$d" -v c="${2:-0}" -v t="$TOTAL_BYTES" -v sp="${3:-0}" -v i="$i" -v n="$n" 'BEGIN {
      done = d + c; if (done > t) done = t
      printf "\r\033[K  %.2f of %.2f GB (%d%%)   %.1f MB/s   file %d of %d", done / 1073741824, t / 1073741824,
             (t > 0 ? done * 100 / t : 100), sp / 1048576, i, n }' >&2
  done
}
stop_poller() { if [ -n "$POLLER" ]; then kill "$POLLER" 2>/dev/null; wait "$POLLER" 2>/dev/null; POLLER=; fi; }

wait_for_phone() {
  say "Connection to the phone lost. Waiting for it to come back (Ctrl-C to stop; run the same command later to continue)..."
  while :; do
    sleep 5
    call "$STATE/resp" "$BASE/ping"
    [ "$HTTP" = 200 ] && { say "Phone is back. Continuing."; return; }
    [ "$HTTP" = 401 ] && die "The phone rejected the code. Check the code shown in the app."
  done
}

# send_one <offset> <size> <path> <check>   check=1 forces a checksum comparison after sending.
# Returns 0 when the file is on the disk, 1 when it had to be given up on.
send_one() {
  local off=$1 size=$2 path=$3 check=$4 enc now mism=0 n lh rh
  enc=$(urlenc "$path")
  [ "$off" -gt 0 ] && check=1
  while :; do
    if [ ! -f "./$path" ]; then
      echo "no longer on this Mac${TAB}$path" >>"$STATE/failed.tsv"; return 1
    fi
    now=$(fsize "./$path")
    if [ "$now" != "$size" ]; then size=$now; off=0; fi
    echo "$((DONE_BYTES + off)) $IDX $TODO_FILES" >"$STATE/progress"

    if [ "$off" -lt "$size" ] || [ "$size" -eq 0 ]; then
      if [ "$off" -eq 0 ]; then
        call "$STATE/resp" -T - -H 'Expect: 100-continue' --expect100-timeout 30 --speed-limit 1 --speed-time 120 \
          "$BASE/put?path=$enc&offset=0" <"./$path"
      else
        call "$STATE/resp" -T - -H 'Expect: 100-continue' --expect100-timeout 30 --speed-limit 1 --speed-time 120 \
          "$BASE/put?path=$enc&offset=$off" < <(tail -c +$((off + 1)) "./$path")
      fi
    else
      HTTP=200
    fi

    if [ "$HTTP" = 200 ] && [ "$check" = 1 ]; then
      lh=$(shasum -a 256 "./$path" | cut -d' ' -f1)
      call "$STATE/resp" "$BASE/hash?path=$enc"
      if [ "$HTTP" = 200 ]; then
        rh=$(cut -d' ' -f1 "$STATE/resp")
        [ "$lh" = "$rh" ] && return 0
        mism=$((mism + 1))
        if [ $mism -ge 3 ]; then
          echo "checksum keeps differing${TAB}$path" >>"$STATE/failed.tsv"; return 1
        fi
        say "Checksum differs for $path; sending it again from the start."
        off=0
        continue
      fi
    fi

    case $HTTP in
      200) return 0 ;;
      401) die "The phone rejected the code. Check the code shown in the app." ;;
      409)
        n=$(sed -n 's/^size \(-\{0,1\}[0-9][0-9]*\).*/\1/p' "$STATE/resp")
        if [ -n "$n" ] && [ "$n" -ge 0 ] && [ "$n" -le "$size" ]; then off=$n; else off=0; fi
        check=1 ;;
      422)
        echo "$(cat "$STATE/resp")${TAB}$path" >>"$STATE/failed.tsv"; return 1 ;;
      *)
        if [ "$HTTP" = 500 ]; then say "The phone reports a problem: $(cat "$STATE/resp" 2>/dev/null). Retrying..."; fi
        wait_for_phone
        while :; do
          call "$STATE/resp" "$BASE/stat?path=$enc"
          [ "$HTTP" = 200 ] && break
          wait_for_phone
        done
        n=$(cat "$STATE/resp")
        if [ "$n" -gt 0 ] 2>/dev/null && [ "$n" -le "$size" ]; then off=$n; else off=0; fi
        check=1 ;;
    esac
  done
}

trap 'stop_poller; say "Stopped. Run the same command again to continue where it left off."; exit 130' INT TERM

call "$STATE/resp" "$BASE/ping"
case $HTTP in
  200) ;;
  401) die "The phone rejected the code. Check the code shown in the app." ;;
  *) die "Cannot reach the phone at $IP. Check that the app says 'Receiving', the address is right, and both are on the same Wi-Fi." ;;
esac

say "Listing files in $SRC ..."
build_manifest
[ -s "$STATE/manifest.tsv" ] || die "No files found in $SRC."

say "Checking what is already on the hard disk ..."
call "$STATE/remote.tsv" "$BASE/list"
[ "$HTTP" = 200 ] || die "The phone could not list the hard disk: $(cat "$STATE/remote.tsv" 2>/dev/null)"
: >"$STATE/failed.tsv"

if [ $MODE = verify ]; then
  compare "$STATE/remote.tsv" "$STATE/manifest.tsv" >"$STATE/todo.tsv"
  read -r TOTAL_FILES TOTAL_BYTES HAVE_FILES HAVE_BYTES <"$STATE/summary"
  MISSING=$(wc -l <"$STATE/todo.tsv" | tr -d ' ')
  say "Comparing checksums of $HAVE_FILES files ($(human "$TOTAL_BYTES")). This re-reads everything on both sides."
  [ "$MISSING" != 0 ] && say "Note: $MISSING file(s) are missing or incomplete on the disk; run the send command for those."
  awk -F'\t' 'FILENAME == ARGV[1] { skip[$3] = 1; next } !($2 in skip)' "$STATE/todo.tsv" "$STATE/manifest.tsv" >"$STATE/check.tsv"
  IDX=0; DONE_BYTES=0; TODO_FILES=$HAVE_FILES; BAD=0; FIXED=0
  while IFS="$TAB" read -r size path <&3; do
    IDX=$((IDX + 1))
    printf '\r\033[K  checked %d of %d' "$IDX" "$HAVE_FILES" >&2
    lh=$(shasum -a 256 "./$path" | cut -d' ' -f1)
    while :; do
      call "$STATE/resp" "$BASE/hash?path=$(urlenc "$path")"
      [ "$HTTP" = 200 ] && break
      wait_for_phone
    done
    if [ "$lh" != "$(cut -d' ' -f1 "$STATE/resp")" ]; then
      BAD=$((BAD + 1))
      say "Differs: $path. Sending it again."
      if send_one 0 "$size" "$path" 1; then FIXED=$((FIXED + 1)); fi
    fi
  done 3<"$STATE/check.tsv"
  if [ "$BAD" = 0 ] && [ "$MISSING" = 0 ]; then
    say "Verified: all $HAVE_FILES files on the hard disk are identical to the ones on this Mac."
    exit 0
  fi
  say "Checked $HAVE_FILES files: $BAD differed, $FIXED of those were sent again and now match. Missing or incomplete: $MISSING."
  [ -s "$STATE/failed.tsv" ] && say "Could not fix (reason, file):" && cat "$STATE/failed.tsv" >&2
  exit 1
fi

compare "$STATE/remote.tsv" "$STATE/manifest.tsv" >"$STATE/todo.tsv"
read -r TOTAL_FILES TOTAL_BYTES HAVE_FILES HAVE_BYTES <"$STATE/summary"
TODO_FILES=$(wc -l <"$STATE/todo.tsv" | tr -d ' ')
say "On this Mac:      $TOTAL_FILES files, $(human "$TOTAL_BYTES")"
say "Already on disk:  $HAVE_FILES complete files, $(human "$HAVE_BYTES")"
say "To send now:      $TODO_FILES files, $(human $((TOTAL_BYTES - HAVE_BYTES)))"
call "$STATE/resp" -X POST "$BASE/plan?files=$TOTAL_FILES&bytes=$TOTAL_BYTES&done=$HAVE_BYTES"

IDX=0
DONE_BYTES=$HAVE_BYTES
echo "$DONE_BYTES 0 $TODO_FILES" >"$STATE/progress"
poller &
POLLER=$!
while IFS="$TAB" read -r off size path <&3; do
  IDX=$((IDX + 1))
  DONE_BYTES=$((DONE_BYTES - off))
  send_one "$off" "$size" "$path" 0
  DONE_BYTES=$((DONE_BYTES + size))
done 3<"$STATE/todo.tsv"
stop_poller

say "Sending finished. Checking both sides once more ..."
while :; do
  call "$STATE/remote.tsv" "$BASE/list"
  [ "$HTTP" = 200 ] && break
  wait_for_phone
done
build_manifest
compare "$STATE/remote.tsv" "$STATE/manifest.tsv" >"$STATE/todo.tsv"
read -r TOTAL_FILES TOTAL_BYTES HAVE_FILES HAVE_BYTES <"$STATE/summary"
LEFT=$(wc -l <"$STATE/todo.tsv" | tr -d ' ')
if [ "$LEFT" = 0 ]; then
  call "$STATE/resp" -X POST "$BASE/finish?ok=1"
  say "Done: all $TOTAL_FILES files ($(human "$TOTAL_BYTES")) are on the hard disk and their sizes match."
  PREFIX=""
  [ -n "${WIFIDROP_EXCLUDE:-}" ] && PREFIX="WIFIDROP_EXCLUDE=\"$WIFIDROP_EXCLUDE\" "
  say "For a full checksum comparison, run:  ${PREFIX}bash $0 verify $IP $CODE \"$SRC\""
  exit 0
fi
call "$STATE/resp" -X POST "$BASE/finish?ok=0"
say "$LEFT file(s) are still missing or incomplete on the disk:"
cut -f3 "$STATE/todo.tsv" | head -20 >&2
[ -s "$STATE/failed.tsv" ] && say "Reasons reported (reason, file):" && head -20 "$STATE/failed.tsv" >&2
say "Run the same command again to retry them."
exit 1
