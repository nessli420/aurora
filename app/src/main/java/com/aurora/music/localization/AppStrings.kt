package com.aurora.music.localization

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** Resource access for shared UI helpers, callbacks and background components. */
object AppStrings {
    private lateinit var application: Context
    @Volatile private lateinit var localizedContext: Context

    fun initialize(context: Context) {
        application = context.applicationContext
        refresh()
    }

    fun refresh() {
        localizedContext = ContextCompat.getContextForLanguage(application)
    }

    fun useConfiguration(configuration: Configuration) {
        localizedContext = application.createConfigurationContext(Configuration(configuration))
    }

    val context: Context get() = localizedContext
}

fun appString(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) AppStrings.context.getString(id) else AppStrings.context.getString(id, *args)

fun appPlural(@PluralsRes id: Int, count: Int): String =
    AppStrings.context.resources.getQuantityString(id, count, count)
