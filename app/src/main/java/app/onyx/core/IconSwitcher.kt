package app.onyx.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The ten launcher icons are declared as activity-alias entries in the manifest.
 * Exactly one alias is enabled at a time. Android shows the enabled alias as the
 * single ONYX launcher entry.
 */
object IconSwitcher {

    const val COUNT = 10

    fun component(context: Context, index: Int): ComponentName =
        ComponentName(context, "app.onyx.Icon%02d".format(index.coerceIn(1, COUNT)))

    /** Which alias is currently the launcher entry (used by the splash). */
    fun current(context: Context): Int {
        val pm = context.packageManager
        for (i in 1..COUNT) {
            val state = runCatching { pm.getComponentEnabledSetting(component(context, i)) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
        }
        return 1
    }

    fun apply(context: Context, index: Int, killApp: Boolean = false) {
        val target = index.coerceIn(1, COUNT)
        val pm = context.packageManager
        val flags = if (killApp) 0 else PackageManager.DONT_KILL_APP

        // Enable the chosen icon first so there is never a moment without a launcher entry.
        val targetComponent = component(context, target)
        if (pm.getComponentEnabledSetting(targetComponent) !=
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ) {
            runCatching {
                pm.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    flags,
                )
            }
        }
        for (i in 1..COUNT) {
            if (i == target) continue
            val c = component(context, i)
            if (pm.getComponentEnabledSetting(c) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                continue
            }
            runCatching {
                pm.setComponentEnabledSetting(
                    c,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    flags,
                )
            }
        }
    }
}
