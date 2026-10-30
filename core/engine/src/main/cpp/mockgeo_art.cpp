#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "MockGeoArt"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ART access_flags constants across Android 8.0 (API 26) .. Android 16 (API 36)
constexpr uint32_t kAccPublic       = 0x0001;
constexpr uint32_t kAccPrivate      = 0x0002;
constexpr uint32_t kAccProtected    = 0x0004;
constexpr uint32_t kAccStatic       = 0x0008;
constexpr uint32_t kAccNative       = 0x0100;
constexpr uint32_t kAccNterpFast    = 0x00100000;
constexpr uint32_t kAccPreCompiled  = 0x00200000;
constexpr uint32_t kAccSingleImpl   = 0x08000000;
constexpr uint32_t kAccDontBother26 = 0x01000000;
constexpr uint32_t kAccDontBother27 = 0x02000000;

pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
bool g_initialized = false;
bool g_hiddenApiUnlocked = false;
int g_sdkInt = 0;
jfieldID g_artMethodField = nullptr;
size_t g_artMethodSize = 0;
size_t g_offsetAccessFlags = 4;
size_t g_offsetData = 0;
size_t g_offsetQuickCode = 0;

#if defined(__aarch64__)
uint8_t* g_trampolinePage = nullptr;
size_t g_trampolineOffset = 0;
size_t g_pageSize = 4096;
#endif

jlong JNICALL nativeProbeA(JNIEnv*, jclass, jlong a, jlong b) {
    return a + b + 1;
}

jlong JNICALL nativeProbeB(JNIEnv*, jclass, jlong a, jlong b) {
    return a ^ b;
}

uintptr_t getArtMethodPtr(JNIEnv* env, jobject executable) {
    if (executable == nullptr) {
        return 0;
    }
    if (g_artMethodField != nullptr) {
        jlong ptr = env->GetLongField(executable, g_artMethodField);
        if (!env->ExceptionCheck() && ptr != 0) {
            return static_cast<uintptr_t>(ptr);
        }
        env->ExceptionClear();
    }
    jmethodID mid = env->FromReflectedMethod(executable);
    return reinterpret_cast<uintptr_t>(mid);
}

void prepareMethodFlags(uintptr_t artMethod, bool isBackupOrCopy) {
    if (artMethod == 0) return;
    auto* flagsPtr = reinterpret_cast<uint32_t*>(artMethod + g_offsetAccessFlags);
    uint32_t flags = *flagsPtr;

    uint32_t dontBother = (g_sdkInt >= 27) ? kAccDontBother27 : kAccDontBother26;
    flags |= dontBother;
    flags &= ~kAccSingleImpl;

    if (g_sdkInt >= 29) {
        flags &= ~kAccPreCompiled;
    }
    if (g_sdkInt >= 31 && (flags & kAccNative) == 0) {
        flags &= ~kAccNterpFast;
    }
    if (isBackupOrCopy) {
        // Clear potential fast-interpreter / intrinsic / visibility bits on backup/copy
        flags &= 0xBFFFFFFFU;
        if ((flags & kAccStatic) == 0 && g_sdkInt >= 30) {
            // Turning non-static copy method private prevents vtable re-dispatch loops
            flags = (flags & ~(kAccPublic | kAccProtected)) | kAccPrivate;
        }
    }
    *flagsPtr = flags;
}

