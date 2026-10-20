package com.codex.pocketledger

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Each launcher alias has its own adaptive icon; exactly one stays enabled. */
object ThemeLauncherIcon {
    private val aliases = mapOf(
        "lavender" to "LauncherLavender",
        "yellow" to "LauncherYellow",
        "pink" to "LauncherPink",
        "blue" to "LauncherBlue",
        "cyan" to "LauncherCyan",
        "green" to "LauncherGreen",
    )

    fun apply(context: Context, themeId: String) {
        val selected = aliases[themeId] ?: aliases.getValue("lavender")
        val manager = context.packageManager
        val changes = aliases.values.map { alias ->
            val component = ComponentName(context.packageName, "${context.packageName}.$alias")
            val state = if (alias == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            component to state
        }
        if (changes.all { (component, state) -> manager.getComponentEnabledSetting(component) == state }) return
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            // Keep a launcher entry available throughout the non-atomic fallback.
            changes.sortedByDescending { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component, state) ->
                    manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
        }
    }
}
