#include <jni.h>
#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct State {
    std::atomic<uint64_t> buttons{0};
    std::atomic<int16_t> analogX{0};
    std::atomic<int16_t> analogY{0};
    std::atomic<ANativeWindow*> window{nullptr};
    std::atomic<unsigned> pixelFormat{RETRO_PIXEL_FORMAT_XRGB8888};
    std::atomic<uint64_t> videoFrames{0};
    std::mutex renderMutex;
    std::mutex audioMutex;
    std::mutex optionMutex;
    std::string systemDir;
    std::string saveDir;
    std::string contentDir;
    std::unordered_map<std::string, std::string> options;
    JavaVM* jvm = nullptr;
    jobject audioTrack = nullptr;
    jshortArray audioSamples = nullptr;
    jmethodID audioWrite = nullptr;
    size_t audioFrames = 0;
    jint audioMode = 1;
    int windowWidth = 0;
    int windowHeight = 0;
    bool initialized = false;
    bool loaded = false;
};

State g;

JNIEnv* envForThread() {
    if (!g.jvm) return nullptr;
    JNIEnv* env = nullptr;
    if (g.jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    return env;
}

std::string jniString(JNIEnv* env, jstring value) {
    if (!env || !value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void coreLogCallback(retro_log_level level, const char* fmt, ...) {
    if (!fmt) return;
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_DEBUG:
            priority = ANDROID_LOG_DEBUG;
            break;
        case RETRO_LOG_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        case RETRO_LOG_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case RETRO_LOG_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        default:
            break;
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, "NJPPSSPP", fmt, args);
    va_end(args);
}

bool environmentCallback(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            if (!data) return false;
            auto* callback = static_cast<retro_log_callback*>(data);
            callback->log = coreLogCallback;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (!data) return false;
            unsigned format = *static_cast<const unsigned*>(data);
            if (format != RETRO_PIXEL_FORMAT_XRGB8888 && format != RETRO_PIXEL_FORMAT_RGB565) return false;
            g.pixelFormat.store(format);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            if (!data) return false;
            *static_cast<const char**>(data) = g.systemDir.c_str();
            return !g.systemDir.empty();
        }
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY: {
            if (!data) return false;
            *static_cast<const char**>(data) = g.contentDir.c_str();
            return !g.contentDir.empty();
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (!data) return false;
            *static_cast<const char**>(data) = g.saveDir.c_str();
            return !g.saveDir.empty();
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* variable = static_cast<retro_variable*>(data);
            if (!variable || !variable->key) return false;
            std::lock_guard<std::mutex> lock(g.optionMutex);
            auto it = g.options.find(variable->key);
            if (it == g.options.end()) return false;
            variable->value = it->second.c_str();
            __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "option %s=%s", variable->key, variable->value);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_MESSAGE:
        case RETRO_ENVIRONMENT_SHUTDOWN:
            return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER:
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
            return false;
        default:
            return false;
    }
}

void videoRefreshCallback(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0) return;
    ANativeWindow* window = g.window.load();
    if (!window) {
        if (g.videoFrames.load() == 0) {
            __android_log_print(ANDROID_LOG_WARN, "NJPPSSPP", "video frame with no surface %ux%u", width, height);
        }
        return;
    }
    const uint64_t frame = ++g.videoFrames;
    if (frame == 1 || (frame % 300u) == 0u) {
        __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "video frame=%llu %ux%u pitch=%zu fmt=%u",
                            static_cast<unsigned long long>(frame), width, height, pitch, g.pixelFormat.load());
    }
    std::lock_guard<std::mutex> lock(g.renderMutex);
    if (g.windowWidth != static_cast<int>(width) || g.windowHeight != static_cast<int>(height)) {
        ANativeWindow_setBuffersGeometry(window, static_cast<int>(width), static_cast<int>(height), WINDOW_FORMAT_RGBA_8888);
        g.windowWidth = static_cast<int>(width);
        g.windowHeight = static_cast<int>(height);
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) return;
    if (buffer.bits && buffer.width >= static_cast<int32_t>(width) && buffer.height >= static_cast<int32_t>(height)) {
        const auto* source = static_cast<const uint8_t*>(data);
        const size_t sourcePitch = pitch ? pitch : static_cast<size_t>(width) * 4u;
        if (g.pixelFormat.load() == RETRO_PIXEL_FORMAT_RGB565) {
            for (unsigned y = 0; y < height; ++y) {
                const auto* src = reinterpret_cast<const uint16_t*>(source + static_cast<size_t>(y) * sourcePitch);
                auto* dst = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(buffer.bits) + static_cast<size_t>(y) * buffer.stride * 4u);
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t pixel = src[x];
                    uint32_t r = static_cast<uint32_t>((pixel >> 11) & 0x1fU) * 255U / 31U;
                    uint32_t green = static_cast<uint32_t>((pixel >> 5) & 0x3fU) * 255U / 63U;
                    uint32_t b = static_cast<uint32_t>(pixel & 0x1fU) * 255U / 31U;
                    dst[x] = 0xFF000000u | (b << 16) | (green << 8) | r;
                }
            }
        } else {
            for (unsigned y = 0; y < height; ++y) {
                const auto* src = reinterpret_cast<const uint32_t*>(source + static_cast<size_t>(y) * sourcePitch);
                auto* dst = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(buffer.bits) + static_cast<size_t>(y) * buffer.stride * 4u);
                for (unsigned x = 0; x < width; ++x) dst[x] = 0xFF000000u | (src[x] & 0x00FFFFFFu);
            }
        }
    }
    ANativeWindow_unlockAndPost(window);
}