#if defined(__aarch64__)
void* allocMethodTrampoline(uintptr_t targetArtMethod) {
    constexpr size_t kSlotSize = 32;
    if (g_trampolinePage == nullptr || (g_trampolineOffset + kSlotSize) > g_pageSize) {
        long sysPage = sysconf(_SC_PAGESIZE);
        g_pageSize = (sysPage > 0) ? static_cast<size_t>(sysPage) : 4096;
        void* page = mmap(nullptr, g_pageSize, PROT_READ | PROT_WRITE | PROT_EXEC,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (page == MAP_FAILED) {
            page = mmap(nullptr, g_pageSize, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (page == MAP_FAILED) {
                LOGE("mmap trampoline failed");
                return nullptr;
            }
        }
        g_trampolinePage = static_cast<uint8_t*>(page);
        g_trampolineOffset = 0;
    }

    uint8_t* slot = g_trampolinePage + g_trampolineOffset;
    mprotect(g_trampolinePage, g_pageSize, PROT_READ | PROT_WRITE | PROT_EXEC);

    // 32-byte ARM64 stub:
    // +0:  d503245f  bti c
    // +4:  58000060  ldr x0, [pc, #12]               -> loads targetArtMethod from +16
    // +8:  f8400010  ldur x16, [x0, #offsetQuickCode] -> loads targetArtMethod->entry_point_from_quick_compiled_code_
    // +12: d61f0200  br x16
    // +16: uint64_t  targetArtMethod
    // +24: uint64_t  0
    uint32_t insn0 = 0xD503245FU; // bti c
    uint32_t insn1 = 0x58000060U; // ldr x0, #12 (from +4 + 12 = +16)
    uint32_t insn2 = 0xF8400010U | ((static_cast<uint32_t>(g_offsetQuickCode) & 0x1FFU) << 12);
    uint32_t insn3 = 0xD61F0200U; // br x16

    memcpy(slot + 0, &insn0, 4);
    memcpy(slot + 4, &insn1, 4);
    memcpy(slot + 8, &insn2, 4);
    memcpy(slot + 12, &insn3, 4);
    uint64_t ptrVal = static_cast<uint64_t>(targetArtMethod);
    uint64_t zero = 0;
    memcpy(slot + 16, &ptrVal, 8);
    memcpy(slot + 24, &zero, 8);

    __builtin___clear_cache(reinterpret_cast<char*>(slot), reinterpret_cast<char*>(slot + kSlotSize));
    g_trampolineOffset += kSlotSize;
    return slot;
}
#endif

void* unlockHiddenApiWorker(void* arg) {
    auto* vm = static_cast<JavaVM*>(arg);
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        return nullptr;
    }
    jclass execClass = env->FindClass("java/lang/reflect/Executable");
    if (execClass != nullptr) {
        jfieldID fid = env->GetFieldID(execClass, "artMethod", "J");
        if (!env->ExceptionCheck() && fid != nullptr) {
            g_artMethodField = fid;
        }
        env->ExceptionClear();
        env->DeleteLocalRef(execClass);
    }

    jclass vmRuntimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (vmRuntimeClass != nullptr) {
        jmethodID getRuntime = env->GetStaticMethodID(
            vmRuntimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
        jmethodID setExemptions = env->GetMethodID(
            vmRuntimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
        if (!env->ExceptionCheck() && getRuntime != nullptr && setExemptions != nullptr) {
            jobject runtime = env->CallStaticObjectMethod(vmRuntimeClass, getRuntime);
            jclass strClass = env->FindClass("java/lang/String");
            jobjectArray arr = env->NewObjectArray(1, strClass, env->NewStringUTF("L"));
            env->CallVoidMethod(runtime, setExemptions, arr);
            if (!env->ExceptionCheck()) {
                g_hiddenApiUnlocked = true;
            }
        }
        env->ExceptionClear();
        env->DeleteLocalRef(vmRuntimeClass);
    }
    vm->DetachCurrentThread();
    return nullptr;
}

bool calibrateArtLayout(JNIEnv* env, jclass bridgeClass, int sdkInt) {
    g_sdkInt = sdkInt;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
        pthread_t tid;
        if (pthread_create(&tid, nullptr, unlockHiddenApiWorker, vm) == 0) {
            pthread_join(tid, nullptr);
        }
    }
    if (g_artMethodField == nullptr) {
        jclass execClass = env->FindClass("java/lang/reflect/Executable");
        if (execClass != nullptr) {
            g_artMethodField = env->GetFieldID(execClass, "artMethod", "J");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                g_artMethodField = nullptr;
            }
            env->DeleteLocalRef(execClass);
        }
    }

    // Defaults for 64-bit ART by SDK level
    if (sizeof(void*) == 8) {
        if (sdkInt >= 31) {
            g_artMethodSize = 24;
            g_offsetData = 16;
            g_offsetQuickCode = 24;
        } else if (sdkInt >= 28) {
            g_artMethodSize = 32;
            g_offsetData = 24;
            g_offsetQuickCode = 32;
        } else {
            g_artMethodSize = 40;
            g_offsetData = 32;
            g_offsetQuickCode = 40;
        }
    } else {
        g_artMethodSize = 24;
        g_offsetData = 16;
        g_offsetQuickCode = 20;
    }

    // Register probe natives and dynamically verify ArtMethod size + JNI/Quick entry offsets
    JNINativeMethod probes[] = {
        {"probeMethodA", "(JJ)J", reinterpret_cast<void*>(nativeProbeA)},
        {"probeMethodB", "(JJ)J", reinterpret_cast<void*>(nativeProbeB)},
    };
    if (env->RegisterNatives(bridgeClass, probes, 2) < 0) {
        env->ExceptionClear();
        LOGW("RegisterNatives on probe methods failed; using SDK defaults");
        return true;
    }

    jclass clsClass = env->FindClass("java/lang/Class");
    jclass longTypeClass = nullptr;
    jclass longBoxClass = env->FindClass("java/lang/Long");
    if (longBoxClass != nullptr) {
        jfieldID typeFid = env->GetStaticFieldID(longBoxClass, "TYPE", "Ljava/lang/Class;");
        if (!env->ExceptionCheck() && typeFid != nullptr) {
            longTypeClass = static_cast<jclass>(env->GetStaticObjectField(longBoxClass, typeFid));
        }
        env->ExceptionClear();
    }

    if (clsClass != nullptr && longTypeClass != nullptr) {
        jmethodID getDeclaredMethod = env->GetMethodID(
            clsClass, "getDeclaredMethod",
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        jobjectArray paramTypes = env->NewObjectArray(2, clsClass, longTypeClass);
        jstring nameA = env->NewStringUTF("probeMethodA");
        jstring nameB = env->NewStringUTF("probeMethodB");

        jobject methodA = env->CallObjectMethod(bridgeClass, getDeclaredMethod, nameA, paramTypes);
        jobject methodB = env->CallObjectMethod(bridgeClass, getDeclaredMethod, nameB, paramTypes);
        if (!env->ExceptionCheck() && methodA != nullptr && methodB != nullptr) {
            uintptr_t artA = getArtMethodPtr(env, methodA);
            uintptr_t artB = getArtMethodPtr(env, methodB);
            if (artA != 0 && artB != 0) {
                size_t diff = (artB > artA) ? (artB - artA) : (artA - artB);
                if (diff >= 16 && diff <= 64 && (diff % sizeof(void*)) == 0) {
                    g_artMethodSize = diff;
                }
                uintptr_t targetJniPtr = reinterpret_cast<uintptr_t>(nativeProbeA);
                for (size_t off = 8; off + sizeof(void*) <= g_artMethodSize; off += sizeof(void*)) {
                    uintptr_t candidate = *reinterpret_cast<uintptr_t*>(artA + off);
                    if (candidate == targetJniPtr) {
                        g_offsetData = off;
                        g_offsetQuickCode = off + sizeof(void*);
                        break;
                    }
                }
            }
        }
        env->ExceptionClear();
    }

    LOGD("Calibrated ART layout: sdk=%d size=%zu offsetData=%zu offsetQuick=%zu",
         g_sdkInt, g_artMethodSize, g_offsetData, g_offsetQuickCode);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_initNative(JNIEnv* env, jclass clazz, jint sdkInt) {
    pthread_mutex_lock(&g_mutex);
    if (!g_initialized) {
        g_initialized = calibrateArtLayout(env, clazz, static_cast<int>(sdkInt));
    }
    pthread_mutex_unlock(&g_mutex);
    return g_initialized ? 0 : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_probeMethodA(JNIEnv* env, jclass clazz, jlong a, jlong b) {
    return nativeProbeA(env, clazz, a, b);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_probeMethodB(JNIEnv* env, jclass clazz, jlong a, jlong b) {
    return nativeProbeB(env, clazz, a, b);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_deoptimizeMethodNative(
    JNIEnv* env, jclass, jobject targetMethod) {
    pthread_mutex_lock(&g_mutex);
    uintptr_t artTarget = getArtMethodPtr(env, targetMethod);
    if (artTarget == 0 || !g_initialized) {
        pthread_mutex_unlock(&g_mutex);
        return JNI_FALSE;
    }
    prepareMethodFlags(artTarget, false);
    pthread_mutex_unlock(&g_mutex);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_hookMethodNative(
    JNIEnv* env, jclass, jobject targetMethod, jobject hookMethod, jobject backupMethod, jobject copyMethod) {
#if !defined(__aarch64__)
    (void)env; (void)targetMethod; (void)hookMethod; (void)backupMethod; (void)copyMethod;
    return JNI_FALSE;
#else
    pthread_mutex_lock(&g_mutex);
    if (!g_initialized) {
        pthread_mutex_unlock(&g_mutex);
        return JNI_FALSE;
    }

    uintptr_t artTarget = getArtMethodPtr(env, targetMethod);
    uintptr_t artHook   = getArtMethodPtr(env, hookMethod);
    uintptr_t artBackup = getArtMethodPtr(env, backupMethod);
    uintptr_t artCopy   = getArtMethodPtr(env, copyMethod);

    if (artTarget == 0 || artHook == 0 || artBackup == 0 || artCopy == 0) {
        pthread_mutex_unlock(&g_mutex);
        return JNI_FALSE;
    }

    prepareMethodFlags(artTarget, false);
    prepareMethodFlags(artHook, false);
    prepareMethodFlags(artBackup, false);

    // 1. Clone target ArtMethod into copy ArtMethod and set its backup flags
    memcpy(reinterpret_cast<void*>(artCopy), reinterpret_cast<const void*>(artTarget), g_artMethodSize);
    prepareMethodFlags(artCopy, true);

    // 2. Point backup ArtMethod's entry point to trampoline -> artCopy
    void* backupTrampoline = allocMethodTrampoline(artCopy);
    if (backupTrampoline == nullptr) {
        pthread_mutex_unlock(&g_mutex);
        return JNI_FALSE;
    }
    *reinterpret_cast<void**>(artBackup + g_offsetQuickCode) = backupTrampoline;
    if (g_offsetData > 0) {
        *reinterpret_cast<uintptr_t*>(artBackup + g_offsetData) =
            *reinterpret_cast<uintptr_t*>(artCopy + g_offsetData);
    }
    prepareMethodFlags(artBackup, true);

    // 3. Point target ArtMethod's entry point to trampoline -> artHook
    void* hookTrampoline = allocMethodTrampoline(artHook);
    if (hookTrampoline == nullptr) {
        pthread_mutex_unlock(&g_mutex);
        return JNI_FALSE;
    }
    *reinterpret_cast<void**>(artTarget + g_offsetQuickCode) = hookTrampoline;
    if (g_offsetData > 0) {
        *reinterpret_cast<uintptr_t*>(artTarget + g_offsetData) =
            *reinterpret_cast<uintptr_t*>(artHook + g_offsetData);
    }

    pthread_mutex_unlock(&g_mutex);
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mockgeo_core_engine_NativeArtBridge_getArtDiagnosticsNative(JNIEnv* env, jclass) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "initialized=%s hiddenApiUnlocked=%s sdk=%d artMethodSize=%zu offsetData=%zu offsetQuick=%zu",
             g_initialized ? "true" : "false",
             g_hiddenApiUnlocked ? "true" : "false",
             g_sdkInt,
             g_artMethodSize,
             g_offsetData,
             g_offsetQuickCode);
    return env->NewStringUTF(buf);
}

// Exported bootstrap symbol for system_server / native host loading
extern "C" __attribute__((visibility("default"))) int mockgeo_bootstrap(const char* payloadDexPath) {
    if (payloadDexPath == nullptr || payloadDexPath[0] == '\0') {
        return -1;
    }
    using GetCreatedJavaVMsFn = jint (*)(JavaVM**, jsize, jsize*);
    void* artHandle = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (artHandle == nullptr) {
        artHandle = dlopen("libnativehelper.so", RTLD_NOW);
    }
    auto getVMs = reinterpret_cast<GetCreatedJavaVMsFn>(
        artHandle ? dlsym(artHandle, "JNI_GetCreatedJavaVMs") : dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    if (getVMs == nullptr) {
        LOGE("JNI_GetCreatedJavaVMs not found");
        return -2;
    }

    JavaVM* vm = nullptr;
    jsize count = 0;
    if (getVMs(&vm, 1, &count) != JNI_OK || vm == nullptr || count <= 0) {
        LOGE("No JavaVM found in process");
        return -3;
    }

    JNIEnv* env = nullptr;
    jint envStat = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK || env == nullptr) {
            LOGE("AttachCurrentThreadAsDaemon failed");
            return -4;
        }
    } else if (envStat != JNI_OK || env == nullptr) {
        return -5;
    }

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCl = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCl = env->CallStaticObjectMethod(clClass, getSysCl);

    jclass dexClClass = env->FindClass("dalvik/system/DexClassLoader");
    jmethodID dexClCtor = env->GetMethodID(
        dexClClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jstring jDexPath = env->NewStringUTF(payloadDexPath);
    jstring jOptDir  = env->NewStringUTF("/data/misc/mockgeo/oat");
    jobject dexLoader = env->NewObject(dexClClass, dexClCtor, jDexPath, jOptDir, nullptr, sysCl);
    if (env->ExceptionCheck() || dexLoader == nullptr) {
        env->ExceptionClear();
        LOGE("Failed to construct DexClassLoader for %s", payloadDexPath);
        return -6;
    }

    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jBootstrapName = env->NewStringUTF("com.mockgeo.core.engine.RootSystemBootstrap");
    auto bootstrapCls = static_cast<jclass>(env->CallObjectMethod(dexLoader, loadClass, jBootstrapName));
    if (env->ExceptionCheck() || bootstrapCls == nullptr) {
        env->ExceptionClear();
        LOGE("Failed to load RootSystemBootstrap");
        return -7;
    }

    jmethodID entryMid = env->GetStaticMethodID(bootstrapCls, "bootstrapFromNative", "(Ljava/lang/String;)I");
    if (env->ExceptionCheck() || entryMid == nullptr) {
        env->ExceptionClear();
        return -8;
    }
    jint hooks = env->CallStaticIntMethod(bootstrapCls, entryMid, jDexPath);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -9;
    }
    LOGD("mockgeo_bootstrap completed: hooks=%d", hooks);
    return static_cast<int>(hooks);
}
