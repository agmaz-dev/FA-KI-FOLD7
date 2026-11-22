// FA Fold KI: lokales Sprachmodell (GGUF) auf dem Handy über llama.cpp (JNI).
// Übernommen aus agmaz-dev/Jarvis-Android (app/src/main/cpp/jarvis_llm.cpp) und angepasst:
// eigene JNI-Namen (de.foldki.app.ai.LlamaNative), Kontext bis 8192, Ausgabe bis 4000 Tokens,
// Sampler-Werte pro Aufruf, "Denken" an/aus für Thinking-Modelle (Qwen3 & Co.).
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <mutex>
#include <set>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>

#include "llama.h"
#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "gguf.h"

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static common_chat_templates_ptr g_tmpl;
static std::vector<llama_token> g_cache;   // was gerade im KV-Speicher steht (für schnelle Folgefragen)
static std::atomic<bool> g_stop{false};
static std::atomic<bool> g_think_open{false};   // Vorlage endet mit <think>: Ausgabe beginnt mit Gedanken
static std::mutex g_mu;
static std::string g_err;
static std::string g_backend_info;   // z. B. "GPU (QUALCOMM Adreno(TM) 830): 27 von 36 Schichten"
static std::mutex g_info_mu;          // eigenes Schloss: die Anzeige darf nicht auf eine laufende Antwort warten
static bool g_backends_loaded = false;
static std::string g_lib_dir;         // nativeLibraryDir der App (dort liegen alle libggml-*.so)

// ---- NPU (Hexagon) ----
// Der NPU-Teil (libggml-hexagon.so + libggml-htp-v79.so u. a.) wird nur geladen, wenn "NPU" gewählt ist.
// Die NPU meldet sich bei llama.cpp als "GPU"-Gerät; deshalb werden die Geräte immer ausdrücklich gewählt.
static bool g_hex_tried = false;
static ggml_backend_dev_t g_hex_dev = nullptr;
static int *g_hex_verbose = nullptr;  // opt_verbose im NPU-Teil: nur beim Laden an, damit abgelehnte Rechenschritte sichtbar werden
static std::mutex g_npu_mu;
static std::string g_npu_log;         // Zeilen "ggml-hex: …" (Arch-Version, Fehler) für den Fehlerbericht
static std::set<std::string> g_npu_rejected;   // Rechenschritte, die die NPU abgelehnt hat (laufen auf dem Prozessor)
static std::string g_log_line;
static ggml_log_level g_log_level = GGML_LOG_LEVEL_INFO;
static bool g_npu_collect = false;

// Alle Meldungen von llama.cpp/ggml: Warnungen und NPU-Zeilen ins Android-Log, NPU-Zeilen zusätzlich in den Bericht.
static void log_cb(ggml_log_level level, const char *text, void *) {
    if (!text) return;
    std::lock_guard<std::mutex> lk(g_npu_mu);
    if (level != GGML_LOG_LEVEL_CONT && g_log_line.empty()) g_log_level = level;
    level = g_log_level;
    g_log_line += text;
    size_t nl;
    while ((nl = g_log_line.find('\n')) != std::string::npos) {
        std::string line = g_log_line.substr(0, nl);
        g_log_line.erase(0, nl + 1);
        bool hex = line.find("ggml-hex") != std::string::npos;
        if (hex) {
            size_t p = line.find("supports-op ");
            if (p != std::string::npos) {
                // "ggml-hex: HTP0 supports-op <OP>…|no" -> nur den Namen des Rechenschritts merken
                if (g_npu_collect && line.size() >= 3 && line.compare(line.size() - 3, 3, "|no") == 0 && g_npu_rejected.size() < 64) {
                    std::string op = line.substr(p + 12);
                    size_t e = op.find_first_of(" :|(");
                    g_npu_rejected.insert(e == std::string::npos ? op : op.substr(0, e));
                }
                continue;   // sehr viele Zeilen, nicht einzeln protokollieren
            }
            if (line.find("execute-op") != std::string::npos || line.find("device-supports-buft") != std::string::npos) continue;
            if (g_npu_log.size() < 12000) g_npu_log += line + "\n";
        }
        if (hex || level >= GGML_LOG_LEVEL_WARN) {
            __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level >= GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                                "FoldKI-llama", "%s", line.c_str());
        }
    }
    if (g_log_line.size() > 4096) g_log_line.clear();
}