void audioSampleCallback(int16_t, int16_t) {
}

size_t audioSampleBatchCallback(const int16_t* data, size_t frames) {
    if (!data || frames == 0) return 0;
    std::lock_guard<std::mutex> lock(g.audioMutex);
    if (!g.audioTrack || !g.audioSamples || !g.audioWrite || g.audioFrames == 0) return frames;
    JNIEnv* env = envForThread();
    if (!env) return frames;
    size_t toWrite = std::min(frames, g.audioFrames);
    env->SetShortArrayRegion(g.audioSamples, 0, static_cast<jsize>(toWrite * 2u), reinterpret_cast<const jshort*>(data));
    env->CallIntMethod(g.audioTrack, g.audioWrite, g.audioSamples, 0, static_cast<jint>(toWrite * 2u), g.audioMode);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return frames;
}

void inputPollCallback() {
}

int16_t inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD && index == 0) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(g.buttons.load() & 0xFFFu);
        if (id < 16) return (g.buttons.load() & (1ULL << id)) != 0 ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && id == RETRO_DEVICE_ID_ANALOG_X) {
        return index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? g.analogX.load() : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && id == RETRO_DEVICE_ID_ANALOG_Y) {
        return index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? g.analogY.load() : 0;
    }
    return 0;
}

void ensureCore() {
    if (g.initialized) return;
    retro_set_environment(environmentCallback);
    retro_set_video_refresh(videoRefreshCallback);
    retro_set_audio_sample(audioSampleCallback);
    retro_set_audio_sample_batch(audioSampleBatchCallback);
    retro_set_input_poll(inputPollCallback);
    retro_set_input_state(inputStateCallback);
    retro_init();
    g.initialized = true;
    __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "retro_init complete");
}

void clearAudio(JNIEnv* env) {
    if (!env) return;
    std::lock_guard<std::mutex> lock(g.audioMutex);
    if (g.audioTrack) {
        env->DeleteGlobalRef(g.audioTrack);
        g.audioTrack = nullptr;
    }
    if (g.audioSamples) {
        env->DeleteGlobalRef(g.audioSamples);
        g.audioSamples = nullptr;
    }
    g.audioWrite = nullptr;
    g.audioFrames = 0;
}

bool writeBytes(const std::string& path, const void* data, size_t size) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output.is_open()) return false;
    output.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return output.good();
}

