#!/bin/sh
# Invoked through the connection's existing remote-command prefix. No interactive sudo.
set -eu
PATH="$HOME/.local/bin:$HOME/bin:${PATH:-}"
export PATH
: "${OP:?}" "${PORT:?}"
case "$PORT" in ''|*[!0-9]*) exit 1;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || exit 1
CACHE="$HOME/.cache/dexcode"
PID_FILE="$CACHE/code-server-$PORT.pid"
STARTED="$CACHE/code-server-$PORT.started-launcher"
PREFERRED="$CACHE/code-server-$PORT.preferred-launcher"
TMP=""
LOCK=""
PROTECTED=""
STAGE=""
LINK_TMP=""
ADMIN=0
fail() { printf '%s\n' "$*" >&2; exit 1; }
emit() { printf 'DEXCODE_SERVER\t%s\t' "$1"; printf '%s' "$2" | base64 | tr -d '\n'; printf '\n'; }
canonical() { readlink -f "$1" 2>/dev/null || printf '%s\n' "$1"; }
version_of() { "$1" --version 2>/dev/null | sed -n '1{s/ .*//;p;}'; }
identity() {
  [ -r "/proc/$1/stat" ] && [ -r /proc/sys/kernel/random/boot_id ] || return 1
  start=$(sed 's/^[0-9][0-9]* (.*) //' "/proc/$1/stat" | awk '{print $20}')
  printf '%s:%s\n' "$(cat /proc/sys/kernel/random/boot_id)" "$start"
}
admin() {
  if [ "$ADMIN" = 1 ]; then
    [ "${ALLOW_SUDO:-0}" = 1 ] || fail 'Administrator authorization is required.'
    command -v sudo >/dev/null 2>&1 || fail 'sudo is unavailable; use an appropriately privileged SSH account.'
    sudo -n -- "$@"
  else
    "$@"
  fi
}
cleanup() {
  [ -z "$TMP" ] || rm -rf -- "$TMP" || true
  [ -z "$PROTECTED" ] || admin rm -rf -- "$PROTECTED" || true
  [ -z "$STAGE" ] || admin rm -rf -- "$STAGE" || true
  [ -z "$LINK_TMP" ] || admin rm -f -- "$LINK_TMP" || true
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
fetch() {
  if command -v curl >/dev/null 2>&1; then curl -fLsS --connect-timeout 15 --max-time 240 "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then wget -q -T 30 -O "$2" "$1"
  else fail 'curl or wget is required to download official releases.'; fi
}

if [ "$OP" = job ]; then
  printf '%s' "${JOB_ID:-}" | grep -Eq '^[a-f0-9-]{36}$' || fail 'Invalid job identifier.'
  DIR="$CACHE/server-jobs/$JOB_ID"
  [ -d "$DIR" ] || fail 'Update job not found.'
  state=$(cat "$DIR/state" 2>/dev/null || printf queued)
  if [ "$state" = running ]; then
    job_pid=$(cat "$DIR/pid" 2>/dev/null || true)
    saved=$(cat "$DIR/identity" 2>/dev/null || true)
    alive=0
    case "$job_pid" in ''|*[!0-9]*) :;; *)
      if [ -n "$saved" ] && [ "$(identity "$job_pid" || true)" = "$saved" ]; then alive=1; fi;;
    esac
    if [ "$alive" = 0 ]; then
      state=$(cat "$DIR/state" 2>/dev/null || printf interrupted)
      [ "$state" != running ] || state=interrupted
    fi
  fi
  if [ "$state" = queued ]; then
    created=$(cat "$DIR/created" 2>/dev/null || echo 0)
    if [ "$(( $(date +%s) - created ))" -gt 30 ]; then state=interrupted; fi
  fi
  emit jobId "$JOB_ID"; emit jobState "$state"
  emit jobExit "$(cat "$DIR/exit" 2>/dev/null || true)"
  emit jobLog "$(tail -c 6000 "$DIR/output" 2>/dev/null || true)"
  emit jobError "$(tail -c 6000 "$DIR/error" 2>/dev/null || true)"
  exit 0
