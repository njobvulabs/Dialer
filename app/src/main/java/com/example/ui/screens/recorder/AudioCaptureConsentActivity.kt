package com.example.ui.screens.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.example.util.InternalAudioCapture
import com.example.util.InternalAudioRecordController

class AudioCaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!InternalAudioCapture.supported()) {
            InternalAudioRecordController.fail("System audio capture needs Android 10 or newer")
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            InternalAudioRecordController.fail("Screen capture unavailable")
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode == RESULT_OK) {
            InternalAudioRecordController.start(applicationContext, resultCode, data)
        } else {
            InternalAudioRecordController.canceled()
        }
        finish()
    }

    companion object {
        private const val REQUEST_PROJECTION = 4411
    }
}
