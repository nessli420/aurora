#include "alsa_output.h"

namespace aurora::alsa {
namespace {

std::vector<snd_pcm_format_t> candidates(int encoding) {
    switch (encoding) {
    case S16: return {SND_PCM_FORMAT_S16_LE};
    case S24: return {SND_PCM_FORMAT_S24_3LE};
    case S24In32: return {SND_PCM_FORMAT_S32_LE, SND_PCM_FORMAT_S24_LE};
    case S32: return {SND_PCM_FORMAT_S32_LE};
    case F32: return {SND_PCM_FORMAT_FLOAT_LE};
    default: return {};
    }
}

struct Ctl {
    snd_ctl_t* handle = nullptr;
    ~Ctl() { if (handle) snd_ctl_close(handle); }
};

struct Pcm {
    snd_pcm_t* handle = nullptr;
    ~Pcm() { if (handle) snd_pcm_close(handle); }
};

}

bool chooseFormat(snd_pcm_t* pcm, const snd_pcm_hw_params_t* space, int encoding, int channels, int rate, snd_pcm_format_t& format) {
    snd_pcm_hw_params_t* params;
    snd_pcm_hw_params_alloca(&params);
    for (const snd_pcm_format_t candidate : candidates(encoding)) {
        snd_pcm_hw_params_copy(params, space);
        if (snd_pcm_hw_params_set_access(pcm, params, SND_PCM_ACCESS_RW_INTERLEAVED) < 0) return false;
        if (snd_pcm_hw_params_set_format(pcm, params, candidate) < 0) continue;
        if (snd_pcm_hw_params_set_channels(pcm, params, static_cast<unsigned>(channels)) < 0) continue;
        if (snd_pcm_hw_params_test_rate(pcm, params, static_cast<unsigned>(rate), 0) < 0) continue;
        format = candidate;
        return true;
    }
    return false;
}

int listDevices(std::vector<DeviceInfo>& devices) {
    int card = -1;
    int error = 0;
    snd_ctl_card_info_t* cardInfo;
    snd_pcm_info_t* pcmInfo;
    snd_ctl_card_info_alloca(&cardInfo);
    snd_pcm_info_alloca(&pcmInfo);
    while ((error = snd_card_next(&card)) == 0 && card >= 0) {
        Ctl ctl;
        const std::string control = "hw:" + std::to_string(card);
        if (snd_ctl_open(&ctl.handle, control.c_str(), 0) < 0) continue;
        if (snd_ctl_card_info(ctl.handle, cardInfo) < 0) continue;
        const std::string id = snd_ctl_card_info_get_id(cardInfo);
        const std::string name = snd_ctl_card_info_get_name(cardInfo);
        const std::string driver = snd_ctl_card_info_get_driver(cardInfo);
        int device = -1;
        while (snd_ctl_pcm_next_device(ctl.handle, &device) == 0 && device >= 0) {
            snd_pcm_info_set_device(pcmInfo, static_cast<unsigned>(device));
            snd_pcm_info_set_subdevice(pcmInfo, 0);
            snd_pcm_info_set_stream(pcmInfo, SND_PCM_STREAM_PLAYBACK);
            if (snd_ctl_pcm_info(ctl.handle, pcmInfo) < 0) continue;
            devices.push_back({
                "hw:CARD=" + id + ",DEV=" + std::to_string(device),
                name,
                snd_pcm_info_get_name(pcmInfo),
                driver,
                "plughw:" + std::to_string(card) + "," + std::to_string(device),
            });
        }
    }
    return error < 0 ? error : 0;
}

int capabilities(const std::string& device, const std::vector<int>& rates, int channels, std::vector<int>& masks) {
    Pcm pcm;
    int error = snd_pcm_open(&pcm.handle, device.c_str(), SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK);
    if (error < 0) return error;
    snd_pcm_hw_params_t* space;
    snd_pcm_hw_params_alloca(&space);
    if ((error = snd_pcm_hw_params_any(pcm.handle, space)) < 0) return error;
    masks.assign(rates.size(), 0);
    for (size_t i = 0; i < rates.size(); ++i) {
        for (int encoding = 0; encoding < EncodingCount; ++encoding) {
            snd_pcm_format_t format;
            if (chooseFormat(pcm.handle, space, encoding, channels, rates[i], format)) masks[i] |= 1 << encoding;
        }
    }
    return 0;
}

}