static void npu_note(const std::string &s) {
    std::lock_guard<std::mutex> lk(g_npu_mu);
    if (g_npu_log.size() < 12000) g_npu_log += s + "\n";
}

static void free_all() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_tmpl.reset();
    g_cache.clear();
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Länge des gültigen UTF-8-Anfangs (unvollständige Zeichen am Ende zurückhalten)
static size_t utf8_valid_prefix(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return (back + 1 >= need) ? n : i - 1;
    }
    return n;
}

static bool ends_with_think(const std::string &s) {
    size_t e = s.find_last_not_of(" \n\r\t");
    if (e == std::string::npos) return false;
    std::string t = s.substr(0, e + 1);
    const std::string tag = "<think>";
    return t.size() >= tag.size() && t.compare(t.size() - tag.size(), tag.size(), tag) == 0;
}

// Welche Rechen-Beschleuniger aktiv sind (nur die wichtigen, als kurzer Text)
static std::string cpu_flags() {
    std::string all = llama_print_system_info(), out;
    for (const char *k : {"DOTPROD = 1", "MATMUL_INT8 = 1", "KLEIDIAI = 1", "REPACK = 1", "SVE = 1"}) {
        if (all.find(k) != std::string::npos) { if (!out.empty()) out += ","; out += std::string(k).substr(0, std::string(k).find(' ')); }
    }
    return out.empty() ? "basic" : out;
}

// Rechenteile laden (libggml-cpu.so, libggml-opencl.so). Fehlt OpenCL auf dem Gerät, bleibt nur der Prozessor.
// Der NPU-Teil wird hier bewusst NICHT geladen (nur bei Wahl "NPU", siehe load_hexagon).
static void load_backends(const std::string &libDir) {
    if (!libDir.empty()) g_lib_dir = libDir;
    if (g_backends_loaded) return;
    llama_log_set(log_cb, nullptr);
    for (const char *name : {"libggml-cpu.so", "libggml-opencl.so"}) {
        bool ok = false;
        if (!g_lib_dir.empty()) ok = ggml_backend_load((g_lib_dir + "/" + name).c_str()) != nullptr;
        // Ersatz, falls die Bibliotheken nicht entpackt im Ordner liegen: über den Namen direkt aus der APK
        if (!ok) ggml_backend_load(name);
    }
    llama_backend_init();
    g_backends_loaded = true;
}

// Die Grafikkarte (Adreno über OpenCL) – ausdrücklich über den Namen, weil sich auch die NPU als "GPU" meldet.
static ggml_backend_dev_t opencl_dev() {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) != GGML_BACKEND_DEVICE_TYPE_GPU) continue;
        ggml_backend_reg_t r = ggml_backend_dev_backend_reg(d);
        if (r && strcmp(ggml_backend_reg_name(r), "OpenCL") == 0) return d;
    }
    return nullptr;
}

static const char *gpu_name() {
    ggml_backend_dev_t d = opencl_dev();
    return d ? ggml_backend_dev_description(d) : nullptr;
}

static std::string npu_lib_path() { return g_lib_dir.empty() ? std::string() : g_lib_dir + "/libggml-hexagon.so"; }

static bool file_exists(const std::string &p) { return !p.empty() && access(p.c_str(), F_OK) == 0; }

