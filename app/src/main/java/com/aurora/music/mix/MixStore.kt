package com.aurora.music.mix

import android.content.Context
import android.util.AtomicFile
import com.aurora.music.data.SavedTrack
import com.aurora.music.data.toSavedTrack
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit, versioned JSON defaults avoid Gson's missing-field/null constructor bypass. */
class MixStore(context: Context) {
    private val directory = File(context.filesDir, "mixes").apply { mkdirs() }
    private val gson = Gson()
    private fun file(account: String) = AtomicFile(File(directory, account.toByteArray().let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
    } + ".json"))

    suspend fun list(account: String): List<MixProject> = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.baseFile.exists()) return@withContext emptyList()
        val array = JSONObject(String(f.readFully(), Charsets.UTF_8)).optJSONArray("projects") ?: JSONArray()
        (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
    }

    suspend fun save(account: String, project: MixProject) = withContext(Dispatchers.IO) {
        val projects = list(account).filter { it.id != project.id } + project.normalized()
        write(account, projects)
    }
    suspend fun delete(account: String, id: String) = withContext(Dispatchers.IO) { write(account, list(account).filter { it.id != id }) }
    private fun write(account: String, projects: List<MixProject>) {
        val json = JSONObject().put("version", 1).put("projects", JSONArray(projects.map { encode(it) }))
        val f = file(account)
        val out = f.startWrite()
        try { out.write(json.toString().toByteArray()); f.finishWrite(out) }
        catch (t: Throwable) { f.failWrite(out); throw t }
    }
    private fun encode(p: MixProject) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("masterDb", p.masterDb); put("protect", p.protectPeaks); put("loop", p.loop)
        put("clips", JSONArray(p.clips.map { c -> JSONObject().apply {
            put("id", c.id)
            // Refresh server URLs by song id on load; never persist expiring stream credentials here.
            val saved = c.song.toSavedTrack().let { if (it.streamUrl.orEmpty().startsWith("http")) it.copy(streamUrl = "", artworkUrl = "") else it }
            put("song", JSONObject(gson.toJson(saved)))
            put("start", c.startSec); put("in", c.cueInSec); put("out", c.cueOutSec)
            put("fadeIn", c.fadeInSec); put("fadeOut", c.fadeOutSec); put("curve", c.curve.name)
            put("inBend", c.fadeInBend); put("outBend", c.fadeOutBend)
            put("note", c.transitionNote); put("bassSwap", c.bassSwap); put("stem", c.stem.name)
            // Stems are resolved from the private cache on reopen, never trusted from saved paths.
            put("gain", c.gainDb); put("pan", c.pan); put("bass", c.bassDb); put("mid", c.midDb); put("treble", c.trebleDb)
            put("speed", c.speed); put("pitch", c.pitchSemitones); put("mute", c.muted); put("solo", c.solo)
        } }))
    }
    private fun decode(p: JSONObject): MixProject {
        val array = p.optJSONArray("clips") ?: JSONArray()
        val clips = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val song = gson.fromJson(c.getJSONObject("song").toString(), SavedTrack::class.java).toSong()
            fun f(key: String, default: Float = 0f) = c.optDouble(key, default.toDouble()).toFloat()
            MixClip(id = c.getString("id"), song = song, startSec = f("start"), cueInSec = f("in"), cueOutSec = f("out", song.durationSec.toFloat()),
                fadeInSec = f("fadeIn"), fadeOutSec = f("fadeOut"), curve = runCatching { FadeCurve.valueOf(c.optString("curve")) }.getOrDefault(FadeCurve.SMOOTH),
                gainDb = f("gain"), pan = f("pan"), bassDb = f("bass"), midDb = f("mid"), trebleDb = f("treble"),
                speed = f("speed", 1f), pitchSemitones = f("pitch"), muted = c.optBoolean("mute"), solo = c.optBoolean("solo"),
                fadeInBend = f("inBend", 1f), fadeOutBend = f("outBend", 1f), transitionNote = c.optString("note"),
                bassSwap = c.optBoolean("bassSwap"), stem = runCatching { StemMode.valueOf(c.optString("stem")) }.getOrDefault(StemMode.FULL))
        }
        return MixProject(p.getString("id"), p.optString("name", "Untitled mix"), clips,
            p.optDouble("masterDb", -1.0).toFloat(), p.optBoolean("protect", true), p.optBoolean("loop")).normalized()
    }
}