fi

# Read-only detection. Prefer the installation actually serving the configured port.
LAUNCHER=""
METHOD=unknown
PREFIX=""
REAL=""
VERSION_NOW=""
RUN_VERSION=""
RUN_EXE=""
RESTART_REQUIRED=0
REASON=""
OWNED=0
PID=""
PROCESS_ID=""
VERIFIED=0
LISTENERS=""
if command -v ss >/dev/null 2>&1; then
  LISTENERS=$(ss -H -ltnp "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9][0-9]*' | cut -d= -f2 | sort -u || true)
fi
RECORDED=$(cat "$PID_FILE" 2>/dev/null || true)
case "$RECORDED" in ''|0|1|*[!0-9]*) RECORDED="";; esac
if [ -n "$RECORDED" ] && kill -0 "$RECORDED" 2>/dev/null; then
  current_id=$(identity "$RECORDED" || true)
  saved_id=$(cat "$PID_FILE.identity" 2>/dev/null || true)
  if [ -n "$current_id" ] && [ "$current_id" = "$saved_id" ]; then OWNED=1; fi
  # Legacy PID files cannot establish process ownership; listeners identify installations only.
  if [ "$OWNED" = 1 ]; then
    PID=$RECORDED
    PROCESS_ID=$current_id
    RUN_EXE=$(readlink "/proc/$PID/exe" 2>/dev/null || true)
    if [ "$current_id" = "$saved_id" ]; then
      candidate=$(cat "$STARTED" 2>/dev/null || true)
      if [ -n "$candidate" ] && [ -x "$candidate" ]; then LAUNCHER=$candidate; VERIFIED=1; fi
      RUN_VERSION=$(cat "$PID_FILE.version" 2>/dev/null || true)
    fi
  fi
fi
if [ -z "$RUN_EXE" ]; then
  for listener in $LISTENERS; do
    candidate=$(readlink "/proc/$listener/exe" 2>/dev/null || true)
    case "$candidate" in *code-server*/lib/node|*code-server*/lib/node' (deleted)') RUN_EXE=$candidate; break;; esac
  done
fi
RUN_PATH=${RUN_EXE%' (deleted)'}
case "$RUN_PATH" in *'/lib/node')
  candidate="${RUN_PATH%/lib/node}/bin/code-server"
  if [ -z "$LAUNCHER" ] && [ -x "$candidate" ]; then LAUNCHER=$candidate; VERIFIED=1; fi;;
esac
if [ -z "$LAUNCHER" ]; then
  candidate=$(cat "$PREFERRED" 2>/dev/null || true)
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then LAUNCHER=$candidate
  else LAUNCHER=$(command -v code-server 2>/dev/null || true); fi
  responding=unknown
  if command -v curl >/dev/null 2>&1; then
    code=$(curl -sS --max-time 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)
    if [ "$code" = 000 ] || [ -z "$code" ]; then responding=no; else responding=yes; fi
  elif command -v wget >/dev/null 2>&1; then
    if wget -S -O /dev/null -T 2 "http://127.0.0.1:$PORT/" 2>&1 | grep -q 'HTTP/'; then responding=yes; else responding=no; fi
  fi
  if [ -n "$LAUNCHER" ] && [ "$responding" = no ] && [ -z "$LISTENERS" ]; then
    VERIFIED=1
    REASON='Server is offline; managing the executable configured for the next start.'
  else
    REASON='The serving process could not be linked to this executable. Update is disabled.'
  fi