// NPU-Teil einmal laden: Suchpfad für die DSP-Bibliothek (libggml-htp-v79.so) auf den App-Ordner setzen,
// dann libggml-hexagon.so laden und das Gerät HTP0 holen. nullptr = NPU nicht nutzbar (Grund steht im Bericht).
static ggml_backend_dev_t load_hexagon() {
    if (g_hex_tried) return g_hex_dev;
    g_hex_tried = true;
    std::string path = npu_lib_path();
    if (!file_exists(path)) { npu_note("NPU: libggml-hexagon.so fehlt in dieser APK (NPU-Bau in GitHub Actions fehlgeschlagen?)"); return nullptr; }
    std::string adsp = g_lib_dir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    setenv("GGML_HEXAGON_LIBHTP_DIR", g_lib_dir.c_str(), 1);
    setenv("GGML_HEXAGON_OPPOLL", "1", 0);   // wie scripts/snapdragon/run.py
    npu_note("NPU: ADSP_LIBRARY_PATH = " + g_lib_dir);
    ggml_backend_reg_t reg = ggml_backend_load(path.c_str());
    if (!reg) {
        void *h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
        npu_note(std::string("NPU: Hexagon-Teil ließ sich nicht starten. libcdsprpc.so: ") + (h ? "erreichbar" : (dlerror() ?: "nicht erreichbar")));
        if (h) dlclose(h);
        return nullptr;
    }
    for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); i++) {
        ggml_backend_dev_t d = ggml_backend_reg_dev_get(reg, i);
        if (d && strncmp(ggml_backend_dev_name(d), "HTP0", 4) == 0) { g_hex_dev = d; break; }
    }
    if (!g_hex_dev && ggml_backend_reg_dev_count(reg) > 0) g_hex_dev = ggml_backend_reg_dev_get(reg, 0);
    if (!g_hex_dev) { npu_note("NPU: kein Gerät HTP0 gefunden"); return nullptr; }
    npu_note(std::string("NPU: Gerät ") + ggml_backend_dev_name(g_hex_dev) + " (" + ggml_backend_dev_description(g_hex_dev) + ")");
    if (void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_NOLOAD)) {
        g_hex_verbose = (int *) dlsym(h, "opt_verbose");
        dlclose(h);   // bleibt geladen (ggml hält die Bibliothek)
    }
    return g_hex_dev;
}

static void npu_verbose(bool on) {
    std::lock_guard<std::mutex> lk(g_npu_mu);
    g_npu_collect = on;
    if (g_hex_verbose) *g_hex_verbose = on ? 1 : 0;
}

// "general.file_type" der GGUF-Datei (2 = Q4_0). -1 = unbekannt.
static int gguf_ftype(const std::string &path) {
    gguf_init_params gp = {/*no_alloc*/ true, /*ctx*/ nullptr};
    gguf_context *g = gguf_init_from_file(path.c_str(), gp);
    if (!g) return -1;
    int t = -1;
    int64_t k = gguf_find_key(g, "general.file_type");
    if (k >= 0) t = (int) gguf_get_val_u32(g, k);
    gguf_free(g);
    return t;
}

