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

function displayMs(raw) {
  if (raw == "") return "-"
  return raw "ms"
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
  speechParse = 0
  if (parsed[id] > 0 && androidSpeechBegin[id] > 0 && parsed[id] >= androidSpeechBegin[id]) {
    speechParse = parsed[id] - androidSpeechBegin[id]
  }
  listenReady = 0
  if (androidReady[id] > 0 && androidListenStart[id] > 0 && androidReady[id] >= androidListenStart[id]) {
    listenReady = androidReady[id] - androidListenStart[id]
  }
  speechAccess = 0
  if (access[id] > 0 && androidSpeechBegin[id] > 0 && access[id] >= androidSpeechBegin[id]) {
    speechAccess = access[id] - androidSpeechBegin[id]
  }
  commandAccess = 0
  if (access[id] > 0 && parsed[id] > 0 && access[id] >= parsed[id]) {
    commandAccess = access[id] - parsed[id]
  }
  ownerGate = ownerGateElapsed[id] + 0
  displayCandidateIndex = parsedCandidateIndex[id]
  if (displayCandidateIndex == "") displayCandidateIndex = "-"
  displayParsedSource = parsedSource[id]
  if (displayParsedSource == "") displayParsedSource = "-"
  displayBiasCount = sttBiasCount[id]
  if (displayBiasCount == "") displayBiasCount = "-"
  displayMinMs = sttMinMs[id]
  if (displayMinMs == "") displayMinMs = "-"
  displayPossibleSilenceMs = sttPossibleSilenceMs[id]
  if (displayPossibleSilenceMs == "") displayPossibleSilenceMs = "-"
  displayCompleteSilenceMs = sttCompleteSilenceMs[id]
  if (displayCompleteSilenceMs == "") displayCompleteSilenceMs = "-"
  printf "trace=%s total=%dms path=%s command=%s status=%s owner_gate=%dms listen=%dms listen_ready=%dms parsed=%dms parsed_source=%s parsed_candidate_index=%s speech_parse=%dms access=%dms speech_access=%dms command_access=%dms bus=%dms stt_bias_count=%s stt_min_ms=%s stt_possible_silence_ms=%s stt_complete_silence_ms=%s\n", id, total[id], displayPath, displayCommand, displayStatus, ownerGate, androidListenStart[id], listenReady, parsed[id], displayParsedSource, displayCandidateIndex, speechParse, access[id], speechAccess, commandAccess, bus[id], displayBiasCount, displayMinMs, displayPossibleSilenceMs, displayCompleteSilenceMs

  if (ownerAcceptance[id] != "") {
    printf "  owner_acceptance=%s owner_auth_speech=%sms", ownerAcceptance[id], ownerAuthSpeech[id]
    if (ownerGateElapsed[id] != "") printf " owner_gate_elapsed=%sms", ownerGateElapsed[id]
    if (ownerAttempts[id] != "") printf " owner_attempts=%s", ownerAttempts[id]
    if (ownerProfileEmbeddings[id] != "") printf " profile_embeddings=%s", ownerProfileEmbeddings[id]
    printf "\n"
  }
  if (ownerEndpoint[id] != "") {
    printf "  owner_endpoint=%s owner_elapsed=%sms speech=%sms samples=%sms", ownerEndpoint[id], ownerElapsed[id], ownerSpeech[id], ownerSamples[id]
    if (ownerPeakRms[id] != "") printf " peak_rms=%s mean_rms=%s asr_gain=%s", ownerPeakRms[id], ownerMeanRms[id], ownerAsrGain[id]
    printf "\n"
  }
  if (ownerText[id] != "") {
    printf("  owner_text=%s\n", ownerText[id])
  }
  if (activationEndpoint[id] != "") {
    printf "  activation_endpoint=%s activation_elapsed=%sms speech=%sms silence=%sms", activationEndpoint[id], activationElapsed[id], activationSpeech[id], activationSilence[id]
    if (activationPeakRms[id] != "") printf " peak_rms=%s mean_rms=%s asr_gain=%s", activationPeakRms[id], activationMeanRms[id], activationAsrGain[id]
    printf "\n"
  }
  if (activationText[id] != "") {
    printf("  activation_text=%s\n", activationText[id])
  }
  if (activationOwnerAccepted[id] != "") {
    printf "  activation_owner accepted=%s acceptance=%s score=%s speech=%sms peak_rms=%s reason=%s\n", activationOwnerAccepted[id], activationOwnerAcceptance[id], activationOwnerScore[id], activationOwnerSpeech[id], activationOwnerPeakRms[id], activationOwnerReason[id]
  }
  if (localText[id] != "") {
    printf("  local_text=%s\n", localText[id])
  }
  if (localEndpoint[id] != "") {
    printf "  local_endpoint=%s local_elapsed=%sms speech=%sms silence=%sms", localEndpoint[id], localElapsed[id], localSpeech[id], localSilence[id]
    if (localPeakRms[id] != "") printf " peak_rms=%s mean_rms=%s asr_gain=%s", localPeakRms[id], localMeanRms[id], localAsrGain[id]
    printf "\n"
  }
  if (androidReady[id] != "" || androidSpeechBegin[id] != "" || androidError[id] != "") {
    displayError = androidError[id]
    if (displayError == "") displayError = "-"
    printf "  android_ready=%s speech_begin=%s speech_end=%s error=%s\n", displayMs(androidReady[id]), displayMs(androidSpeechBegin[id]), displayMs(androidSpeechEnd[id]), displayError
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
  if (contains($0, "engine=local_activation_asr")) addPath(trace, "local_activation_asr")
  if (contains($0, "engine=local_asr")) addPath(trace, "local_asr")
  if (contains($0, "engine=android_stt")) addPath(trace, "android_stt")
  if (event == "fallback_to_android") addPath(trace, "android_stt")

  cmd = value($0, "command")
  if (cmd != "") command[trace] = cmd

  if (event == "owner_authorized") {
    ownerAcceptance[trace] = value($0, "acceptance")
    ownerAuthSpeech[trace] = value($0, "speechMs")
    ownerGateElapsed[trace] = value($0, "ownerElapsedMs")
    ownerAttempts[trace] = value($0, "ownerAttempts")
    ownerProfileEmbeddings[trace] = value($0, "profileEmbeddings")
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
  if (event == "activation_asr_complete") {
    activationEndpoint[trace] = value($0, "endpoint")
    activationElapsed[trace] = value($0, "elapsedMs")
    activationSpeech[trace] = value($0, "speechMs")
    activationSilence[trace] = value($0, "trailingMs")
    activationPeakRms[trace] = value($0, "peakRms")
    activationMeanRms[trace] = value($0, "meanRms")
    activationAsrGain[trace] = value($0, "asrGain")
    text = tailValue($0, "text")
    if (text != "") activationText[trace] = text
  }
  if (event == "activation_owner_verified") {
    activationOwnerAccepted[trace] = value($0, "accepted")
    activationOwnerAcceptance[trace] = value($0, "acceptance")
    activationOwnerScore[trace] = value($0, "score")
    activationOwnerSpeech[trace] = value($0, "speechMs")
    activationOwnerPeakRms[trace] = value($0, "peakRms")
    activationOwnerReason[trace] = value($0, "reason")
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
  if (event == "listen_start" && contains($0, "engine=android_stt")) {
    if (totalRaw != "" && androidListenStart[trace] == "") androidListenStart[trace] = millis(totalRaw)
    if (sttBiasCount[trace] == "") sttBiasCount[trace] = value($0, "biasCount")
    if (sttMinMs[trace] == "") sttMinMs[trace] = value($0, "minMs")
    if (sttPossibleSilenceMs[trace] == "") sttPossibleSilenceMs[trace] = value($0, "possibleSilenceMs")
    if (sttCompleteSilenceMs[trace] == "") sttCompleteSilenceMs[trace] = value($0, "completeSilenceMs")
  }
  if (event == "ready_for_speech" && totalRaw != "" && androidReady[trace] == "") androidReady[trace] = millis(totalRaw)
  if (event == "speech_begin" && totalRaw != "" && androidSpeechBegin[trace] == "") androidSpeechBegin[trace] = millis(totalRaw)
  if (event == "speech_end" && totalRaw != "" && androidSpeechEnd[trace] == "") androidSpeechEnd[trace] = millis(totalRaw)
  if (event == "speech_error") androidError[trace] = value($0, "code")
  if (event == "partial_results" || event == "final_results") androidText[trace] = $0

  if (event == "command_parsed" && totalRaw != "") {
    parsed[trace] = millis(totalRaw)
    source = value($0, "source")
    if (source != "") parsedSource[trace] = source
    candidateIndex = value($0, "candidateIndex")
    if (candidateIndex != "") parsedCandidateIndex[trace] = candidateIndex
  }
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
  if (orderCount == 0) {
    print "no_traces=1"
    exit
  }
  for (i = 1; i <= orderCount; i++) printTrace(order[i])
}
'
