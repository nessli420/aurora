package com.aurora.music.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.InputStream

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aurora_settings")

fun SettingsStore(context: Context) = SettingsStore(context.dataStore, context.filesDir, context.cacheDir)

fun androidFileUri(path: String): String = Uri.fromFile(File(path)).toString()

fun Context.openUri(uri: String): InputStream? = applicationContext.contentResolver.openInputStream(Uri.parse(uri))