fi
if [ -n "$LAUNCHER" ]; then
  REAL=$(canonical "$LAUNCHER")
  VERSION_NOW=$(version_of "$LAUNCHER" || true)
  if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -S "$REAL" 2>/dev/null | grep -q '^code-server\(:[^:]*\)\?: '; then
    METHOD=deb
  elif command -v rpm >/dev/null 2>&1 && [ "$(rpm -qf --qf '%{NAME}' "$REAL" 2>/dev/null || true)" = code-server ]; then
    METHOD=rpm
  else
    root=$(dirname "$(dirname "$REAL")")
    case "$root" in */lib/code-server-*)
      possible=$(dirname "$(dirname "$root")")
      if [ -x "$possible/bin/code-server" ] && [ -f "$root/package.json" ] && [ -x "$root/lib/node" ]; then
        resolved=$(canonical "$possible/bin/code-server")
        case "$resolved" in "$possible"/lib/code-server-*/bin/code-server)
          METHOD=standalone; PREFIX=$possible; LAUNCHER="$possible/bin/code-server"
          REAL=$resolved; VERSION_NOW=$(version_of "$LAUNCHER" || true);;
        esac
      fi;;
    esac
    if [ "$METHOD" = unknown ] && command -v npm >/dev/null 2>&1; then
      possible=$(npm prefix -g 2>/dev/null || true)
      if [ -n "$possible" ]; then
        case "$REAL" in "$possible"/lib/node_modules/code-server/*)
          METHOD=npm; PREFIX=$possible;;
        esac
      fi
    fi
  fi
fi
if [ "$(id -u)" != 0 ]; then
  case "$METHOD" in
    deb|rpm) ADMIN=1;;
    standalone) if [ ! -w "$PREFIX/lib" ] || [ ! -w "$PREFIX/bin" ]; then ADMIN=1; fi;;
    npm) if [ ! -w "$PREFIX/lib/node_modules" ] || [ ! -w "$PREFIX/bin" ]; then ADMIN=1; fi;;
  esac
fi
if [ "$(uname -s)" != Linux ]; then METHOD=unknown; REASON='Automatic updates currently support Linux hosts only.'; fi
if [ -f /.dockerenv ]; then METHOD=unknown; REASON='Container installations must be updated through their image/deployment manager.'; fi
if [ "$METHOD" = unknown ] && [ -z "$REASON" ]; then REASON='Unknown or manually unpacked installation; automatic replacement is disabled.'; fi

case "$RUN_EXE" in *' (deleted)')
  RESTART_REQUIRED=1
  REASON='Server files were replaced while the old process is still running. Restart the server before opening the editor.';;
esac

AVAILABLE=""
if [ "$METHOD" = standalone ]; then
  for candidate in "$PREFIX"/lib/code-server-*; do
    local_version=${candidate##*/code-server-}
    if printf '%s' "$local_version" | grep -Eq '^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$' && [ -x "$candidate/bin/code-server" ]; then AVAILABLE="$AVAILABLE $local_version"; fi
  done
