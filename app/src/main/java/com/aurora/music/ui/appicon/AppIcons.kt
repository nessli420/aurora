package com.aurora.music.ui.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.aurora.music.AuroraActivity
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle

enum class AppIcon(val alias: String, @DrawableRes val icon: Int, val themeStyle: Int, val enabledByDefault: Boolean) {
    AURORA("MainActivity", R.mipmap.ic_launcher, ThemeStyle.AURORA, true),
    RETRO("IconRetro", R.mipmap.ic_launcher_retro, ThemeStyle.RETRO, false),
    AERO("IconAero", R.mipmap.ic_launcher_aero, ThemeStyle.AERO, false),
    GLASS("IconGlass", R.mipmap.ic_launcher_glass, ThemeStyle.GLASS, false),
}

object AppIcons {
    private const val PREFS = "app_icon"
    private const val PENDING = "pending"

    private fun component(context: Context, icon: AppIcon) =
        ComponentName(context.packageName, "${AuroraActivity::class.java.packageName}.${icon.alias}")

    private fun isEnabled(context: Context, icon: AppIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.enabledByDefault
            else -> false
        }

    fun current(context: Context): AppIcon = AppIcon.entries.firstOrNull { isEnabled(context, it) } ?: AppIcon.AURORA

    private fun pending(context: Context): AppIcon? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, null)
            ?.let { name -> AppIcon.entries.firstOrNull { it.name == name } }

    fun selected(context: Context): AppIcon = pending(context) ?: current(context)

    fun select(context: Context, icon: AppIcon) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (icon == current(context)) prefs.edit().remove(PENDING).apply()
        else prefs.edit().putString(PENDING, icon.name).apply()
    }

    fun applyPending(context: Context) {
        val target = pending(context) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).apply()
        if (target == current(context)) return
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != target }.forEach {
            pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
