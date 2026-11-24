// Kataa's offline speech recognizer: whisper.cpp behind a tiny JNI API. Built into libkataa_llm so the
// recognizer and the mind share one copy of ggml. Text leaves as UTF-8 bytes (never JNI strings).
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
struct Ear {
    whisper_context * ctx = nullptr;
    int threads = 4;
    std::mutex lock;
};

std::string bytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

void quiet(ggml_log_level, const char *, void *) {}
}  // namespace

extern "C" {

// [model] is a direct ByteBuffer with the ggml model file (read from the APK); it is copied, so it can be freed after.
JNIEXPORT jlong JNICALL Java_app_kataa_core_speech_WhisperNative_init(JNIEnv * env, jclass, jobject model, jint threads) {
    whisper_log_set(quiet, nullptr);
    void * data = env->GetDirectBufferAddress(model);
    const jlong size = env->GetDirectBufferCapacity(model);
    if (!data || size <= 0) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context * ctx = whisper_init_from_buffer_with_params(data, static_cast<size_t>(size), cp);
    if (!ctx) return 0;
    auto * e = new Ear();
    e->ctx = ctx;
    e->threads = threads > 0 ? threads : 4;
    return reinterpret_cast<jlong>(e);
}

// [pcm] is 16 kHz mono float audio. [prompt] carries words to expect (names, places) as UTF-8.
JNIEXPORT jbyteArray JNICALL Java_app_kataa_core_speech_WhisperNative_transcribe(JNIEnv * env, jclass, jlong h, jfloatArray pcm, jbyteArray prompt) {
    auto * e = reinterpret_cast<Ear *>(h);
    if (!e || !pcm) return nullptr;
    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> audio(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, audio.data());
    const std::string p = bytes(env, prompt);

    std::lock_guard<std::mutex> guard(e->lock);
    whisper_full_params fp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    fp.n_threads = e->threads;
    fp.language = "en";
    fp.translate = false;
    fp.no_timestamps = true;
    fp.single_segment = true;
    fp.print_progress = false;
    fp.print_realtime = false;
    fp.print_special = false;
    fp.print_timestamps = false;
    fp.suppress_blank = true;
    fp.suppress_nst = true;
    fp.temperature = 0.0f;
    fp.temperature_inc = 0.2f;   // falls back to sampling only when greedy output looks broken
    fp.max_tokens = 96;
    fp.audio_ctx = 0;
    fp.initial_prompt = p.empty() ? nullptr : p.c_str();
    if (whisper_full(e->ctx, fp, audio.data(), n) != 0) return nullptr;
    std::string out;
    const int segs = whisper_full_n_segments(e->ctx);
    for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(e->ctx, i);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    if (arr && !out.empty()) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

JNIEXPORT void JNICALL Java_app_kataa_core_speech_WhisperNative_free(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Ear *>(h);
    if (!e) return;
    { std::lock_guard<std::mutex> guard(e->lock); whisper_free(e->ctx); }
    delete e;
}

}  // extern "C"