// Anzahl der Schichten aus der GGUF-Datei ("<arch>.block_count"), ohne das Modell zu laden. 0 = unbekannt.
static int gguf_layers(const std::string &path) {
    gguf_init_params gp = {/*no_alloc*/ true, /*ctx*/ nullptr};
    gguf_context *g = gguf_init_from_file(path.c_str(), gp);
    if (!g) return 0;
    int n = 0;
    int64_t ka = gguf_find_key(g, "general.architecture");
    if (ka >= 0) {
        std::string key = std::string(gguf_get_val_str(g, ka)) + ".block_count";
        int64_t kb = gguf_find_key(g, key.c_str());
        if (kb >= 0) n = (int) gguf_get_val_u32(g, kb);
    }
    gguf_free(g);
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeInit(JNIEnv *env, jclass, jstring jdir) {
    std::lock_guard<std::mutex> lk(g_mu);
    load_backends(jstr(env, jdir));
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeBackendInfo(JNIEnv *env, jclass) {
    std::string s;
    { std::lock_guard<std::mutex> lk(g_info_mu); s = g_backend_info; }
    if (s.empty() && g_backends_loaded) { const char *g = gpu_name(); s = g ? std::string("GPU gefunden: ") + g : "Keine GPU (OpenCL) gefunden – nur Prozessor"; }
    return env->NewStringUTF(s.c_str());
}

// Nur die Geräte, die wir wollen (NPU ODER Grafikkarte ODER keines = nur Prozessor). Leere Liste = nur Prozessor.
static std::vector<ggml_backend_dev_t> g_devs;
static llama_model *load_model(const std::string &path, ggml_backend_dev_t dev, int ngl) {
    g_devs.clear();
    if (dev && ngl > 0) g_devs.push_back(dev);
    g_devs.push_back(nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.devices = g_devs.data();
    mp.n_gpu_layers = (dev && ngl > 0) ? ngl : 0;
    return llama_model_load_from_file(path.c_str(), mp);
}

static llama_context *make_ctx(llama_model *m, int nCtx, int nThreads, bool npu) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx > 0 ? nCtx : 8192;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    // Antwort schreiben: wenige Threads (Speicher ist der Engpass), Eingabe lesen: mehr Threads
    cp.n_threads = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = std::min(8, cp.n_threads + 2);
    cp.swa_full = true;   // damit der Gesprächsanfang wiederverwendet werden kann
    cp.flash_attn_type = npu ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;   // NPU: wie run.py "-fa on"
    // NPU: KV-Speicher F16 (Standard von llama.cpp auf der NPU), sonst komprimiert (Q8_0)
    cp.type_k = npu ? GGML_TYPE_F16 : GGML_TYPE_Q8_0;
    cp.type_v = npu ? GGML_TYPE_F16 : GGML_TYPE_Q8_0;
    llama_context *c = llama_init_from_model(m, cp);
    if (!c && npu) { cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO; c = llama_init_from_model(m, cp); }
    if (!c) {
        // Ersatz 1: ohne komprimierten KV-Speicher, halber Kontext
        cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16; cp.n_ctx = std::min<uint32_t>(cp.n_ctx, 4096);
        c = llama_init_from_model(m, cp);
    }
    if (!c) {
        // Ersatz 2: kleinster sinnvoller Kontext
        cp.n_ctx = 2048;
        c = llama_init_from_model(m, cp);
    }
    return c;
}

// gpuPercent: Anteil der Schichten auf der GPU (0 = nur Prozessor, 100 = alles). npu: alle Schichten auf die NPU
// (Hexagon, HTP0) statt auf die GPU. Klappt das Laden mit GPU/NPU nicht, wird automatisch nur mit dem Prozessor geladen.
extern "C" JNIEXPORT jboolean JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads, jint gpuPercent, jboolean jnpu) {
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
    g_err.clear();
    { std::lock_guard<std::mutex> il(g_info_mu); g_backend_info.clear(); }
    load_backends("");
    std::string path = jstr(env, jpath);
    const bool wantNpu = jnpu == JNI_TRUE;
    const char *gpu = gpu_name();
    ggml_backend_dev_t gpuDev = opencl_dev();
    int layers = gguf_layers(path);
    int pct = std::max(0, std::min(100, (int) gpuPercent));

    ggml_backend_dev_t dev = nullptr;
    int ngl = 0;
    ggml_backend_dev_t npuDev = nullptr;
    if (wantNpu) {
        { std::lock_guard<std::mutex> nl(g_npu_mu); g_npu_log.clear(); g_npu_rejected.clear(); }
        npuDev = load_hexagon();
        int ft = gguf_ftype(path);
        if (ft != LLAMA_FTYPE_MOSTLY_Q4_0)
            npu_note("NPU: Datei ist nicht Q4_0 (Typ " + std::to_string(ft) + ") – die NPU rechnet nur Q4_0 schnell, der Rest läuft auf dem Prozessor");
        if (npuDev) { dev = npuDev; ngl = layers > 0 ? layers + 1 : 999; }   // NPU zählt wie GPU bei -ngl
    } else if (gpuDev && pct > 0) {
        dev = gpuDev;
        if (layers <= 0) ngl = pct >= 100 ? 999 : 0;   // Schichtzahl unbekannt: nur bei 100 % auf die GPU
        else if (pct >= 100) ngl = layers + 1;          // + Ausgabeschicht
        else ngl = std::max(1, (int) ((layers * pct + 50) / 100));
    }
    const bool onNpu = npuDev && dev == npuDev;
    if (onNpu) npu_verbose(true);
    g_model = load_model(path, dev, ngl);
    if (g_model && ngl > 0) {
        g_ctx = make_ctx(g_model, nCtx, nThreads, onNpu);
        if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; }
    }
    if (onNpu) npu_verbose(false);
    if (!g_model) {
        // GPU/NPU hat nicht geklappt (oder war aus): nur mit dem Prozessor
        if (ngl > 0 && onNpu) npu_note("NPU: Laden auf HTP0 fehlgeschlagen – nur Prozessor");
        bool retry = ngl > 0;
        ngl = 0;
        if (retry) g_model = load_model(path, nullptr, 0);
        if (!g_model) { g_err = "Modell konnte nicht geladen werden (Datei defekt, kein GGUF oder zu wenig Arbeitsspeicher)."; return JNI_FALSE; }
    }
    if (!g_ctx) g_ctx = make_ctx(g_model, nCtx, nThreads, false);
    if (!g_ctx) { g_err = "Zu wenig Arbeitsspeicher für dieses Modell."; free_all(); return JNI_FALSE; }
    {
        int total = layers > 0 ? layers : (int) llama_model_n_layer(g_model);
        int on_dev = std::min(ngl, total);
        char info[256];
        if (on_dev > 0 && onNpu) snprintf(info, sizeof(info), "NPU (Hexagon HTP0): %d von %d Schichten", on_dev, total);
        else if (on_dev > 0) snprintf(info, sizeof(info), "GPU (%s): %d von %d Schichten", gpu, on_dev, total);
        else if (wantNpu) snprintf(info, sizeof(info), "Nur Prozessor (NPU ließ sich nicht nutzen – siehe Fehlerbericht)");
        else if (gpu && pct > 0) snprintf(info, sizeof(info), "Nur Prozessor (GPU %s ließ sich nicht nutzen)", gpu);
        else if (gpu) snprintf(info, sizeof(info), "Nur Prozessor (GPU %s ist ausgeschaltet)", gpu);
        else snprintf(info, sizeof(info), "Nur Prozessor (keine GPU über OpenCL gefunden)");
        std::lock_guard<std::mutex> il(g_info_mu);
        g_backend_info = info;
    }
    try {
        g_tmpl = common_chat_templates_init(g_model, "");
    } catch (...) {
        g_tmpl.reset();
    }
    return JNI_TRUE;
}

