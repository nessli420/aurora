#include <initguid.h>
#include "audio/wasapi.h"
#include <functiondiscoverykeys_devpkey.h>
#include <ksmedia.h>
#include <wrl/implements.h>

namespace aurora::audio {
namespace {

using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;

HRESULT enumerator(ComPtr<IMMDeviceEnumerator>& result) {
    return CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&result));
}

HRESULT client(const std::wstring& id, ComPtr<IAudioClient>& result) {
    ComPtr<IMMDevice> device;
    const HRESULT hr = findDevice(id, &device);
    return FAILED(hr) ? hr : device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &result);
}

class DeviceNotifier : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMMNotificationClient> {
public:
    explicit DeviceNotifier(DeviceCallback callback) : callback_(std::move(callback)) {}

    STDMETHODIMP OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR id) override {
        if (flow == eRender && role == eConsole) callback_(0, id ? id : L"", 0);
        return S_OK;
    }
    STDMETHODIMP OnDeviceAdded(LPCWSTR id) override { return notify(1, id, 0); }
    STDMETHODIMP OnDeviceRemoved(LPCWSTR id) override { return notify(2, id, 0); }
    STDMETHODIMP OnDeviceStateChanged(LPCWSTR id, DWORD state) override { return notify(3, id, state); }
    STDMETHODIMP OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }

private:
    HRESULT notify(int kind, LPCWSTR id, int64_t value) {
        if (id) callback_(kind, id, value);
        return S_OK;
    }

    DeviceCallback callback_;
};

}

bool makeFormat(int rate, int channels, int encoding, WAVEFORMATEXTENSIBLE& format) {
    struct Layout {
        WORD container;
        WORD valid;
        bool isFloat;
    };
    static constexpr Layout layouts[] = {{16, 16, false}, {24, 24, false}, {32, 24, false}, {32, 32, false}, {32, 32, true}};
    static constexpr DWORD masks[] = {KSAUDIO_SPEAKER_MONO, KSAUDIO_SPEAKER_STEREO, KSAUDIO_SPEAKER_STEREO | SPEAKER_FRONT_CENTER,
        KSAUDIO_SPEAKER_QUAD, KSAUDIO_SPEAKER_QUAD | SPEAKER_FRONT_CENTER, KSAUDIO_SPEAKER_5POINT1,
        KSAUDIO_SPEAKER_5POINT1 | SPEAKER_BACK_CENTER, KSAUDIO_SPEAKER_7POINT1_SURROUND};
    if (encoding < 0 || encoding > 4 || channels < 1 || channels > 8 || rate < 8000 || rate > 1536000) return false;
    const Layout& layout = layouts[encoding];
    format = {};
    format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    format.Format.nChannels = static_cast<WORD>(channels);
    format.Format.nSamplesPerSec = static_cast<DWORD>(rate);
    format.Format.wBitsPerSample = layout.container;
    format.Format.nBlockAlign = static_cast<WORD>(channels * layout.container / 8);
    format.Format.nAvgBytesPerSec = format.Format.nSamplesPerSec * format.Format.nBlockAlign;
    format.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    format.Samples.wValidBitsPerSample = layout.valid;
    format.dwChannelMask = masks[channels - 1];
    format.SubFormat = layout.isFloat ? KSDATAFORMAT_SUBTYPE_IEEE_FLOAT : KSDATAFORMAT_SUBTYPE_PCM;
    return true;
}

HRESULT findDevice(const std::wstring& id, IMMDevice** device) {
    ComPtr<IMMDeviceEnumerator> devices;
    const HRESULT hr = enumerator(devices);
    if (FAILED(hr)) return hr;
    return id.empty() ? devices->GetDefaultAudioEndpoint(eRender, eConsole, device) : devices->GetDevice(id.c_str(), device);
}

