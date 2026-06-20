#!/usr/bin/env bash
set -euo pipefail

LOG_SOURCE="${1:-}"

if [[ -n "$LOG_SOURCE" ]]; then
  LOG_CMD=(cat "$LOG_SOURCE")
else
  LOG_CMD=(adb logcat -d -v time -s JarvisLatency)
fi

"${LOG_CMD[@]}" | awk '
function value(line, key, marker, start, rest, end) {
  marker = key "="
  start = index(line, marker)
  if (start == 0) return ""
  rest = substr(line, start + length(marker))
  end = index(rest, " ")
  if (end == 0) return rest
  return substr(rest, 1, end - 1)
}

function tailValue(line, key, marker, start) {
  marker = key "="
  start = index(line, marker)
  if (start == 0) return ""
  return substr(line, start + length(marker))
}

function contains(line, needle) {
  return index(line, needle) > 0
}

function startsWith(text, prefix) {
  return substr(text, 1, length(prefix)) == prefix
}

function endsWith(text, suffix) {
  return substr(text, length(text) - length(suffix) + 1) == suffix
}

function millis(raw) {
  sub(/ms$/, "", raw)
  return raw + 0
}

function printTrace(id) {
  if (id == "" || !seen[id]) return

  path = engine[id]
  if (path == "") path = "unknown"
  if (fallback[id] != "") path = path "->" fallback[id]

  status = finalEvent[id]
  if (status == "") status = lastEvent[id]

  displayCommand = command[id]
  if (displayCommand == "") displayCommand = "-"
  displayStatus = status
  if (displayStatus == "") displayStatus = "-"
  printf "trace=%s total=%dms path=%s command=%s status=%s parsed=%dms access=%dms bus=%dms\n", id, total[id], path, displayCommand, displayStatus, parsed[id], access[id], bus[id]

  if (localText[id] != "") {
    printf("  local_text=%s\n", localText[id])
  }
  if (localEndpoint[id] != "") {
    printf "  local_endpoint=%s local_elapsed=%sms speech=%sms silence=%sms\n", localEndpoint[id], localElapsed[id], localSpeech[id], localSilence[id]
  }
  if (androidText[id] != "") {
    printf("  android_text=%s\n", androidText[id])
  }
}

{
  trace = value($0, "trace")
  if (trace == "" || trace == "none") next

  if (!(trace in orderIndex)) {
    order[++orderCount] = trace
    orderIndex[trace] = orderCount
  }
  seen[trace] = 1

  event = value($0, "event")
  if (event != "") lastEvent[trace] = event

  totalRaw = value($0, "total")
  if (totalRaw != "") total[trace] = millis(totalRaw)

  if (contains($0, "engine=local_asr")) engine[trace] = "local_asr"
  if (contains($0, "engine=android_stt") && engine[trace] == "") engine[trace] = "android_stt"
  if (event == "fallback_to_android") fallback[trace] = "android_stt"

  cmd = value($0, "command")
  if (cmd != "") command[trace] = cmd

  if (event == "local_partial") localText[trace] = tailValue($0, "text")
  if (event == "local_complete") {
    localEndpoint[trace] = value($0, "endpoint")
    localElapsed[trace] = value($0, "elapsedMs")
    localSpeech[trace] = value($0, "speechMs")
    localSilence[trace] = value($0, "silenceMs")
    text = tailValue($0, "text")
    if (text != "") localText[trace] = text
  }
  if (event == "partial_results" || event == "final_results") androidText[trace] = $0

  if (event == "command_parsed" && totalRaw != "") parsed[trace] = millis(totalRaw)
  if (event == "accessibility_command_received") {
    accessRaw = value($0, "totalMs")
    busRaw = value($0, "busDelayMs")
    if (accessRaw != "") access[trace] = millis(accessRaw)
    if (busRaw != "") bus[trace] = millis(busRaw)
  }
  isFinal = event == "command_complete"
  isFinal = isFinal || startsWith(event, "command_window_")
  isFinal = isFinal || endsWith(event, "_unavailable")
  isFinal = isFinal || endsWith(event, "_retry")
  if (isFinal) {
    finalEvent[trace] = event
  }
}

END {
  for (i = 1; i <= orderCount; i++) printTrace(order[i])
}
'