fi
ARCH=$(uname -m)
case "$ARCH" in x86_64|amd64) ARCH=amd64;;aarch64|arm64)ARCH=arm64;;esac
TOOLS=1
for tool in flock sha256sum mktemp; do command -v "$tool" >/dev/null 2>&1 || TOOLS=0; done
report() {
  emit launcher "$LAUNCHER"; emit realPath "$REAL"; emit method "$METHOD"; emit prefix "$PREFIX"
  emit version "$VERSION_NOW"; emit runningVersion "$RUN_VERSION"; emit runningExecutable "$RUN_EXE"
  emit restartRequired "$RESTART_REQUIRED"
  emit pid "$PID"; emit processIdentity "$PROCESS_ID"; emit canRestart "$OWNED"
  emit needsAdmin "$ADMIN"; emit targetVerified "$VERIFIED"; emit reason "$REASON"
  emit lastJob "$(cat "$CACHE/code-server-$PORT.last-job" 2>/dev/null || true)"
  emit availableVersions "$AVAILABLE"; emit arch "$ARCH"; emit toolsReady "$TOOLS"
  emit previousVersion "$(cat "$CACHE/code-server-$PORT.previous-version" 2>/dev/null || true)"
}
if [ "$OP" = inspect ]; then report; exit 0; fi
# Serialize update/restart/startup across this SSH account, including different ports.
lock_operations() {
  command -v flock >/dev/null 2>&1 || fail 'flock is required to coordinate server operations.'
  mkdir -p "$CACHE"
  exec 8>"$CACHE/server-operation.lock"
  flock -n 8 || fail 'Another server operation is already running. Wait before retrying.'
}
if [ "$OP" = restart ]; then
  lock_operations
  [ "$OWNED" = 1 ] && [ -n "$PID" ] && [ -n "$PROCESS_ID" ] || fail 'This process is not verified as a DeXCode-owned server. Restart it using its service manager.'
  [ "$PID" = "${EXPECTED_PID:-}" ] && [ "$PROCESS_ID" = "${EXPECTED_PROCESS_ID:-}" ] || fail 'The server process changed. Refresh before restarting.'
  [ "$(identity "$PID" || true)" = "$PROCESS_ID" ] || fail 'The process identity changed.'
  kill -TERM "$PID"
  i=0
  while kill -0 "$PID" 2>/dev/null && [ "$i" -lt 15 ]; do
    state=$(sed 's/^[0-9][0-9]* (.*) //' "/proc/$PID/stat" 2>/dev/null | awk '{print $1}')
    [ "$state" = Z ] && break
    sleep 1; i=$((i+1))
  done
  state=$(sed 's/^[0-9][0-9]* (.*) //' "/proc/$PID/stat" 2>/dev/null | awk '{print $1}' || true)
  if kill -0 "$PID" 2>/dev/null && [ "$state" != Z ]; then fail 'The server did not stop. No force-kill was attempted.'; fi
  if command -v ss >/dev/null 2>&1 && [ -n "$(ss -H -ltn "sport = :$PORT" 2>/dev/null || true)" ]; then
    fail 'A process is still listening on the server port. Restart it through its service manager.'
  fi
  rm -f "$PID_FILE" "$PID_FILE.identity" "$PID_FILE.version" "$STARTED"
  emit stopped 1
  exit 0
fi
[ "$OP" = update ] || fail 'Unknown management operation.'
[ "$RESTART_REQUIRED" = 0 ] || fail "$REASON"
[ "$VERIFIED" = 1 ] || fail "$REASON"
[ "$METHOD" != unknown ] || fail "$REASON"
[ "$LAUNCHER" = "${EXPECTED_LAUNCHER:-}" ] && [ "$REAL" = "${EXPECTED_REAL:-}" ] && [ "$METHOD" = "${EXPECTED_METHOD:-}" ] && [ "$VERSION_NOW" = "${EXPECTED_VERSION:-}" ] || fail 'Installation changed since inspection. Refresh before updating.'
printf '%s' "${VERSION:-}" | grep -Eq '^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$' || fail 'Enter a stable version such as 4.136.2.'
if [ "$METHOD" != npm ]; then
  case "$ARCH" in amd64|arm64) :;; *) fail 'No official release package is supported for this CPU architecture.';; esac
fi
[ "$TOOLS" = 1 ] || fail 'flock, sha256sum and mktemp are required for managed updates.'
if [ "$ADMIN" = 1 ] && [ "${ALLOW_SUDO:-0}" != 1 ]; then fail 'Administrator authorization is required.'; fi
lock_operations
key=$(printf '%s:%s' "$METHOD" "${PREFIX:-$REAL}" | cksum | awk '{print $1}')
LOCK="$CACHE/server-update-$key.lock"
exec 9>"$LOCK"
flock -n 9 || fail 'Another update is already running for this installation.'
[ "$(canonical "$LAUNCHER")" = "$REAL" ] && [ "$(version_of "$LAUNCHER" || true)" = "$VERSION_NOW" ] || fail 'Installation changed while waiting for the update lock.'
TMP=$(mktemp -d "${TMPDIR:-/tmp}/dexcode-server.XXXXXXXX")
BASE="https://github.com/coder/code-server/releases/download/v$VERSION"
LOCAL_TARGET=0
if [ "$METHOD" = standalone ] && [ -x "$PREFIX/lib/code-server-$VERSION/bin/code-server" ]; then
  [ "$(version_of "$PREFIX/lib/code-server-$VERSION/bin/code-server" || true)" = "$VERSION" ] || fail 'The existing version directory is damaged or incompatible. Inspect it manually.'
  LOCAL_TARGET=1
