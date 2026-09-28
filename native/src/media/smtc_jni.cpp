#include "jni_support.h"
#include <windows.h>
#include <SystemMediaTransportControlsInterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>
#include <chrono>
#include <unordered_map>

using aurora::Worker;
using namespace winrt::Windows::Media;
using namespace winrt::Windows::Storage::Streams;
using winrt::Windows::Foundation::TimeSpan;

namespace {

struct Session {
    SystemMediaTransportControls controls{nullptr};
    jobject listener = nullptr;
    jmethodID onButton = nullptr;
    jmethodID onSeek = nullptr;
    jmethodID onShuffle = nullptr;
    jmethodID onRepeat = nullptr;
    winrt::event_token button{};
    winrt::event_token seek{};
    winrt::event_token shuffle{};
    winrt::event_token repeat{};
};

struct Sessions {
    std::unordered_map<jlong, Session> items;
    jlong next = 1;
};

Sessions& sessions() {
    static auto* value = new Sessions();
    return *value;
}

template <class Invoke>
void dispatch(jlong handle, Invoke invoke) {
    Worker::post([handle, invoke](JNIEnv* env) {
        const auto it = sessions().items.find(handle);
        if (it != sessions().items.end()) invoke(env, it->second);
    });
}

template <class Update>
jint apply(jlong handle, Update&& update) {
    return Worker::call([&](JNIEnv*) -> jint {
        const auto it = sessions().items.find(handle);
        if (it == sessions().items.end()) return E_HANDLE;
        try {
            update(it->second.controls);
            return S_OK;
        } catch (const winrt::hresult_error& error) {
            return error.code();
        }
    });
}

TimeSpan millis(jlong value) { return std::chrono::milliseconds(value); }

void listen(jlong handle, Session& session) {
    auto& controls = session.controls;
    session.button = controls.ButtonPressed([handle](const SystemMediaTransportControls&,
        const SystemMediaTransportControlsButtonPressedEventArgs& args) {
        const auto button = static_cast<jint>(args.Button());
        dispatch(handle, [button](JNIEnv* env, const Session& target) { env->CallVoidMethod(target.listener, target.onButton, button); });
    });
    session.seek = controls.PlaybackPositionChangeRequested([handle](const SystemMediaTransportControls&,
        const PlaybackPositionChangeRequestedEventArgs& args) {
        const jlong position = std::chrono::duration_cast<std::chrono::milliseconds>(args.RequestedPlaybackPosition()).count();
        dispatch(handle, [position](JNIEnv* env, const Session& target) { env->CallVoidMethod(target.listener, target.onSeek, position); });
    });
    session.shuffle = controls.ShuffleEnabledChangeRequested([handle](const SystemMediaTransportControls&,
        const ShuffleEnabledChangeRequestedEventArgs& args) {
        const jboolean enabled = args.RequestedShuffleEnabled() ? JNI_TRUE : JNI_FALSE;
        dispatch(handle, [enabled](JNIEnv* env, const Session& target) { env->CallVoidMethod(target.listener, target.onShuffle, enabled); });
    });
    session.repeat = controls.AutoRepeatModeChangeRequested([handle](const SystemMediaTransportControls&,
        const AutoRepeatModeChangeRequestedEventArgs& args) {
        const auto mode = static_cast<jint>(args.RequestedAutoRepeatMode());
        dispatch(handle, [mode](JNIEnv* env, const Session& target) { env->CallVoidMethod(target.listener, target.onRepeat, mode); });
    });
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_create(JNIEnv* env, jobject, jlong window, jobject listener) {
    const HWND root = window ? GetAncestor(reinterpret_cast<HWND>(window), GA_ROOT) : nullptr;
    if (!root || !listener) return E_INVALIDARG;
    Session session;
    const jclass type = env->GetObjectClass(listener);
    session.onButton = env->GetMethodID(type, "onButton", "(I)V");
    session.onSeek = env->GetMethodID(type, "onSeek", "(J)V");
    session.onShuffle = env->GetMethodID(type, "onShuffle", "(Z)V");
    session.onRepeat = env->GetMethodID(type, "onRepeat", "(I)V");
    env->DeleteLocalRef(type);
    if (!session.onButton || !session.onSeek || !session.onShuffle || !session.onRepeat) {
        env->ExceptionClear();
        return E_INVALIDARG;
    }
    session.listener = env->NewGlobalRef(listener);
    const jobject reference = session.listener;
    const jlong result = Worker::call([&](JNIEnv*) -> jlong {
        try {
            const auto interop = winrt::get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
            winrt::check_hresult(interop->GetForWindow(root, winrt::guid_of<SystemMediaTransportControls>(), winrt::put_abi(session.controls)));
            session.controls.IsEnabled(true);
            session.controls.IsPlayEnabled(true);
            session.controls.IsPauseEnabled(true);
            session.controls.DisplayUpdater().Type(MediaPlaybackType::Music);
            session.controls.DisplayUpdater().Update();
            const jlong handle = sessions().next++;
            listen(handle, session);
            sessions().items.emplace(handle, std::move(session));
            return handle;
        } catch (const winrt::hresult_error& error) {
            return error.code();
        }
    });
    if (result <= 0) env->DeleteGlobalRef(reference);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_metadata(JNIEnv* env, jobject, jlong handle, jstring title,
    jstring artist, jstring album, jstring albumArtist, jbyteArray thumbnail) {
    const std::wstring titleText = aurora::wide(env, title), artistText = aurora::wide(env, artist);
    const std::wstring albumText = aurora::wide(env, album), albumArtistText = aurora::wide(env, albumArtist);
    const std::vector<unsigned char> image = aurora::bytes(env, thumbnail);
    return apply(handle, [&](const SystemMediaTransportControls& controls) {
        auto updater = controls.DisplayUpdater();
        updater.Type(MediaPlaybackType::Music);
        auto music = updater.MusicProperties();
        music.Title(titleText);
        music.Artist(artistText);
        music.AlbumTitle(albumText);
        music.AlbumArtist(albumArtistText);
        if (image.empty()) {
            updater.Thumbnail(nullptr);
        } else {
            InMemoryRandomAccessStream stream;
            DataWriter writer(stream);
            writer.WriteBytes(image);
            writer.StoreAsync().get();
            writer.DetachStream();
            stream.Seek(0);
            updater.Thumbnail(RandomAccessStreamReference::CreateFromStream(stream));
        }
        updater.Update();
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_playbackStatus(JNIEnv*, jobject, jlong handle, jint status) {
    if (status < 0 || status > 4) return E_INVALIDARG;
    return apply(handle, [&](const SystemMediaTransportControls& controls) {
        controls.PlaybackStatus(static_cast<MediaPlaybackStatus>(status));
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_timeline(JNIEnv*, jobject, jlong handle, jlong positionMs,
    jlong durationMs, jlong minSeekMs, jlong maxSeekMs) {
    return apply(handle, [&](const SystemMediaTransportControls& controls) {
        SystemMediaTransportControlsTimelineProperties timeline;
        timeline.StartTime(TimeSpan{});
        timeline.EndTime(millis(durationMs));
        timeline.Position(millis(positionMs));
        timeline.MinSeekTime(millis(minSeekMs));
        timeline.MaxSeekTime(millis(maxSeekMs));
        controls.UpdateTimelineProperties(timeline);
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_buttons(JNIEnv*, jobject, jlong handle, jint mask) {
    return apply(handle, [&](const SystemMediaTransportControls& controls) {
        controls.IsPlayEnabled((mask & 1) != 0);
        controls.IsPauseEnabled((mask & 2) != 0);
        controls.IsNextEnabled((mask & 4) != 0);
        controls.IsPreviousEnabled((mask & 8) != 0);
        controls.IsStopEnabled((mask & 16) != 0);
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_shuffle(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    return apply(handle, [&](const SystemMediaTransportControls& controls) { controls.ShuffleEnabled(enabled == JNI_TRUE); });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_repeat(JNIEnv*, jobject, jlong handle, jint mode) {
    if (mode < 0 || mode > 2) return E_INVALIDARG;
    return apply(handle, [&](const SystemMediaTransportControls& controls) {
        controls.AutoRepeatMode(static_cast<MediaPlaybackAutoRepeatMode>(mode));
    });
}

JNIEXPORT void JNICALL
Java_com_aurora_music_desktop_natives_SmtcNative_destroy(JNIEnv*, jobject, jlong handle) {
    Worker::call([&](JNIEnv* env) {
        const auto it = sessions().items.find(handle);
        if (it == sessions().items.end()) return;
        Session& session = it->second;
        try {
            session.controls.ButtonPressed(session.button);
            session.controls.PlaybackPositionChangeRequested(session.seek);
            session.controls.ShuffleEnabledChangeRequested(session.shuffle);
            session.controls.AutoRepeatModeChangeRequested(session.repeat);
            session.controls.DisplayUpdater().ClearAll();
            session.controls.DisplayUpdater().Update();
            session.controls.PlaybackStatus(MediaPlaybackStatus::Closed);
            session.controls.IsEnabled(false);
        } catch (const winrt::hresult_error&) {
        }
        env->DeleteGlobalRef(session.listener);
        sessions().items.erase(it);
    });
}

}
