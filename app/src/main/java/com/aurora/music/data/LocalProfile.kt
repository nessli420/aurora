package com.aurora.music.data

import com.google.gson.Gson
import java.util.Base64

data class LocalProfile(val name: String? = "", val avatar: String? = "", val banner: String? = "")

data class ProfileAppearance(val name: String = "Local Library", val avatarUrl: String = "", val bannerUrl: String = "")

fun profileAppearance(session: Session?, local: ProfileAppearance): ProfileAppearance =
    if (session?.type == ServerType.LOCAL) local else ProfileAppearance(session?.username.orEmpty(), session?.imageUrl.orEmpty())

object LocalProfileCodec {
    const val KEY = "local_profile_v1"
    const val MAX_IMAGE_BYTES = 512 * 1024
    private val gson = Gson()
    fun decode(json: String?): LocalProfile = if (json.isNullOrBlank()) LocalProfile() else
        validate(requireNotNull(gson.fromJson(json, LocalProfile::class.java)))
    fun encode(profile: LocalProfile): String = gson.toJson(validate(profile))
    fun validate(profile: LocalProfile): LocalProfile {
        val name = profile.name.orEmpty().trim()
        require(name.length <= 80 && name.none(Char::isISOControl)) { "Use a display name of up to 80 characters." }
        listOf(profile.avatar, profile.banner).forEach { encoded ->
            if (!encoded.isNullOrEmpty()) {
                require(encoded.length <= (MAX_IMAGE_BYTES + 2) / 3 * 4) { "Profile image is too large." }
                require(Base64.getDecoder().decode(encoded).size <= MAX_IMAGE_BYTES) { "Profile image is too large." }
            }
        }
        return LocalProfile(name, profile.avatar.orEmpty(), profile.banner.orEmpty())
    }
}
