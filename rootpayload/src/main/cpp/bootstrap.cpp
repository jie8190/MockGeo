#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <pthread.h>
#include <atomic>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <dobby.h>
#include <xdl.h>
#include <lsplant.hpp>

extern "C" void* mockgeo_xdl_prefix(void*, const char*);
namespace {
std::atomic<bool> started{false}, ready{false};
std::mutex initMutex;
void* art = nullptr;
struct Request { std::string payload, nonce, mode; };
void failure(const Request& request, const char* stage) {
    const std::string path = (request.mode == "system" ? std::string("/data/misc/mockgeo/root/state") :
        request.payload.substr(0, request.payload.find_last_of('/'))) + "/receipt.json";
    if (FILE* out = fopen(path.c_str(), "we")) {
        fprintf(out, "{\"ready\":false,\"pid\":%d,\"nonce\":\"%s\",\"stage\":\"%s\"}\n",
                getpid(), request.nonce.c_str(), stage);
        fclose(out);
    }
    __android_log_print(ANDROID_LOG_ERROR, "MockGeoRoot", "Bootstrap failed: %s", stage);
}
jboolean initialize(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(initMutex);
    if (ready.load()) return JNI_TRUE;
    if (!art) art = xdl_open("libart.so", XDL_DEFAULT);
    if (!art) return JNI_FALSE;
    lsplant::InitInfo info{
        .inline_hooker = [](void* target, void* replacement) -> void* {
            dobby_dummy_func_t backup = nullptr;
            return DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement), &backup) == 0
                ? reinterpret_cast<void*>(backup) : nullptr;
        },
        .inline_unhooker = [](void* target) { return DobbyDestroy(target) == 0; },
        .art_symbol_resolver = [](std::string_view name) -> void* {
            const std::string symbol(name);
            if (void* result = xdl_sym(art, symbol.c_str(), nullptr)) return result;
            return xdl_dsym(art, symbol.c_str(), nullptr);
        },
        .art_symbol_prefix_resolver = [](std::string_view name) -> void* {
            return mockgeo_xdl_prefix(art, std::string(name).c_str());
        },
        .generated_class_name = "MockGeoRootHook_",
        .generated_source_name = "MockGeoRoot",
        .generated_field_name = "hooker",
        .generated_method_name = "{target}",
    };
    ready.store(lsplant::Init(env, info));
    return ready.load() ? JNI_TRUE : JNI_FALSE;
}
jobject hook(JNIEnv* env, jclass, jobject target, jobject record, jobject callback) {
    return ready.load() ? lsplant::Hook(env, target, record, callback) : nullptr;
}
jboolean deoptimize(JNIEnv* env, jclass, jobject method) {
    return ready.load() && lsplant::Deoptimize(env, method);
}
bool unlock(JNIEnv* env) {
    auto runtime = env->FindClass("dalvik/system/VMRuntime");
    if (!runtime) return false;
    auto get = env->GetStaticMethodID(runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
    auto set = env->GetMethodID(runtime, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (!get || !set) return false;
    auto object = env->CallStaticObjectMethod(runtime, get);
    auto strings = env->NewObjectArray(1, env->FindClass("java/lang/String"), env->NewStringUTF("L"));
    env->CallVoidMethod(object, set, strings);
    return !env->ExceptionCheck();
}
void* bootstrap(void* pointer) {
    std::unique_ptr<Request> request(static_cast<Request*>(pointer));
    art = xdl_open("libart.so", XDL_DEFAULT);
    using GetVMs = jint (*)(JavaVM**, jsize, jsize*);
    auto getVMs = art ? reinterpret_cast<GetVMs>(xdl_sym(art, "JNI_GetCreatedJavaVMs", nullptr)) : nullptr;
    JavaVM* vm = nullptr; jsize count = 0;
    if (!getVMs || getVMs(&vm, 1, &count) != JNI_OK || !vm || count != 1) { failure(*request, "java-vm"); return nullptr; }
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) { failure(*request, "attach-worker"); return nullptr; }
    const char* stage = "jni-frame";
    do {
        if (env->PushLocalFrame(64) < 0) break;
        stage = "hidden-api"; if (!unlock(env)) break;
        auto classLoader = env->FindClass("java/lang/ClassLoader");
        auto getSystem = env->GetStaticMethodID(classLoader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        auto load = env->GetMethodID(classLoader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        auto parent = env->CallStaticObjectMethod(classLoader, getSystem);
        auto pathClass = env->FindClass("dalvik/system/PathClassLoader");
        auto ctor = env->GetMethodID(pathClass, "<init>", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
        stage = "payload-loader";
        auto loader = env->NewObject(pathClass, ctor, env->NewStringUTF(request->payload.c_str()), parent);
        if (!loader || env->ExceptionCheck()) break;
        stage = "native-registration";
        auto hooks = static_cast<jclass>(env->CallObjectMethod(loader, load, env->NewStringUTF("com.mockgeo.root.NativeHooks")));
        if (!hooks || env->ExceptionCheck()) break;
        const JNINativeMethod methods[] = {
            {"initialize", "()Z", reinterpret_cast<void*>(initialize)},
            {"hook", "(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", reinterpret_cast<void*>(hook)},
            {"deoptimize", "(Ljava/lang/reflect/Executable;)Z", reinterpret_cast<void*>(deoptimize)},
        };
        if (env->RegisterNatives(hooks, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) break;
        stage = "payload-entry";
        auto entry = static_cast<jclass>(env->CallObjectMethod(loader, load, env->NewStringUTF("com.mockgeo.root.PayloadBootstrap")));
        if (!entry || env->ExceptionCheck()) break;
        auto start = env->GetStaticMethodID(entry, "start", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");
        if (!start) break;
        const auto directory = request->mode == "system" ? std::string("/data/misc/mockgeo/root/state") :
            request->payload.substr(0, request->payload.find_last_of('/'));
        env->CallStaticIntMethod(entry, start, env->NewStringUTF(request->mode.c_str()),
            env->NewStringUTF(request->nonce.c_str()), env->NewStringUTF(directory.c_str()));
        if (env->ExceptionCheck()) break;
        env->PopLocalFrame(nullptr);
        vm->DetachCurrentThread();
        return nullptr;
    } while (false);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    failure(*request, stage);
    vm->DetachCurrentThread();
    return nullptr;
}
}

extern "C" __attribute__((visibility("default"))) int mockgeo_root_start(const char* payload, const char* nonce, const char* mode) {
    if (!payload || !nonce || !mode) return -1;
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) return -2;
    auto request = std::make_unique<Request>(Request{payload, nonce, mode});
    pthread_t worker;
    const int error = pthread_create(&worker, nullptr, bootstrap, request.get());
    if (error != 0) { started.store(false); return -error; }
    request.release(); pthread_detach(worker); return 0;
}
