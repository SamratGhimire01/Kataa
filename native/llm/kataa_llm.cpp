// Kataa's on-device language model: a thin JNI wrapper around llama.cpp.
//
// One model, one context, one sequence. Each call is a single chat turn (system + user), answered with
// greedy decoding, optionally constrained by a GBNF grammar so the output is always valid JSON.
// The prompt prefix that matches the previous call (the long system prompt) stays in the KV cache, so
// only the new user text is processed: that is what makes replies take ~1-3 s on a phone, not ~10.
//
// Text crosses JNI as UTF-8 byte arrays (never JNI strings: modified UTF-8 breaks emoji).

#include <jni.h>
#include <cstdio>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <unistd.h>

#include "llama.h"

namespace {

// One context per job (understanding, answer picking): each keeps its own long system prompt in its KV cache,
// so switching jobs never re-reads a prompt. A context costs ~60 MB of RAM at n_ctx 2048.
constexpr int kSlots = 2;

struct Slot {
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    std::vector<llama_token> cached;  // tokens currently in this context's KV cache (sequence 0)
};

struct Engine {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    Slot slots[kSlots];
    std::string tmpl;
    std::mutex lock;
    FILE * file = nullptr;  // kept open while an mmap'd model loaded from a file descriptor lives
};

std::string bytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray toBytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    if (a) env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

bool initialized = false;

// The last warnings/errors, so the app can show why loading failed (no logcat on the user's phone).
std::mutex logMutex;
std::string lastLog;

void quietLog(ggml_log_level level, const char * text, void *) {
    if (level < GGML_LOG_LEVEL_WARN) return;
    fputs(text, stderr);
    std::lock_guard<std::mutex> lock(logMutex);
    lastLog += text;
    if (lastLog.size() > 2000) lastLog.erase(0, lastLog.size() - 2000);
}

Engine * finish(llama_model * model, int threads, int nCtx, FILE * file) {
    if (!model) { if (file) fclose(file); return nullptr; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    auto * e = new Engine();
    e->model = model;
    e->vocab = llama_model_get_vocab(model);
    e->file = file;
    for (auto & sl : e->slots) {
        sl.ctx = llama_init_from_model(model, cp);
        if (!sl.ctx) {
            for (auto & o : e->slots) { if (o.batch) llama_batch_ext_free(o.batch); if (o.ctx) llama_free(o.ctx); }
            llama_model_free(model); if (file) fclose(file); delete e; return nullptr;
        }
        sl.batch = llama_batch_ext_init(sl.ctx);
    }
    const char * t = llama_model_chat_template(model, nullptr);
    e->tmpl = t ? t : "chatml";
    return e;
}

llama_model_params modelParams(bool fast) {
    llama_model_params mp = llama_model_default_params();
    // fast: repack weights for the best CPU kernels (weights then live in RAM instead of the mapped file).
    mp.use_extra_bufts = fast;
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    return mp;
}

void ensureInit() {
    if (initialized) return;
    llama_log_set(quietLog, nullptr);
    llama_backend_init();
    initialized = true;
}

std::string applyTemplate(Engine * e, const std::string & system, const std::string & user) {
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(system.size() * 2 + user.size() * 2 + 512);
    int n = llama_chat_apply_template(e->tmpl.c_str(), msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(e->tmpl.c_str(), msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    return std::string(buf.data(), static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(Engine * e, const std::string & text) {
    int n = -llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    std::vector<llama_token> out(static_cast<size_t>(n > 0 ? n : 0));
    if (n > 0 && llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), n, true, true) < 0) out.clear();
    return out;
}

using Clock = std::chrono::steady_clock;

// A call's time budget: past it, the call gives up and returns nothing, so the app answers with its rules
// instead of making the user wait. No deadline when budgetMs <= 0.
struct Deadline {
    Clock::time_point at;
    bool on;
    explicit Deadline(int budgetMs) : at(Clock::now() + std::chrono::milliseconds(budgetMs)), on(budgetMs > 0) {}
    bool passed() const { return on && Clock::now() >= at; }
};

// Decodes tokens[from..] at positions from.., in chunks of n_batch. Logits only for the very last token.
// Returns how many tokens are now in the cache: tokens.size() when done, less when the deadline passed
// (what was read stays cached, so a long system prompt still gets read across calls), -1 on error.
long feed(Slot * e, const std::vector<llama_token> & tokens, size_t from, const Deadline & dl) {
    const size_t nBatch = llama_n_batch(e->ctx);
    for (size_t i = from; i < tokens.size(); i += nBatch) {
        if (dl.passed()) return static_cast<long>(i);
        const size_t end = std::min(tokens.size(), i + nBatch);
        llama_batch_ext_clear(e->batch);
        for (size_t j = i; j < end; ++j) {
            const int32_t idx = llama_batch_ext_add_token(e->batch, 0, tokens[j]);
            const llama_pos pos = static_cast<llama_pos>(j);
            llama_batch_ext_set_pos(e->batch, idx, &pos);
        }
        llama_batch_ext_set_output_logits(e->batch, static_cast<int32_t>(end - i - 1), end == tokens.size());
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch) != 0) return -1;
    }
    return static_cast<long>(tokens.size());
}

std::string complete(Engine * eng, int slot, const std::string & system, const std::string & user, const std::string & grammar, int maxTokens, int budgetMs) {
    // The budget starts before waiting for the lock: time spent behind another call counts too.
    const Deadline dl(budgetMs);
    std::lock_guard<std::mutex> g(eng->lock);
    if (dl.passed()) return {};
    if (slot < 0 || slot >= kSlots) slot = 0;
    Slot * e = &eng->slots[slot];
    const llama_vocab * vocab = eng->vocab;
    std::vector<llama_token> prompt = tokenize(eng, applyTemplate(eng, system, user));
    const size_t nCtx = llama_n_ctx(e->ctx);
    if (prompt.empty() || prompt.size() + static_cast<size_t>(maxTokens) >= nCtx) return {};

    // Reuse the cached prefix (the system prompt), drop the rest. Always re-feed at least the last token
    // so there are fresh logits to sample from.
    size_t common = 0;
    while (common < e->cached.size() && common < prompt.size() && e->cached[common] == prompt[common]) ++common;
    if (common == prompt.size()) --common;
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) { llama_memory_clear(mem, true); common = 0; }
    e->cached.assign(prompt.begin(), prompt.begin() + static_cast<long>(common));
    const long fed = feed(e, prompt, common, dl);
    if (fed < 0) { llama_memory_clear(mem, true); e->cached.clear(); return {}; }
    if (static_cast<size_t>(fed) < prompt.size()) {
        // Out of time: keep what was read (whole batches, at positions 0..fed-1), drop nothing else.
        e->cached.assign(prompt.begin(), prompt.begin() + fed);
        return {};
    }
    e->cached = prompt;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler * gs = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!gs) { llama_sampler_free(smpl); return {}; }
        llama_sampler_chain_add(smpl, gs);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    bool late = false;
    for (int i = 0; i < maxTokens && e->cached.size() < nCtx; ++i) {
        if (dl.passed()) { late = true; break; }
        const llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char piece[256];
        const int n = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, static_cast<size_t>(n));
        llama_batch_ext_clear(e->batch);
        const int32_t idx = llama_batch_ext_add_token(e->batch, 0, t);
        const llama_pos pos = static_cast<llama_pos>(e->cached.size());
        llama_batch_ext_set_pos(e->batch, idx, &pos);
        llama_batch_ext_set_output_logits(e->batch, 0, true);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch) != 0) break;
        e->cached.push_back(t);
    }
    llama_sampler_free(smpl);
    return late ? std::string() : out;  // a cut-off answer is worse than none (half a JSON object)
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_kataa_core_mind_LlamaNative_loadPath(JNIEnv * env, jclass, jbyteArray path, jint threads, jint nCtx, jboolean fast) {
    ensureInit();
    const std::string p = bytes(env, path);
    return reinterpret_cast<jlong>(finish(llama_model_load_from_file(p.c_str(), modelParams(fast)), threads, nCtx, nullptr));
}

