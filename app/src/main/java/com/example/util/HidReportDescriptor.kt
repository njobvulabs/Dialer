package com.example.util

/**
 * Report descriptor for a combined five-button mouse (report 1) and a
 * six-key rollover keyboard (report 2). Version 2 optionally adds a consumer
 * control collection (report 3) and LED output for the keyboard.
 *
 * Version 1 is frozen: changing report ids or bit layouts forces every host to
 * remove and re-pair the phone. Media keys are therefore opt-in.
 */
object HidReportDescriptor {
    const val VERSION = 2
    const val MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2
    const val CONSUMER_REPORT_ID = 3

    const val MOUSE_PAYLOAD_SIZE = 5
    const val KEYBOARD_PAYLOAD_SIZE = 8
    const val CONSUMER_PAYLOAD_SIZE = 2

    fun build(mediaKeys: Boolean): ByteArray {
        val descriptor = ArrayList<Int>(mouseKeyboardBase())
        if (mediaKeys) {
            val keyboardEnd = descriptor.lastIndexOf(0xC0)
            if (keyboardEnd >= 0) descriptor.addAll(keyboardEnd, ledOutput())
            descriptor.addAll(consumerControl())
        }
        return descriptor.map { it.toByte() }.toByteArray()
    }

    val MOUSE_KEYBOARD_COMBO: ByteArray = build(false)
    val MEDIA_KEYS_COMBO: ByteArray = build(true)

    private fun mouseKeyboardBase(): List<Int> = intArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, MOUSE_REPORT_ID, // Report ID (1)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (1)
        0x29, 0x05,             //     Usage Maximum (5)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95, 0x05,             //     Report Count (5)
        0x75, 0x01,             //     Report Size (1)
        0x81, 0x02,             //     Input (Data, Variable, Absolute)
        0x95, 0x01,             //     Report Count (1)
        0x75, 0x03,             //     Report Size (3)
        0x81, 0x01,             //     Input (Constant) padding
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x03,             //     Report Count (3)
        0x81, 0x06,             //     Input (Data, Variable, Relative)
        0x09, 0x48,             //     Usage (Horizontal Wheel / pan)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x01,             //     Report Count (1)
        0x81, 0x06,             //     Input (Data, Variable, Relative)
        0xC0,                   //   End Collection
        0xC0,                   // End Collection
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,             // Collection (Application)
        0x85, KEYBOARD_REPORT_ID, // Report ID (2)
        0x05, 0x07,             //   Usage Page (Keyboard)
        0x19, 0xE0,             //   Usage Minimum (Left Control)
        0x29, 0xE7,             //   Usage Maximum (Right GUI)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x08,             //   Report Count (8)
        0x81, 0x02,             //   Input (Data, Variable, Absolute) modifiers
        0x95, 0x01,             //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x81, 0x01,             //   Input (Constant) reserved byte
        0x95, 0x06,             //   Report Count (6)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Keyboard)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81, 0x00,             //   Input (Data, Array) keycodes
        0xC0,                   // End Collection
    ).toList()

    /** LED output inside the existing keyboard collection (report 2). */
    private fun ledOutput(): List<Int> = intArrayOf(
        0x05, 0x08,             //   Usage Page (LEDs)
        0x19, 0x01,             //   Usage Minimum (Num Lock)
        0x29, 0x05,             //   Usage Maximum (Kana)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x05,             //   Report Count (5)
        0x91, 0x02,             //   Output (Data, Variable, Absolute)
        0x95, 0x01,             //   Report Count (1)
        0x75, 0x03,             //   Report Size (3)
        0x91, 0x01,             //   Output (Constant) padding
    ).toList()

    /**
     * Consumer-control collection as report 3: a single 16-bit usage array
     * carrying play/pause, track skip, volume, mute and browser keys.
     */
    private fun consumerControl(): List<Int> = intArrayOf(
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1, 0x01,             // Collection (Application)
        0x85, CONSUMER_REPORT_ID, // Report ID (3)
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF, 0x03,       //   Logical Maximum (1023)
        0x19, 0x00,             //   Usage Minimum (0)
        0x2A, 0xFF, 0x03,       //   Usage Maximum (1023)
        0x75, 0x10,             //   Report Size (16)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x00,             //   Input (Data, Array, Absolute)
        0xC0,                   // End Collection
    ).toList()
}