bool readBytes(const std::string& path, void* data, size_t size) {
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) return false;
    input.read(static_cast<char*>(data), static_cast<std::streamsize>(size));
    return input.gcount() == static_cast<std::streamsize>(size);
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g.jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_init(JNIEnv*, jobject) {
    ensureCore();
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setPaths(JNIEnv* env, jobject, jstring systemDir, jstring saveDir, jstring contentDir) {
    g.systemDir = jniString(env, systemDir);
    g.saveDir = jniString(env, saveDir);
    g.contentDir = jniString(env, contentDir);
    __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "paths system=%s save=%s content=%s",
                        g.systemDir.c_str(), g.saveDir.c_str(), g.contentDir.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setOption(JNIEnv* env, jobject, jstring key, jstring value) {
    std::string name = jniString(env, key);
    if (name.empty()) return;
    std::lock_guard<std::mutex> lock(g.optionMutex);
    if (!value) {
        g.options.erase(name);
        return;
    }
    g.options[name] = jniString(env, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setSurface(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* next = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(g.renderMutex);
    ANativeWindow* previous = g.window.exchange(next);
    if (previous) ANativeWindow_release(previous);
    g.windowWidth = 0;
    g.windowHeight = 0;
    __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "setSurface=%d", next != nullptr ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setAudioTrack(JNIEnv* env, jobject, jobject track, jint frames, jint mode) {
    clearAudio(env);
    if (!env || !track || frames <= 0) return;
    jclass clazz = env->GetObjectClass(track);
    jmethodID write = env->GetMethodID(clazz, "write", "([SIII)I");
    env->DeleteLocalRef(clazz);
    if (!write) return;
    jshortArray local = env->NewShortArray(static_cast<jsize>(frames) * 2);
    if (!local) return;
    jobject globalSamples = env->NewGlobalRef(local);
    jobject globalTrack = env->NewGlobalRef(track);
    env->DeleteLocalRef(local);
    if (!globalSamples || !globalTrack) {
        if (globalSamples) env->DeleteGlobalRef(globalSamples);
        if (globalTrack) env->DeleteGlobalRef(globalTrack);
        return;
    }
    std::lock_guard<std::mutex> lock(g.audioMutex);
    g.audioTrack = globalTrack;
    g.audioSamples = static_cast<jshortArray>(globalSamples);
    g.audioWrite = write;
    g.audioFrames = static_cast<size_t>(frames);
    g.audioMode = mode;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_loadRom(JNIEnv* env, jobject, jstring path) {
    ensureCore();
    if (g.loaded) {
        retro_unload_game();
        g.loaded = false;
    }
    std::string resolved = jniString(env, path);
    if (resolved.empty()) return JNI_FALSE;
    retro_game_info info{};
    info.path = resolved.c_str();
    info.data = nullptr;
    info.size = 0;
    info.meta = nullptr;
    g.loaded = retro_load_game(&info);
    __android_log_print(ANDROID_LOG_INFO, "NJPPSSPP", "retro_load_game=%d path=%s",
                        g.loaded ? 1 : 0, resolved.c_str());
    return g.loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_unload(JNIEnv*) {
    if (g.loaded) {
        retro_unload_game();
        g.loaded = false;
    }
    if (g.initialized) {
        retro_deinit();
        g.initialized = false;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_runFrame(JNIEnv*, jobject) {
    if (!g.loaded) return JNI_FALSE;
    retro_run();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setButtons(JNIEnv*, jobject, jlong mask) {
    g.buttons.store(static_cast<uint64_t>(mask));
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_setAnalog(JNIEnv*, jobject, jshort x, jshort y) {
    g.analogX.store(x);
    g.analogY.store(y);
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_avInfo(JNIEnv* env, jobject) {
    jint values[4] = {480, 272, 60, 44100};
    if (g.loaded) {
        retro_system_av_info info{};
        retro_get_system_av_info(&info);
        values[0] = static_cast<jint>(info.geometry.base_width);
        values[1] = static_cast<jint>(info.geometry.base_height);
        values[2] = info.timing.fps > 0.0 && info.timing.fps < 1000.0 ? static_cast<jint>(info.timing.fps) : 60;
        values[3] = info.timing.sample_rate > 0.0 && info.timing.sample_rate < 400000.0 ? static_cast<jint>(info.timing.sample_rate) : 44100;
    }
    jintArray result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_reset(JNIEnv*, jobject) {
    if (g.loaded) retro_reset();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_saveState(JNIEnv* env, jobject, jstring path) {
    if (!g.loaded) return JNI_FALSE;
    size_t size = retro_serialize_size();
    if (size == 0) return JNI_FALSE;
    std::vector<uint8_t> data(size);
    if (!retro_serialize(data.data(), size)) return JNI_FALSE;
    return writeBytes(jniString(env, path), data.data(), size) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_ui_screens_ppsspp_PpssppRetroNative_loadState(JNIEnv* env, jobject, jstring path) {
    if (!g.loaded) return JNI_FALSE;
    size_t size = retro_serialize_size();
    if (size == 0) return JNI_FALSE;
    std::vector<uint8_t> data(size);
    if (!readBytes(jniString(env, path), data.data(), size)) return JNI_FALSE;
    return retro_unserialize(data.data(), size) ? JNI_TRUE : JNI_FALSE;
}
