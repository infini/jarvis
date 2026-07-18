package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.text.format.DateFormat
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CommandVoiceSamplePanel(
    private val activity: Activity,
    private val handler: Handler,
    private val onSamplesChanged: () -> Unit,
) {
    private var activeEntry: CommandCatalog.Entry? = null
    private var pendingPermissionEntry: CommandCatalog.Entry? = null
    private var sampleCountView: TextView? = null
    private var sampleStatusView: TextView? = null
    private var sampleProgress: ProgressBar? = null
    private var sampleRecordButton: Button? = null
    private var sampleDeleteButton: Button? = null
    private var pendingSampleStart: Runnable? = null
    private val sampleRecorder by lazy {
        CommandVoiceSampleRecorder(
            context = activity.applicationContext,
            postToMain = { action -> activity.runOnUiThread(action) },
            onProgress = { percent ->
                val displayed = if (percent >= 100) 100 else (percent / 20) * 20
                if (sampleProgress?.progress != displayed) sampleProgress?.progress = displayed
            },
            onStatus = { status ->
                if (shouldDisplayProgressStatus(status)) sampleStatusView?.text = status
            },
            onCompleted = { info ->
                activeEntry?.let(::refreshSampleViews)
                onSamplesChanged()
                Toast.makeText(
                    activity,
                    "음성 샘플 저장됨: ${info.durationMs}ms",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onFailed = { message ->
                sampleStatusView?.text = message
                sampleRecordButton?.text = "내 발음 녹음하기"
                sampleProgress?.visibility = View.GONE
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    fun build(entry: CommandCatalog.Entry, detailText: String): ScrollView {
        activeEntry = entry
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
        }

        root.addView(
            TextView(activity).apply {
                text = detailText
                textSize = 14f
                setTextColor(Color.rgb(32, 38, 44))
            },
            matchWrap(bottomMargin = dp(14)),
        )

        root.addView(sectionLabel("내 발음으로 인식 개선"), matchWrap(bottomMargin = dp(6)))
        sampleCountView = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.rgb(32, 38, 44))
            setBackgroundColor(Color.rgb(246, 247, 249))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(sampleCountView, matchWrap(bottomMargin = dp(8)))

        sampleStatusView = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.rgb(76, 86, 96))
        }
        root.addView(sampleStatusView, matchWrap(bottomMargin = dp(8)))

        sampleProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(sampleProgress, matchWrap(bottomMargin = dp(8)))

        sampleRecordButton = Button(activity).apply {
            text = "내 발음 녹음하기"
            setAllCaps(false)
            setOnClickListener { toggleSampleRecording(entry) }
        }
        root.addView(sampleRecordButton, matchWrap(bottomMargin = dp(8)))

        sampleDeleteButton = Button(activity).apply {
            text = "이 명령의 녹음 삭제"
            setAllCaps(false)
            setOnClickListener { confirmDeleteSamples(entry) }
        }
        root.addView(sampleDeleteButton, matchWrap(bottomMargin = 0))

        refreshSampleViews(entry)
        return ScrollView(activity).apply { addView(root) }
    }

    fun onDismiss(entry: CommandCatalog.Entry) {
        cancelPendingSampleStart()
        if (sampleRecorder.recordingCommandId == entry.commandId) {
            sampleRecorder.stop()
        }
        if (activeEntry?.commandId == entry.commandId) {
            activeEntry = null
            sampleCountView = null
            sampleStatusView = null
            sampleProgress = null
            sampleRecordButton = null
            sampleDeleteButton = null
        }
        if (pendingPermissionEntry?.commandId == entry.commandId) {
            pendingPermissionEntry = null
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_RECORD_AUDIO) return

        val entry = pendingPermissionEntry ?: return
        pendingPermissionEntry = null
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            sampleStatusView?.text = "마이크 권한이 없어 샘플 녹음을 시작할 수 없습니다."
            Toast.makeText(activity, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (activeEntry?.commandId == entry.commandId) {
            startSampleRecording(entry)
        }
    }

    fun stop() {
        val wasRecording = sampleRecorder.isRecording || pendingSampleStart != null
        cancelPendingSampleStart()
        sampleRecorder.stop()
        activeEntry?.let(::refreshSampleViews)
        if (wasRecording) sampleStatusView?.text = "화면을 벗어나 녹음을 중지했습니다."
    }

    private fun toggleSampleRecording(entry: CommandCatalog.Entry) {
        if (sampleRecorder.isRecording) {
            sampleRecorder.stop()
            refreshSampleViews(entry)
            sampleStatusView?.text = activity.getString(R.string.sample_recording_stopped)
            return
        }

        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionEntry = entry
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        startSampleRecording(entry)
    }

    private fun startSampleRecording(entry: CommandCatalog.Entry) {
        if (JarvisVoiceService.isRunning) {
            sampleStatusView?.text = activity.getString(R.string.sample_recording_stop_jarvis)
            activity.stopService(Intent(activity, JarvisVoiceService::class.java))
            cancelPendingSampleStart()
            pendingSampleStart = Runnable {
                pendingSampleStart = null
                if (activeEntry?.commandId == entry.commandId && !activity.isFinishing && !activity.isDestroyed) {
                    beginSampleRecording(entry)
                }
            }.also { handler.postDelayed(it, SAMPLE_RECORDING_START_DELAY_MS) }
            return
        }

        beginSampleRecording(entry)
    }

    private fun beginSampleRecording(entry: CommandCatalog.Entry) {
        if (sampleRecorder.isRecording) return

        sampleRecordButton?.text = "녹음 중지"
        sampleProgress?.progress = 0
        sampleProgress?.visibility = View.VISIBLE
        sampleRecorder.start(entry, SAMPLE_RECORDING_DURATION_MS)
    }

    private fun confirmDeleteSamples(entry: CommandCatalog.Entry) {
        val summary = CommandVoiceSampleStore.summary(activity, entry.commandId)
        if (summary.count == 0) {
            Toast.makeText(activity, "삭제할 음성 샘플이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("음성 샘플 삭제")
            .setMessage("'${entry.title}' 샘플 ${summary.count}개를 삭제합니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                val deleted = CommandVoiceSampleStore.deleteSamples(activity, entry.commandId)
                refreshSampleViews(entry)
                onSamplesChanged()
                Toast.makeText(activity, "음성 샘플 ${deleted}개 삭제됨", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refreshSampleViews(entry: CommandCatalog.Entry) {
        val summary = CommandVoiceSampleStore.summary(activity, entry.commandId)
        sampleCountView?.text = buildString {
            appendLine(sampleSummaryText(summary))
            append(
                "권장: 명령어당 3개 이상, 최대 ${CommandVoiceSampleStore.MAX_SAMPLES_PER_COMMAND}개 보관. " +
                    "녹음할 때는 대표 명령 '${entry.phrases.first()}'를 말하세요.",
            )
            summary.lastSample?.let { last ->
                appendLine()
                append("최근 녹음: ${formatTimestamp(last.createdAtMs)} / ${last.durationMs}ms")
            }
        }
        sampleStatusView?.text = if (sampleRecorder.recordingCommandId == entry.commandId) {
            "녹음 중입니다."
        } else {
            "평소 발음으로 여러 번 녹음하면 기본 음성 인식이 놓친 명령을 보완할 수 있습니다."
        }
        sampleProgress?.progress = if (sampleRecorder.recordingCommandId == entry.commandId) {
            sampleProgress?.progress ?: 0
        } else {
            0
        }
        sampleProgress?.visibility = if (sampleRecorder.recordingCommandId == entry.commandId) {
            View.VISIBLE
        } else {
            View.GONE
        }
        sampleRecordButton?.text = if (sampleRecorder.recordingCommandId == entry.commandId) {
            "녹음 중지"
        } else {
            "내 발음 녹음하기"
        }
        sampleDeleteButton?.isEnabled = summary.count > 0 && !sampleRecorder.isRecording
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(90, 100, 112))
        }
    }

    private fun sampleSummaryText(summary: CommandVoiceSampleStore.Summary): String {
        return if (summary.count > 0) {
            "음성 샘플 ${summary.count}/${CommandVoiceSampleStore.MAX_SAMPLES_PER_COMMAND}개 저장됨"
        } else {
            "음성 샘플 미등록"
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return DateFormat.format("yyyy-MM-dd HH:mm", timestampMs).toString()
    }

    private fun cancelPendingSampleStart() {
        pendingSampleStart?.let(handler::removeCallbacks)
        pendingSampleStart = null
    }

    private fun shouldDisplayProgressStatus(status: String): Boolean {
        val percent = PROGRESS_PERCENT.find(status)?.groupValues?.get(1)?.toIntOrNull() ?: return true
        return percent >= 100 || percent % 20 == 0
    }

    private fun matchWrap(
        topMargin: Int = 0,
        bottomMargin: Int = dp(10),
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, topMargin, 0, bottomMargin) }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val REQUEST_RECORD_AUDIO = 2101
        private const val SAMPLE_RECORDING_DURATION_MS = 3000L
        private const val SAMPLE_RECORDING_START_DELAY_MS = 400L
        private val PROGRESS_PERCENT = Regex("(\\d+)%")
    }
}
