#!/usr/bin/env bash
set -euo pipefail

WINDOW_SECONDS="${1:-30}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$WINDOW_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [window_seconds] [command ...]" >&2
    exit 2
    ;;
esac

shift || true
COMMANDS=("$@")
if [[ "${#COMMANDS[@]}" -eq 0 ]]; then
  COMMANDS=(
    open_camera
    open_front_camera
    open_rear_camera
    take_photo
    home
  )
fi

echo "Verifying bare command window timeout (${WINDOW_SECONDS}s)."
JARVIS_TIMEOUT_QUIET=1 "$SCRIPT_DIR/jarvis-command-window-timeout.sh" "$WINDOW_SECONDS"

for command in "${COMMANDS[@]}"; do
  echo "Verifying command '$command' timeout (${WINDOW_SECONDS}s)."
  JARVIS_TIMEOUT_QUIET=1 "$SCRIPT_DIR/jarvis-command-window-timeout.sh" "$WINDOW_SECONDS" "$command"
done

echo "PASS: all command window timeout checks passed for ${WINDOW_SECONDS}s."