fi
if [ "$METHOD" != npm ] && [ "$LOCAL_TARGET" = 0 ]; then
  case "$METHOD" in
    standalone) ASSET="code-server-$VERSION-linux-$ARCH.tar.gz";;
    deb) ASSET="code-server_${VERSION}_$ARCH.deb";;
    rpm) ASSET="code-server-$VERSION-$ARCH.rpm";;
  esac
  [ "$ASSET" = "${EXPECTED_ASSET:-}" ] || fail 'Release asset or CPU architecture changed. Refresh before updating.'
  printf '%s' "${EXPECTED_SHA256:-}" | grep -Eq '^[0-9a-fA-F]{64}$' || fail 'The release has no verifiable SHA-256 digest. Only already-installed versions can be selected without a download.'
  fetch "$BASE/$ASSET" "$TMP/artifact"
  FILE="$TMP/artifact"
  if [ "$ADMIN" = 1 ]; then
    PROTECTED=$(admin mktemp -d /var/tmp/dexcode-server.XXXXXXXX)
    case "$PROTECTED" in /var/tmp/dexcode-server.*) :;; *) fail 'Unexpected privileged staging path.';; esac
    admin cp "$FILE" "$PROTECTED/artifact"
    FILE="$PROTECTED/artifact"
  fi
  actual_hash=$(admin sha256sum "$FILE" | awk '{print $1}')
  [ "$actual_hash" = "$EXPECTED_SHA256" ] || fail 'Release checksum mismatch. Nothing was installed.'
fi
case "$METHOD" in
  standalone)
    TARGET="$PREFIX/lib/code-server-$VERSION"
    if [ "$LOCAL_TARGET" = 0 ]; then
      [ ! -e "$TARGET" ] || fail 'The target version directory already exists. Inspect it manually.'
      STAGE=$(admin mktemp -d "$PREFIX/lib/.dexcode-stage.XXXXXXXX")
      admin tar --no-same-owner -xzf "$FILE" -C "$STAGE"
      PACKAGE="$STAGE/code-server-$VERSION-linux-$ARCH"
      [ "$(admin "$PACKAGE/bin/code-server" --version 2>/dev/null | sed -n '1{s/ .*//;p;}')" = "$VERSION" ] || fail 'The downloaded code-server cannot run on this host. Existing launcher was kept.'
      admin mv -T "$PACKAGE" "$TARGET"
    fi
    LINK_TMP="$PREFIX/bin/.dexcode-link-$$"
    admin ln -s "$TARGET/bin/code-server" "$LINK_TMP"
    admin mv -Tf "$LINK_TMP" "$LAUNCHER"
    LINK_TMP="";;
  deb)
    [ "$(admin dpkg-deb -f "$FILE" Package)" = code-server ] || fail 'Unexpected package name.'
    admin env DEBIAN_FRONTEND=noninteractive dpkg --force-confold -i "$FILE";;
  rpm)
    [ "$(admin rpm -qp --qf '%{NAME}' "$FILE")" = code-server ] || fail 'Unexpected package name.'
    admin rpm -U --oldpackage "$FILE";;
  npm)
    NPM=$(command -v npm)
    admin "$NPM" install --global --prefix "$PREFIX" "code-server@$VERSION";;
esac
ACTUAL=$(version_of "$LAUNCHER" || true)
[ "$ACTUAL" = "$VERSION" ] || fail 'The installer finished, but the target executable does not report the requested version. Inspect the server before retrying.'
umask 077
printf '%s\n' "$VERSION_NOW" > "$CACHE/code-server-$PORT.previous-version"
printf '%s\n' "$LAUNCHER" > "$PREFERRED.tmp"
mv -f "$PREFERRED.tmp" "$PREFERRED"
emit updatedVersion "$ACTUAL"
emit launcher "$LAUNCHER"
