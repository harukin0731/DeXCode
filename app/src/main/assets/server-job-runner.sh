#!/bin/sh
set -u
umask 077
printf '%s\n' "$$" > "$JOB_DIR/pid"
start=$(sed 's/^[0-9][0-9]* (.*) //' "/proc/$$/stat" | awk '{print $20}')
printf '%s:%s\n' "$(cat /proc/sys/kernel/random/boot_id)" "$start" > "$JOB_DIR/identity"
printf 'running\n' > "$JOB_DIR/state.tmp"
mv -f "$JOB_DIR/state.tmp" "$JOB_DIR/state"
mkfifo "$JOB_DIR/.outpipe" "$JOB_DIR/.errpipe"
# Bounded output even for a very verbose package manager; preserve the final diagnostics/protocol.
tail -c 65536 < "$JOB_DIR/.outpipe" > "$JOB_DIR/output" &
out_reader=$!
tail -c 65536 < "$JOB_DIR/.errpipe" > "$JOB_DIR/error" &
err_reader=$!
sh "$JOB_DIR/payload.sh" > "$JOB_DIR/.outpipe" 2> "$JOB_DIR/.errpipe"
result=$?
wait "$out_reader" || true
wait "$err_reader" || true
rm -f "$JOB_DIR/.outpipe" "$JOB_DIR/.errpipe"
printf '%s\n' "$result" > "$JOB_DIR/exit"
if [ "$result" = 0 ]; then printf 'completed\n' > "$JOB_DIR/state.tmp"
else printf 'failed\n' > "$JOB_DIR/state.tmp"; fi
mv -f "$JOB_DIR/state.tmp" "$JOB_DIR/state"
