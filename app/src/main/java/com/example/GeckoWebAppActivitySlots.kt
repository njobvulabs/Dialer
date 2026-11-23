package com.example

import android.content.Context
import android.content.Intent

/**
 * Custom Web App Creator slots. Each subclass is declared in its own OS process
 * (`:webapp1`..`:webapp4`) so it can host its own Gecko runtime + Gecko profile
 * directory — the same isolation pattern as the WhatsApp 1/2 Gecko activities.
 * The hosted app id (e.g. `customweb2`) travels in the intent extra and selects
 * both the URL/title (via the custom web app store) and the profile dir (via the
 * slot index).
 */
class GeckoWebAppActivity1 : GeckoWebAppActivity() {
    companion object {
        fun intent(context: Context, appId: String): Intent =
            Intent(context, GeckoWebAppActivity1::class.java).apply {
                putExtra(GeckoWebAppActivity.EXTRA_APP_ID, appId)
            }
    }
}

class GeckoWebAppActivity2 : GeckoWebAppActivity() {
    companion object {
        fun intent(context: Context, appId: String): Intent =
            Intent(context, GeckoWebAppActivity2::class.java).apply {
                putExtra(GeckoWebAppActivity.EXTRA_APP_ID, appId)
            }
    }
}

class GeckoWebAppActivity3 : GeckoWebAppActivity() {
    companion object {
        fun intent(context: Context, appId: String): Intent =
            Intent(context, GeckoWebAppActivity3::class.java).apply {
                putExtra(GeckoWebAppActivity.EXTRA_APP_ID, appId)
            }
    }
}

class GeckoWebAppActivity4 : GeckoWebAppActivity() {
    companion object {
        fun intent(context: Context, appId: String): Intent =
            Intent(context, GeckoWebAppActivity4::class.java).apply {
                putExtra(GeckoWebAppActivity.EXTRA_APP_ID, appId)
            }
    }
}
