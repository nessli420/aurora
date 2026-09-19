package com.aurora.music.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.ProfileAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalProfileEditState(val profile: LocalProfile = LocalProfile(), val preview: ProfileAppearance = ProfileAppearance(),
    val busy: Boolean = true, val error: String? = null)

class LocalProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val mutable = MutableStateFlow(LocalProfileEditState())
    val state = mutable.asStateFlow()

    fun begin() = viewModelScope.launch {
        mutable.value = LocalProfileEditState()
        val profile = container.settingsStore.localProfile.first()
        val preview = withContext(Dispatchers.IO) { container.profileImages.appearance(profile) }
        mutable.value = LocalProfileEditState(profile, preview, false)
    }

    fun name(value: String) { mutable.update { it.copy(profile = it.profile.copy(name = value), error = null) } }

    fun removeImage(banner: Boolean) { mutable.update {
        if (banner) it.copy(profile = it.profile.copy(banner = ""), preview = it.preview.copy(bannerUrl = ""), error = null)
        else it.copy(profile = it.profile.copy(avatar = ""), preview = it.preview.copy(avatarUrl = ""), error = null)
    } }

    fun image(uri: Uri, banner: Boolean) = viewModelScope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        try {
            val encoded = container.profileImages.import(uri, banner)
            val url = withContext(Dispatchers.IO) { container.profileImages.imageUrl(encoded) }
            check(url.isNotEmpty()) { "Cannot read this image." }
            mutable.update {
                if (banner) it.copy(profile = it.profile.copy(banner = encoded), preview = it.preview.copy(bannerUrl = url))
                else it.copy(profile = it.profile.copy(avatar = encoded), preview = it.preview.copy(avatarUrl = url))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Cannot read this image.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        try {
            container.settingsStore.setLocalProfile(mutable.value.profile)
            onSaved()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Could not save profile.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
}
