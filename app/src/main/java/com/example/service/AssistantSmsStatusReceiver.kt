package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository
import com.example.ui.screens.sms.EXTRA_LOCAL_ID
import com.example.ui.screens.sms.SMS_DELIVERED_ACTION
import com.example.ui.screens.sms.SMS_RECEIVED_ACTION
import com.example.ui.screens.sms.SMS_SENT_ACTION
import com.example.util.AssistantSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AssistantSmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SMS_SENT_ACTION && intent.action != SMS_DELIVERED_ACTION) return
        val localId = intent.getLongExtra(EXTRA_LOCAL_ID, -1L)
        if (localId <= 0L) return
        val status = AssistantSms.statusForResult(intent.action, resultCode)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppRepository(AppDatabase.getDatabase(context.applicationContext))
                    .updateLocalSmsStatus(localId, status)
                context.sendBroadcast(Intent(SMS_RECEIVED_ACTION).setPackage(context.packageName))
            } finally {
                pendingResult.finish()
            }
        }
    }
}
