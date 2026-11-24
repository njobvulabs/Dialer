package com.example.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsConversationDeletesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBlocks() {
        prefs().edit().clear().apply()
    }

    @Test
    fun keyForHandlesNumericAndAlphanumericAddresses() {
        assertEquals("991234567", SmsConversationDeletes.keyFor("+265 991234567"))
        assertEquals("991234567", SmsConversationDeletes.keyFor("0991234567"))
        assertEquals("alpha:mtn", SmsConversationDeletes.keyFor("MTN"))
        assertEquals("alpha:mtn24", SmsConversationDeletes.keyFor(" MTN-24 "))
        assertEquals("", SmsConversationDeletes.keyFor("   "))
    }

    @Test
    fun markAndClearAlphanumericConversation() {
        SmsConversationDeletes.mark(context, "MTN")
        val keys = SmsConversationDeletes.read(context).keys
        assertTrue(keys.contains("alpha:mtn"))

        SmsConversationDeletes.clear(context, listOf("MTN"))
        assertFalse(SmsConversationDeletes.read(context).keys.contains("alpha:mtn"))
    }

    @Test
    fun readKeepsStoredKeysVerbatim() {
        prefs().edit().putStringSet(
            "deleted_conversations",
            setOf("1000|991234567", "1000|alpha:airtel")
        ).apply()

        val keys = SmsConversationDeletes.read(context).keys
        assertTrue(keys.contains("991234567"))
        assertTrue(keys.contains("alpha:airtel"))
    }

    private fun prefs() = context.getSharedPreferences("sms", Context.MODE_PRIVATE)
}
