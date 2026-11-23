package com.example.util

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

sealed class HidMacroStep {
    data class Chord(val modifiers: Int, val usage: Int) : HidMacroStep()
    data class Text(val text: String) : HidMacroStep()
    data class Delay(val ms: Long) : HidMacroStep()
}

data class HidMacro(
    val id: String,
    val label: String,
    val steps: List<HidMacroStep>,
    val builtIn: Boolean = false,
)

object HidMacros {
    fun defaults(): List<HidMacro> = listOf(
        chord("copy", "Copy", HidKeymap.MOD_CTRL, 0x06),
        chord("paste", "Paste", HidKeymap.MOD_CTRL, 0x19),
        chord("cut", "Cut", HidKeymap.MOD_CTRL, 0x1B),
        chord("undo", "Undo", HidKeymap.MOD_CTRL, 0x1D),
        chord("redo", "Redo", HidKeymap.MOD_CTRL, 0x1C),
        chord("select_all", "Select all", HidKeymap.MOD_CTRL, 0x04),
        chord("save", "Save", HidKeymap.MOD_CTRL, 0x16),
        chord("find", "Find", HidKeymap.MOD_CTRL, 0x09),
        chord("task_manager", "Task manager", HidKeymap.MOD_CTRL or HidKeymap.MOD_SHIFT, HidKeymap.KEY_ESC),
        chord("security", "Ctrl+Alt+Del", HidKeymap.MOD_CTRL or HidKeymap.MOD_ALT, HidKeymap.KEY_DELETE),
        chord("alt_tab", "Alt+Tab", HidKeymap.MOD_ALT, HidKeymap.KEY_TAB),
        chord("close", "Alt+F4", HidKeymap.MOD_ALT, HidKeymap.KEY_F4),
        chord("back", "Alt+Left", HidKeymap.MOD_ALT, HidKeymap.KEY_LEFT),
        chord("forward", "Alt+Right", HidKeymap.MOD_ALT, HidKeymap.KEY_RIGHT),
        chord("desktop", "Show desktop", HidKeymap.MOD_GUI, 0x07),
        chord("explorer", "Explorer", HidKeymap.MOD_GUI, 0x08),
        chord("lock", "Lock PC", HidKeymap.MOD_GUI, 0x0F),
        chord("refresh", "Refresh", 0, HidKeymap.KEY_F5),
        chord("fullscreen", "Fullscreen", 0, HidKeymap.KEY_F11),
    )

    fun load(prefs: SharedPreferences): List<HidMacro> {
        val raw = prefs.getString(HidPrefs.MACROS, null) ?: return defaults()
        val parsed = runCatching { parse(raw) }.getOrDefault(emptyList())
        return if (parsed.isEmpty()) defaults() else parsed
    }

    fun save(prefs: SharedPreferences, macros: List<HidMacro>) {
        val array = JSONArray()
        macros.forEach { macro ->
            val obj = JSONObject()
                .put("id", macro.id)
                .put("label", macro.label)
                .put("builtIn", macro.builtIn)
            val steps = JSONArray()
            macro.steps.forEach { step ->
                when (step) {
                    is HidMacroStep.Chord -> steps.put(
                        JSONObject().put("type", "chord")
                            .put("modifiers", step.modifiers)
                            .put("usage", step.usage)
                    )
                    is HidMacroStep.Text -> steps.put(
                        JSONObject().put("type", "text").put("text", step.text)
                    )
                    is HidMacroStep.Delay -> steps.put(
                        JSONObject().put("type", "delay").put("ms", step.ms)
                    )
                }
            }
            obj.put("steps", steps)
            array.put(obj)
        }
        prefs.edit().putString(HidPrefs.MACROS, array.toString()).apply()
    }

    fun parse(raw: String): List<HidMacro> {
        val array = JSONArray(raw)
        val result = ArrayList<HidMacro>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val stepsJson = obj.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<HidMacroStep>(stepsJson.length())
            for (j in 0 until stepsJson.length()) {
                val step = stepsJson.optJSONObject(j) ?: continue
                when (step.optString("type")) {
                    "chord" -> steps.add(
                        HidMacroStep.Chord(step.optInt("modifiers"), step.optInt("usage"))
                    )
                    "text" -> steps.add(HidMacroStep.Text(step.optString("text")))
                    "delay" -> steps.add(HidMacroStep.Delay(step.optLong("ms", 30L)))
                }
            }
            val id = obj.optString("id").ifBlank { "macro_$i" }
            val label = obj.optString("label").ifBlank { id }
            if (steps.isNotEmpty()) {
                result.add(HidMacro(id, label, steps, obj.optBoolean("builtIn")))
            }
        }
        return result
    }

    fun newId(): String = "macro_${System.currentTimeMillis()}"

    private fun chord(id: String, label: String, modifiers: Int, usage: Int): HidMacro =
        HidMacro(id, label, listOf(HidMacroStep.Chord(modifiers, usage)), builtIn = true)
}
