package io.embodit.regionsnap.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.regionsnap.platform.RsLog

/**
 * Debug builds only. Forwards adb broadcasts to the running app (action `io.embodit.regionsnap.DEBUG`); see
 * [DebugCommands] and AGENTS.md for the command list.
 *
 *   adb -s emulator-5558 shell "am broadcast -a io.embodit.regionsnap.DEBUG -p io.embodit.regionsnap --es cmd 'goto S06'"
 *   adb -s emulator-5558 shell "am broadcast -a io.embodit.regionsnap.DEBUG -p io.embodit.regionsnap --es cmd goto --es s S06"
 *
 * Extras: `cmd` = command name, its arguments may follow inside `cmd`; or the design's extras s / id / d / x y z (float)
 * / v (float) / n (int).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"))
        val args = ArrayList(parts.drop(1))
        intent.getStringExtra("s")?.let { args += it }
        intent.getStringExtra("id")?.let { args += it }
        intent.getStringExtra("d")?.let { args += it }
        for (k in listOf("x", "y", "z", "v")) if (intent.hasExtra(k)) args += intent.getFloatExtra(k, 0f).toString()
        if (intent.hasExtra("n")) args += intent.getIntExtra("n", 0).toString()
        RsLog.i("CMD recv ${parts[0]} args=$args")
        // broadcasts arrive on the main thread, where the controller and the scene live; a malformed command logs
        runCatching { DebugCommands.run(parts[0], args) }.onFailure { RsLog.w("CMD ${parts[0]} failed: $it") }
    }
}
