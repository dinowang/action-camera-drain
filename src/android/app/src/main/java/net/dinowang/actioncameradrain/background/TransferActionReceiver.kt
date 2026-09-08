package net.dinowang.actioncameradrain.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val scheduler = UploadJobScheduler(context)
                when (intent.action) {
                    ACTION_PAUSE -> scheduler.pause(transferId)
                    ACTION_CANCEL -> scheduler.cancel(transferId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "net.dinowang.actioncameradrain.action.PAUSE_TRANSFER"
        const val ACTION_CANCEL = "net.dinowang.actioncameradrain.action.CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID = "transfer_id"
    }
}