// Bericht der letzten NPU-Ladung: Hexagon-Meldungen (z. B. "Hexagon Arch version v79") und abgelehnte Rechenschritte.
extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeNpuReport(JNIEnv *env, jclass) {
    std::string r;
    {
        std::lock_guard<std::mutex> lk(g_npu_mu);
        r = g_npu_log;
        if (!g_npu_rejected.empty()) {
            r += "NPU lehnt ab (läuft auf dem Prozessor): ";
            bool first = true;
            for (const auto &o : g_npu_rejected) { if (!first) r += ", "; r += o; first = false; }
            r += "\n";
        } else if (g_hex_dev) {
            r += "NPU lehnt keine Rechenschritte ab\n";
        }
    }
    return env->NewStringUTF(r.c_str());
}

// Ist der NPU-Teil in dieser APK enthalten?
extern "C" JNIEXPORT jboolean JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeNpuBuilt(JNIEnv *env, jclass, jstring jdir) {
    std::string d = jstr(env, jdir);
    return file_exists(d + "/libggml-hexagon.so") && file_exists(d + "/libggml-htp-v79.so") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_err.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeContextSize(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_ctx ? (jint) llama_n_ctx(g_ctx) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeThinkOpen(JNIEnv *, jclass) { return g_think_open ? JNI_TRUE : JNI_FALSE; }

extern "C" JNIEXPORT void JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeStop(JNIEnv *, jclass) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeUnload(JNIEnv *, jclass) {
    g_stop = true;
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
}

// roles/contents: parallele Arrays; cb.onToken(byte[]) wird für jedes Textstück aufgerufen.
// think = false: bei Thinking-Vorlagen wird ein leerer Denkblock vorgegeben (schnelle interne Aufgaben).
// thinkBudget > 0: nach so vielen Tokens im Denkblock wird "</think>" eingefügt, damit die Antwort sicher kommt.
extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jobjectArray roles, jobjectArray contents,
                                                 jint maxTokens, jfloat temp, jint topK, jfloat topP, jboolean think, jint thinkBudget, jobject cb) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_stop = false;
    g_think_open = false;
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"Kein Modell geladen.\"}");
    jclass cbc = env->GetObjectClass(cb);
    jmethodID onTok = env->GetMethodID(cbc, "onToken", "([B)V");
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // 1) Chat-Vorlage des Modells (Jinja aus der GGUF-Datei) anwenden
    std::vector<common_chat_msg> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        jstring jr = (jstring) env->GetObjectArrayElement(roles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(contents, i);
        m.role = jstr(env, jr);
        m.content = jstr(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs.push_back(m);
    }
    std::vector<llama_token> toks;
    std::string rendered;
    bool templ_ok = false;
    if (g_tmpl) {
        try {
            common_chat_templates_inputs in;
            in.messages = msgs;
            in.add_generation_prompt = true;
            in.use_jinja = true;
            in.enable_thinking = think;
            common_chat_params p = common_chat_templates_apply(g_tmpl.get(), in);
            bool first = true;
            for (const auto &part : p.prompt.parts) {
                auto t = common_tokenize(vocab, part.text, first && llama_vocab_get_add_bos(vocab), !part.is_input);
                toks.insert(toks.end(), t.begin(), t.end());
                rendered += part.text;
                first = false;
            }
            templ_ok = true;
        } catch (...) { toks.clear(); rendered.clear(); }
    }
    if (!templ_ok) {
        // Notlösung ohne Vorlage: ChatML (Qwen-Format)
        std::string s;
        for (auto &m : msgs) s += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
        s += "<|im_start|>assistant\n";
        rendered = s;
        toks = common_tokenize(vocab, s, true, true);
    }
    if (ends_with_think(rendered)) {
        if (think) g_think_open = true;
        else {
            // Thinking-Modell, aber jetzt ohne Nachdenken: leeren Denkblock vorgeben
            auto t = common_tokenize(vocab, "\n</think>\n\n", false, true);
            toks.insert(toks.end(), t.begin(), t.end());
        }
    }
    int n_ctx = (int) llama_n_ctx(g_ctx);
    int limit = maxTokens > 0 ? maxTokens : 700;
    // Passt die Antwort nicht mehr ganz in den Kontext: Antwortlänge kürzen, mindestens 256 Tokens
    if ((int) toks.size() + limit > n_ctx - 8) limit = n_ctx - 8 - (int) toks.size();
    if (limit < 256) return env->NewStringUTF("{\"error\":\"Die Unterhaltung ist zu lang für das Modell. Bitte einen neuen Chat beginnen.\"}");

    const int64_t t0 = ggml_time_us();
    // 2) Gemeinsamen Anfang mit dem letzten Mal wiederverwenden
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t keep = 0;
    while (keep < g_cache.size() && keep < toks.size() && g_cache[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--;
    if (keep < g_cache.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
    }
    g_cache.resize(keep);

    // 3) Neue Eingabe verarbeiten
    int nb = (int) llama_n_batch(g_ctx);
    for (size_t i = keep; i < toks.size(); i += nb) {
        if (g_stop) return env->NewStringUTF("{\"stopped\":true}");
        int cnt = (int) std::min((size_t) nb, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
        if (llama_decode(g_ctx, b) != 0) {
            llama_memory_clear(mem, true);
            g_cache.clear();
            return env->NewStringUTF("{\"error\":\"Fehler beim Verarbeiten der Eingabe (zu wenig Speicher?).\"}");
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + cnt);
    }

    const int64_t t1 = ggml_time_us();
    const size_t reused = keep;
    // 4) Antwort erzeugen
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending;
    int generated = 0;
    int64_t t_first = 0;   // Zeitpunkt des ersten Antwort-Tokens
    bool cut = false;
    // Denk-Budget: zählt Tokens, solange der Denkblock offen ist (nur wenn die Ausgabe mit Gedanken beginnt)
    bool think_done = !g_think_open.load();
    bool think_cut = false;
    int think_tokens = 0;
    std::string think_tail;
    for (int i = 0; !g_stop; i++) {
        if (i >= limit) { cut = true; break; }
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (t_first == 0) t_first = ggml_time_us();
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, len);
        size_t ok = utf8_valid_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
            env->CallVoidMethod(cb, onTok, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
        }
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(g_ctx, b) != 0) break;
        generated++;
        g_cache.push_back(t);
        if (!think_done) {
            if (len > 0) think_tail.append(buf, len);
            if (think_tail.find("</think>") != std::string::npos) {
                think_done = true;
            } else {
                if (think_tail.size() > 32) think_tail.erase(0, think_tail.size() - 32);
                think_tokens++;
                if (thinkBudget > 0 && think_tokens >= thinkBudget) {
                    // Zu langes Nachdenken: Denkblock selbst schließen, das Modell schreibt danach die Antwort
                    static const std::string close_txt = "\n</think>\n\n";
                    auto ct = common_tokenize(vocab, close_txt, false, true);
                    if (!ct.empty() && (int) g_cache.size() + (int) ct.size() < n_ctx - 8) {
                        // unvollständige UTF-8-Reste vor dem Einfügen verwerfen
                        pending.clear();
                        jbyteArray arr = env->NewByteArray((jsize) close_txt.size());
                        env->SetByteArrayRegion(arr, 0, (jsize) close_txt.size(), (const jbyte *) close_txt.data());
                        env->CallVoidMethod(cb, onTok, arr);
                        env->DeleteLocalRef(arr);
                        llama_batch cb2 = llama_batch_get_one(ct.data(), (int32_t) ct.size());
                        if (llama_decode(g_ctx, cb2) != 0) break;
                        g_cache.insert(g_cache.end(), ct.begin(), ct.end());
                        think_cut = true;
                    }
                    think_done = true;
                }
            }
        }
        if ((int) g_cache.size() >= n_ctx - 2) { cut = true; break; }
    }
    llama_sampler_free(smpl);
    const int64_t t2 = ggml_time_us();
    char stats[700];
    snprintf(stats, sizeof(stats),
             "{\"prompt\":%d,\"reused\":%d,\"t_prompt\":%lld,\"gen\":%d,\"t_gen\":%lld,\"limit\":%d,\"cut\":%s,\"ctx\":%d,\"tmpl\":%s,\"think_open\":%s,\"think_cut\":%s,\"threads\":%d,\"cpu\":\"%s\",\"t_first\":%lld}",
             (int) toks.size(), (int) reused, (long long) ((t1 - t0) / 1000), generated, (long long) ((t2 - t1) / 1000),
             limit, cut ? "true" : "false", n_ctx, templ_ok ? "true" : "false", g_think_open ? "true" : "false", think_cut ? "true" : "false",
             (int) llama_n_threads(g_ctx), cpu_flags().c_str(), (long long) (t_first > 0 ? (t_first - t0) / 1000 : -1));
    return env->NewStringUTF(stats);
}
