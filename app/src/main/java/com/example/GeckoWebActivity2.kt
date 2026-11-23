package com.example

import android.content.Context
import android.content.Intent

/**
 * Second WhatsApp account on the GeckoView engine, in its own OS process and
 * its own Gecko profile directory, so the second WhatsApp session (QR link,
 * cookies, chat history) stays completely independent from WhatsApp 1.
 */
class GeckoWebActivity2 : GeckoWebActivity() {

    companion object {
        private const val PROFILE_DIR = "whatsapp2-profile"

        fun intent(ctx: Context): Intent {
            val dir = ctx.filesDir.absolutePath + "/" + PROFILE_DIR
            return GeckoWebActivity.intent(
                ctx,
                "https://web.whatsapp.com/",

                "WhatsApp 2",
                profileDir = dir,
                activityClass = GeckoWebActivity2::class.java
            )
        }
    }
}