HRESULT listDevices(std::vector<DeviceInfo>& result) {
    ComPtr<IMMDeviceEnumerator> devices;
    ComPtr<IMMDeviceCollection> collection;
    HRESULT hr = enumerator(devices);
    if (SUCCEEDED(hr)) hr = devices->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &collection);
    UINT count = 0;
    if (SUCCEEDED(hr)) hr = collection->GetCount(&count);
    if (FAILED(hr)) return hr;
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> device;
        ComPtr<IPropertyStore> store;
        LPWSTR id = nullptr;
        if (FAILED(collection->Item(i, &device)) || FAILED(device->GetId(&id))) continue;
        DeviceInfo info{id, {}, 10};
        CoTaskMemFree(id);
        if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &store))) {
            PROPVARIANT value;
            PropVariantInit(&value);
            if (SUCCEEDED(store->GetValue(PKEY_Device_FriendlyName, &value)) && value.vt == VT_LPWSTR) info.name = value.pwszVal;
            PropVariantClear(&value);
            if (SUCCEEDED(store->GetValue(PKEY_AudioEndpoint_FormFactor, &value)) && value.vt == VT_UI4) {
                info.formFactor = static_cast<int>(value.ulVal);
            }
            PropVariantClear(&value);
        }
        result.push_back(std::move(info));
    }
    return S_OK;
}

HRESULT defaultDevice(std::wstring& id) {
    ComPtr<IMMDevice> device;
    LPWSTR value = nullptr;
    HRESULT hr = findDevice({}, &device);
    if (SUCCEEDED(hr)) hr = device->GetId(&value);
    if (FAILED(hr)) return hr;
    id = value;
    CoTaskMemFree(value);
    return S_OK;
}

HRESULT mixFormat(const std::wstring& id, WAVEFORMATEXTENSIBLE& format) {
    ComPtr<IAudioClient> audio;
    WAVEFORMATEX* mix = nullptr;
    HRESULT hr = client(id, audio);
    if (SUCCEEDED(hr)) hr = audio->GetMixFormat(&mix);
    if (FAILED(hr)) return hr;
    format = {};
    std::memcpy(&format, mix, mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE ? sizeof(WAVEFORMATEXTENSIBLE) : sizeof(WAVEFORMATEX));
    if (mix->wFormatTag != WAVE_FORMAT_EXTENSIBLE) {
        format.Samples.wValidBitsPerSample = mix->wBitsPerSample;
        format.SubFormat = mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ? KSDATAFORMAT_SUBTYPE_IEEE_FLOAT : KSDATAFORMAT_SUBTYPE_PCM;
    }
    CoTaskMemFree(mix);
    return S_OK;
}

HRESULT probeFormat(const std::wstring& id, bool exclusive, int rate, int channels, int encoding) {
    WAVEFORMATEXTENSIBLE format;
    if (!makeFormat(rate, channels, encoding, format)) return E_INVALIDARG;
    ComPtr<IAudioClient> audio;
    HRESULT hr = client(id, audio);
    if (FAILED(hr)) return hr;
    WAVEFORMATEX* closest = nullptr;
    hr = audio->IsFormatSupported(exclusive ? AUDCLNT_SHAREMODE_EXCLUSIVE : AUDCLNT_SHAREMODE_SHARED, &format.Format,
        exclusive ? nullptr : &closest);
    CoTaskMemFree(closest);
    return hr;
}

bool isRenderEndpoint(IMMDeviceEnumerator* devices, const std::wstring& id) {
    ComPtr<IMMDevice> device;
    ComPtr<IMMEndpoint> endpoint;
    EDataFlow flow = eRender;
    if (FAILED(devices->GetDevice(id.c_str(), &device)) || FAILED(device.As(&endpoint))) return true;
    return FAILED(endpoint->GetDataFlow(&flow)) || flow == eRender;
}

HRESULT watchDevices(DeviceCallback callback, ComPtr<IMMDeviceEnumerator>& devices, ComPtr<IMMNotificationClient>& notifier) {
    HRESULT hr = enumerator(devices);
    if (FAILED(hr)) return hr;
    notifier = Microsoft::WRL::Make<DeviceNotifier>(std::move(callback));
    if (!notifier) return E_OUTOFMEMORY;
    hr = devices->RegisterEndpointNotificationCallback(notifier.Get());
    if (FAILED(hr)) notifier.Reset();
    return hr;
}

}
