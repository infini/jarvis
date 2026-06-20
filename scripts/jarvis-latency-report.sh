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

function addPath(id, name, key) {
  if (id == "" || name == "") return

  key = id SUBSEP name
  if (key in pathSeen) return

  pathSeen[key] = 1
  if (pathText[id] == "") {
    pathText[id] = name
  } else {
    pathText[id] = pathText[id] "->" name
  }
}

function printTrace(id) {
  if (id == "" || !seen[id]) return

  displayPath = pathText[id]
  if (displayPath == "") displayPath = "unknown"

  status = finalEvent[id]
  if (status == "") status = lastEvent[id]

  displayCommand = command[id]
  if (displayCommand == "") displayCommand = "-"
  displayStatus = status
  if (displayStatus == "") displayStatus = "-"
  printf "trace=%s total=%dms path=%s command=%s status=%s parsed=%dms access=%dms bus=%dms\n", id, total[id], displayPath, displayCommand, displayStatus, parsed[id], access[id], bus[id]

  if (ownerAcceptance[id] != "") {
    printf "  owner_acceptance=%s owner_auth_speech=%sms\n", ownerAcceptance[id], ownerAuthSpeech[id]
  }
  if (ownerEndpoint[id] != "") {
    printf "  owner_endpoint=%s owner_elapsed=%sms speech=%sms samples=%sms", ownerEndpoint[id], ownerElapsed[id], ownerSpeech[id], ownerSamples[id]
    if (ownerPeakRms[id] != "") printf " peak_rms=%s mean_rms=%s asr_gain=%s", ownerPeakRms[id], ownerMeanRms[id], ownerAsrGain[id]
    printf "\n"
  }
  if (ownerText[id] != "") {
    printf("  owner_text=%s\n", ownerText[id])
  }
  if (localText[id] != "") {
    printf("  local_text=%s\n", localText[id])
  }
  if (localEndpoint[id] != "") {
    printf "  local_endpoint=%s local_elapsed=%sms speech=%sms silence=%sms", localEndpoint[id], localElapsed[id], localSpeech[id], localSilence[id]
    if (localPeakRms[id] != "") printf " peak_rms=%s mean_rms=%s asr_gain=%s", localPeakRms[id], localMeanRms[id], localAsrGain[id]
    printf "\n"
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

  if (contains($0, "engine=owner_audio_asr")) addPath(trace, "owner_audio_asr")
  if (contains($0, "engine=local_asr")) addPath(trace, "local_asr")
  if (contains($0, "engine=android_stt")) addPath(trace, "android_stt")
  if (event == "fallback_to_android") addPath(trace, "android_stt")

  cmd = value($0, "command")
  if (cmd != "") command[trace] = cmd

  if (event == "owner_authorized") {
    ownerAcceptance[trace] = value($0, "acceptance")
    ownerAuthSpeech[trace] = value($0, "speechMs")
  }
  if (event == "owner_audio_asr_start") {
    ownerSamples[trace] = value($0, "samplesMs")
  }
  if (event == "owner_audio_asr_complete") {
    ownerEndpoint[trace] = value($0, "endpoint")
    ownerElapsed[trace] = value($0, "elapsedMs")
    ownerSpeech[trace] = value($0, "speechMs")
    ownerPeakRms[trace] = value($0, "peakRms")
    ownerMeanRms[trace] = value($0, "meanRms")
    ownerAsrGain[trace] = value($0, "asrGain")
    text = tailValue($0, "text")
    if (text != "") ownerText[trace] = text
  }
  if (event == "local_partial") localText[trace] = tailValue($0, "text")
  if (event == "local_complete") {
    localEndpoint[trace] = value($0, "endpoint")
    localElapsed[trace] = value($0, "elapsedMs")
    localSpeech[trace] = value($0, "speechMs")
    localSilence[trace] = value($0, "silenceMs")
    localPeakRms[trace] = value($0, "peakRms")
    localMeanRms[trace] = value($0, "meanRms")
    localAsrGain[trace] = value($0, "asrGain")
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
