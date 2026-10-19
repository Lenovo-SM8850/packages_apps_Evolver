/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ThemeEngine
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.ListPreference
import com.android.settings.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppIconPackPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle
) : ListPreference(context, attrs, defStyleAttr) {

    private data class Pack(val packageName: String, val label: String)

    private val engine = ThemeEngine.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null
    private val systemDefault = Pack("", context.getString(R.string.app_icon_pack_system_default))
    private val themeListener = ThemeEngine.ThemeChangeListener { category ->
        if (category == null || category == ThemeEngine.CATEGORY_ICON_PACK) {
            mainHandler.post { refreshPacks() }
        }
    }

    init {
        setSummaryProvider(SimpleSummaryProvider.getInstance())
        // The dialog requires entries, so offer the system default until discovery finishes.
        showPacks(listOf(systemDefault), null)
    }

    override fun onAttached() {
        super.onAttached()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Stay disabled until the pack list is loaded so the dialog never opens half empty.
        isEnabled = false
        engine?.addThemeChangeListener(themeListener)
        refreshPacks()
    }

    override fun onDetached() {
        engine?.removeThemeChangeListener(themeListener)
        scope?.cancel()
        scope = null
        super.onDetached()
    }

    // The new value is committed only after the theme engine has accepted it.
    override fun callChangeListener(newValue: Any?): Boolean {
        val packageName = newValue as? String ?: return false
        refreshJob?.cancel()
        scope?.launch {
            val applied = withContext(Dispatchers.IO) {
                engine?.setIconPack(packageName.ifEmpty { null }) == true
            }
            if (applied) {
                value = packageName
            } else {
                Toast.makeText(context, R.string.app_icon_pack_apply_failed,
                        Toast.LENGTH_SHORT).show()
                refreshPacks()
            }
        }
        return false
    }

    private fun refreshPacks() {
        refreshJob?.cancel()
        refreshJob = scope?.launch {
            // Discovery parses appfilters in system_server; never wait for it on the UI thread.
            val (installed, selected) = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val installed = engine?.installedIconPacks.orEmpty().mapNotNull { packageName ->
                    try {
                        val app = pm.getApplicationInfo(packageName, 0)
                        Pack(packageName, app.loadLabel(pm).toString())
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }.sortedWith(compareBy<Pack> { it.label.lowercase() }.thenBy { it.packageName })
                installed to engine?.iconPackPackage
            }
            showPacks(listOf(systemDefault) + installed, selected)
            isEnabled = engine != null
        }
    }

    private fun showPacks(packs: List<Pack>, selected: String?) {
        entries = packs.map { it.label }.toTypedArray()
        entryValues = packs.map { it.packageName }.toTypedArray()
        // A selected pack that is no longer installed falls back to the system default.
        value = packs.firstOrNull { it.packageName == selected }?.packageName
                ?: systemDefault.packageName
        // The value may be unchanged while the entries are not, so refresh the summary.
        notifyChanged()
    }
}
