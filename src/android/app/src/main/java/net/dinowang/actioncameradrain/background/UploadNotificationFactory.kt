package net.dinowang.actioncameradrain.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import net.dinowang.actioncameradrain.MainActivity
import net.dinowang.actioncameradrain.R
import net.dinowang.actioncameradrain.domain.upload.TransferRecord
import net.dinowang.actioncameradrain.domain.upload.TransferState

class UploadNotificationFactory(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Card uploads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun build(record: TransferRecord): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            record.jobId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = actionIntent(record, TransferActionReceiver.ACTION_PAUSE, 1)
        val cancelIntent = actionIntent(record, TransferActionReceiver.ACTION_CANCEL, 2)
        val progressMax = 10_000
        val progress = if (record.totalBytes > 0) {
            ((record.doneBytes.toDouble() / record.totalBytes) * progressMax)
                .toInt()
                .coerceIn(0, progressMax)
        } else {
            0
        }
        val running = record.state in setOf(
            TransferState.QUEUED,
            TransferState.PLANNING,
            TransferState.RUNNING,
            TransferState.CANCELLING,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Uploading ${record.rootLabel}")
            .setContentText(notificationText(record))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setProgress(progressMax, progress, record.totalBytes <= 0)
            .apply {
                if (record.state in setOf(
                        TransferState.QUEUED,
                        TransferState.PLANNING,
                        TransferState.RUNNING,
                    )
                ) {
                    addAction(Notification.Action.Builder(null, "Pause", pauseIntent).build())
                    addAction(Notification.Action.Builder(null, "Cancel", cancelIntent).build())
                }
            }
            .build()
    }

    private fun actionIntent(record: TransferRecord, action: String, offset: Int): PendingIntent {
        val intent = Intent(context, TransferActionReceiver::class.java)
            .setAction(action)
            .putExtra(TransferActionReceiver.EXTRA_TRANSFER_ID, record.id)
        return PendingIntent.getBroadcast(
            context,
            record.jobId + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationText(record: TransferRecord): String = when (record.state) {
        TransferState.QUEUED -> "Waiting for network"
        TransferState.PLANNING -> "Scanning memory card"
        TransferState.RUNNING ->
            "${record.doneFiles}/${record.totalFiles} files · ${formatPercent(record)}"
        TransferState.PAUSED -> "Paused"
        TransferState.CANCELLING -> "Cancelling"
        TransferState.CANCELLED -> "Cancelled"
        TransferState.FAILED -> record.message ?: "Upload failed"
        TransferState.COMPLETED -> "${record.doneFiles} files uploaded and verified"
    }

    private fun formatPercent(record: TransferRecord): String {
        if (record.totalBytes <= 0) return "Starting"
        return "%.1f%%".format(record.doneBytes.toDouble() * 100 / record.totalBytes)
    }

    companion object {
        const val CHANNEL_ID = "card_uploads"
    }
}