// A model stored uncompressed inside the APK: [fd] is the APK, the GGUF starts at [offset].
// Returns -1 when the offset can't be memory-mapped (not 32-byte aligned): the caller copies the file out.
JNIEXPORT jlong JNICALL Java_app_kataa_core_mind_LlamaNative_loadFd(JNIEnv *, jclass, jint fd, jlong offset, jint threads, jint nCtx, jboolean fast) {
    ensureInit();
    if (offset % 32 != 0) return -1;
    const int own = dup(fd);
    if (own < 0) return 0;
    FILE * f = fdopen(own, "rb");
    if (!f) { close(own); return 0; }
    if (fseeko(f, static_cast<off_t>(offset), SEEK_SET) != 0) { fclose(f); return 0; }
    return reinterpret_cast<jlong>(finish(llama_model_load_from_file_ptr(f, modelParams(fast)), threads, nCtx, f));
}

JNIEXPORT jbyteArray JNICALL Java_app_kataa_core_mind_LlamaNative_lastLog(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(logMutex);
    std::string out = lastLog;
    lastLog.clear();
    return toBytes(env, out);
}

JNIEXPORT jbyteArray JNICALL Java_app_kataa_core_mind_LlamaNative_complete(JNIEnv * env, jclass, jlong h, jint slot, jbyteArray system,
                                                                           jbyteArray user, jbyteArray grammar, jint maxTokens, jint budgetMs) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    return toBytes(env, complete(e, slot, bytes(env, system), bytes(env, user), bytes(env, grammar), maxTokens, budgetMs));
}

JNIEXPORT void JNICALL Java_app_kataa_core_mind_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    for (auto & sl : e->slots) { llama_batch_ext_free(sl.batch); llama_free(sl.ctx); }
    llama_model_free(e->model);
    if (e->file) fclose(e->file);
    delete e;
}

}  // extern "C"
