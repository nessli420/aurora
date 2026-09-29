package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.ProfileAppearance
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.ui.screens.profile.ProfileImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalProfileEditState(val profile: LocalProfile = LocalProfile(), val preview: ProfileAppearance = ProfileAppearance(),
    val busy: Boolean = true, val error: String? = null)

class LocalProfileViewModel(private val container: DesktopContainer) : ViewModel() {
    private val profileImages = ProfileImages(File(container.paths.cache, "local-profile"))
    private val mutable = MutableStateFlow(LocalProfileEditState())
    val state = mutable.asStateFlow()

    fun begin() = viewModelScope.launch {
        mutable.value = LocalProfileEditState()
        val profile = container.settingsStore.localProfile.first()
        val preview = withContext(Dispatchers.IO) { profileImages.appearance(profile) }
        mutable.value = LocalProfileEditState(profile, preview, false)
    }

    fun name(value: String) { mutable.update { it.copy(profile = it.profile.copy(name = value), error = null) } }

    fun removeImage(banner: Boolean) { mutable.update {
        if (banner) it.copy(profile = it.profile.copy(banner = ""), preview = it.preview.copy(bannerUrl = ""), error = null)
        else it.copy(profile = it.profile.copy(avatar = ""), preview = it.preview.copy(avatarUrl = ""), error = null)
    } }

    fun image(file: File, banner: Boolean) = viewModelScope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        try {
            val encoded = profileImages.import(file, banner)
            val url = withContext(Dispatchers.IO) { profileImages.imageUrl(encoded) }
            check(url.isNotEmpty()) { appString(R.string.text_cannot_read_this_image_9a45ce) }
            mutable.update {
                if (banner) it.copy(profile = it.profile.copy(banner = encoded), preview = it.preview.copy(bannerUrl = url))
                else it.copy(profile = it.profile.copy(avatar = encoded), preview = it.preview.copy(avatarUrl = url))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: appString(R.string.text_cannot_read_this_image_9a45ce)) } }
        finally { mutable.update { it.copy(busy = false) } }
    }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        try {
            container.settingsStore.setLocalProfile(mutable.value.profile)
            onSaved()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: appString(R.string.text_could_not_save_profile_ac7801)) } }
        finally { mutable.update { it.copy(busy = false) } }
    }
}
