// Blue Benchmark native DGEMM burn engine.
// - Native buffers use guarded allocation and RAII cleanup on all return paths.
// - Atomic cancel flag checked per block; ONLY user cancel stops the run (no thermal abort).
// - Returns measured GFLOPs on success.

#include <jni.h>
#include <cstdlib>
#include <cmath>
#include <ctime>
#include <cstdint>
#include <memory>
#include <new>
#include <exception>
#include <thread>
#include <vector>
#include <atomic>
#include <sched.h>
#include <unistd.h>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define BLUEBENCH_HAS_NEON 1
#else
#define BLUEBENCH_HAS_NEON 0
#endif
#if defined(__arm__) && !defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

static double nowSec() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1e9;
}

static bool runtimeHasNeon() {
#if defined(__aarch64__)
    return true; // Advanced SIMD is part of the AArch64 architecture baseline.
#elif defined(__arm__) && defined(HWCAP_NEON)
    return (getauxval(AT_HWCAP) & HWCAP_NEON) != 0;
#else
    return false;
#endif
}

// Android may assign a process to a restricted CPU set based on device policy
// and process state. Use the CPUs actually allowed here instead of assuming
// every online CPU is schedulable by this app.
static std::vector<int> allowedCpuIds() {
    std::vector<int> ids;
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0) {
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (CPU_ISSET(cpu, &allowed)) ids.push_back(cpu);
        }
    }
    if (!ids.empty()) return ids;

    // Keep other ABIs/devices usable if affinity queries are unavailable.
    long online = sysconf(_SC_NPROCESSORS_ONLN);
    if (online > CPU_SETSIZE) online = CPU_SETSIZE;
    for (int cpu = 0; cpu < online; ++cpu) ids.push_back(cpu);
    return ids;
}

static void pinCurrentThread(int cpu) {
    if (cpu < 0 || cpu >= CPU_SETSIZE) return;
    cpu_set_t one;
    CPU_ZERO(&one);
    CPU_SET(cpu, &one);
    // Best effort: affinity can fail if Android changes the process cpuset
    // during startup. The OS scheduler still runs the worker in that case.
    (void) sched_setaffinity(0, sizeof(one), &one);
}

static constexpr uint64_t LCG_MULT = 6364136223846793005ULL;
static constexpr uint64_t LCG_ADD = 1442695040888963407ULL;
static constexpr int MATRIX_COUNT = 3;

// Advance the deterministic initializer by delta steps so independent workers
// can fill disjoint ranges without changing the generated matrix values.
static uint64_t advanceLcg(uint64_t state, uint64_t delta) {
    uint64_t accMult = 1;
    uint64_t accPlus = 0;
    uint64_t curMult = LCG_MULT;
    uint64_t curPlus = LCG_ADD;
    while (delta > 0) {
        if (delta & 1U) {
            accMult *= curMult;
            accPlus = accPlus * curMult + curPlus;
        }
        curPlus = (curMult + 1U) * curPlus;
        curMult *= curMult;
        delta >>= 1U;
    }
    return accMult * state + accPlus;
}

struct InitWork {
    double* A;
    double* B;
    size_t start;
    size_t end;
    int cpuId;
    std::atomic<bool>* cancel;
};

static void initFn(InitWork* w) {
    pinCurrentThread(w->cpuId);
    uint64_t state = advanceLcg(0x12345678ULL, (uint64_t) w->start * 2ULL);
    for (size_t i = w->start; i < w->end; ++i) {
        if ((i & 0x3FFFU) == 0 && w->cancel &&
            w->cancel->load(std::memory_order_relaxed)) return;
        state = state * LCG_MULT + LCG_ADD;
        w->A[i] = ((state >> 33) & 0xFFFF) / 65535.0;
        state = state * LCG_MULT + LCG_ADD;
        w->B[i] = ((state >> 33) & 0xFFFF) / 65535.0;
    }
}

// Vectorization-friendly inner product. Compiler auto-vectorizes with -O3.
static inline double dotRow(const double* __restrict__ row,
                            const double* __restrict__ bCol,
                            int n, int ldb) {
    double s0 = 0.0, s1 = 0.0, s2 = 0.0, s3 = 0.0;
    int k = 0;
    int lim = (n / 4) * 4;
    for (; k < lim; k += 4) {
        s0 += row[k] * bCol[(k) * ldb];
        s1 += row[k + 1] * bCol[(k + 1) * ldb];
        s2 += row[k + 2] * bCol[(k + 2) * ldb];
        s3 += row[k + 3] * bCol[(k + 3) * ldb];
    }
    double s = s0 + s1 + s2 + s3;
    for (; k < n; ++k) s += row[k] * bCol[k * ldb];
    return s;
}

struct Work {
    const double* A;
    const double* B;
    double* C;
    int n;
    int rowStart;
    int rowEnd;
    int cpuId;
    std::atomic<bool>* cancel;
    uint64_t outputsDone;
    volatile double sink;
};

static void workerFn(Work* w) {
    pinCurrentThread(w->cpuId);
    const int n = w->n;
    for (int i = w->rowStart; i < w->rowEnd; ++i) {
        if (w->cancel && w->cancel->load(std::memory_order_relaxed)) return;
        const double* row = w->A + (size_t) i * n;
        // Block over columns for cache reuse; check cancel per block.
        for (int j0 = 0; j0 < n; j0 += 256) {
            if (w->cancel && w->cancel->load(std::memory_order_relaxed)) return;
            int j1 = j0 + 256;
            if (j1 > n) j1 = n;
            for (int j = j0; j < j1; ++j) {
                w->C[(size_t) i * n + j] = dotRow(row, w->B + j, n, n);
            }
            w->outputsDone += (uint64_t) (j1 - j0);
        }
        w->sink += w->C[(size_t) i * n + (i % n)];
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeCreateFlag(JNIEnv*, jobject) {
    auto* flag = new (std::nothrow) std::atomic<bool>(false);
    return reinterpret_cast<jlong>(flag);
}

JNIEXPORT jintArray JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeAllowedCpuIds(JNIEnv* env, jobject) {
    try {
        const std::vector<int> ids = allowedCpuIds();
        jintArray result = env->NewIntArray((jsize) ids.size());
        if (!result || ids.empty()) return result;

        std::vector<jint> values;
        values.reserve(ids.size());
        for (int cpu : ids) values.push_back((jint) cpu);
        env->SetIntArrayRegion(result, 0, (jsize) values.size(), values.data());
        return result;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeSetCancelled(JNIEnv*, jobject, jlong ptr) {
    if (!ptr) return;
    auto* flag = reinterpret_cast<std::atomic<bool>*>(ptr);
    flag->store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeFreeFlag(JNIEnv*, jobject, jlong ptr) {
    if (!ptr) return;
    auto* flag = reinterpret_cast<std::atomic<bool>*>(ptr);
    delete flag;
}

JNIEXPORT jboolean JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativePinCurrentThread(JNIEnv*, jobject, jint cpuId) {
    if (cpuId < 0 || cpuId >= CPU_SETSIZE) return JNI_FALSE;
    cpu_set_t one;
    CPU_ZERO(&one);
    CPU_SET(cpuId, &one);
    return sched_setaffinity(0, sizeof(one), &one) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeImageBenchmark(
        JNIEnv* env, jobject, jint pixelCount, jint passes, jboolean requestNeon) {
    if (pixelCount <= 0 || passes <= 0) return nullptr;
    try {
        const size_t count = static_cast<size_t>(pixelCount);
        std::vector<uint8_t> input(count * 4);
        std::vector<uint8_t> output(count * 4);
        uint32_t state = 0x91e10da5U;
        for (size_t i = 0; i < count; ++i) {
            state = state * 1664525U + 1013904223U;
            input[i * 4] = static_cast<uint8_t>(state >> 24);
            input[i * 4 + 1] = static_cast<uint8_t>(state >> 16);
            input[i * 4 + 2] = static_cast<uint8_t>(state >> 8);
            input[i * 4 + 3] = 255;
        }

        const bool useNeon = requestNeon == JNI_TRUE && BLUEBENCH_HAS_NEON && runtimeHasNeon();
        const double t0 = nowSec();
        for (int pass = 0; pass < passes; ++pass) {
            size_t i = 0;
            volatile uint8_t* scalarOutput = output.data();
#if BLUEBENCH_HAS_NEON
            if (useNeon) {
                for (; i + 8 <= count; i += 8) {
                    const uint8x8x4_t rgba = vld4_u8(input.data() + i * 4);
                    uint16x8_t gray = vmull_u8(rgba.val[0], vdup_n_u8(77));
                    gray = vmlal_u8(gray, rgba.val[1], vdup_n_u8(150));
                    gray = vmlal_u8(gray, rgba.val[2], vdup_n_u8(29));
                    gray = vaddq_u16(gray, vdupq_n_u16(128));
                    const uint8x8_t y = vmovn_u16(vshrq_n_u16(gray, 8));
                    uint8x8x4_t packed{};
                    packed.val[0] = y;
                    packed.val[1] = y;
                    packed.val[2] = y;
                    packed.val[3] = rgba.val[3];
                    vst4_u8(output.data() + i * 4, packed);
                }
            }
#endif
            for (; i < count; ++i) {
                const uint32_t r = input[i * 4];
                const uint32_t g = input[i * 4 + 1];
                const uint32_t b = input[i * 4 + 2];
                const uint8_t y = static_cast<uint8_t>((77U * r + 150U * g + 29U * b + 128U) >> 8);
                scalarOutput[i * 4] = y;
                scalarOutput[i * 4 + 1] = y;
                scalarOutput[i * 4 + 2] = y;
                scalarOutput[i * 4 + 3] = input[i * 4 + 3];
            }
        }
        const double elapsed = nowSec() - t0;
        uint64_t checksum = 0;
        for (size_t i = 0; i < count * 4; i += 4096) checksum += output[i];
        jdouble values[3] = {elapsed, static_cast<double>(checksum), useNeon ? 1.0 : 0.0};
        jdoubleArray result = env->NewDoubleArray(3);
        if (result) env->SetDoubleArrayRegion(result, 0, 3, values);
        return result;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jdouble JNICALL
Java_com_bluebenchmark_cpu_engine_BenchmarkEngine_nativeRun(
        JNIEnv*, jobject, jlong ramBytes, jint threads, jlong cancelPtr) {
    if (ramBytes <= 0 || threads <= 0 || threads > 256) return -3.0; // invalid args
    long long nll = (long long) sqrt((double) ramBytes / (8.0 * MATRIX_COUNT));
    if (nll < 64 || nll > 20000) return -3.0;
    try {
        const int n = (int) nll;
        const size_t elems = (size_t) n * (size_t) n;
        const size_t bytes = elems * sizeof(double);
        const auto freeBuffer = [](double* ptr) { std::free(ptr); };
        std::unique_ptr<double, decltype(freeBuffer)> A(
                static_cast<double*>(std::malloc(bytes)), freeBuffer);
        std::unique_ptr<double, decltype(freeBuffer)> B(
                static_cast<double*>(std::malloc(bytes)), freeBuffer);
        std::unique_ptr<double, decltype(freeBuffer)> C(
                static_cast<double*>(std::malloc(bytes)), freeBuffer);
        if (!A || !B || !C) return -1.0;

        std::atomic<bool>* cancel = cancelPtr
                                   ? reinterpret_cast<std::atomic<bool>*>(cancelPtr)
                                   : nullptr;
        if (cancel && cancel->load(std::memory_order_relaxed)) return -2.0;

        const std::vector<int> cpuIds = allowedCpuIds();
        int t = threads > n ? n : threads;
        if (!cpuIds.empty() && t > (int) cpuIds.size()) t = (int) cpuIds.size();
        if (t < 1) t = 1;

        // Initialize both full-size inputs in parallel. The old serial pass could
        // spend much of the 60-second run on one core before the burn workers ran.
        std::vector<std::thread> initPool;
        std::vector<InitWork> initWorks((size_t) t);
        try {
            initPool.reserve((size_t) t);
            for (int i = 0; i < t; ++i) {
                size_t start = (elems * (size_t) i) / (size_t) t;
                size_t end = (elems * (size_t) (i + 1)) / (size_t) t;
                int cpuId = cpuIds.empty() ? -1 : cpuIds[(size_t) i];
                initWorks[i] = InitWork{A.get(), B.get(), start, end, cpuId, cancel};
                initPool.emplace_back(initFn, &initWorks[i]);
            }
        } catch (...) {
            if (cancel) cancel->store(true, std::memory_order_relaxed);
            for (auto& th : initPool) if (th.joinable()) th.join();
            return -4.0;
        }
        for (auto& th : initPool) th.join();
        if (cancel && cancel->load(std::memory_order_relaxed)) return -2.0;

        const double t0 = nowSec();
        std::vector<std::thread> pool;
        std::vector<Work> works((size_t) t);
        int rowsPer = n / t;
        int rem = n % t;
        int cur = 0;
        for (int i = 0; i < t; ++i) {
            int rows = rowsPer + (i < rem ? 1 : 0);
            int cpuId = cpuIds.empty() ? -1 : cpuIds[(size_t) i];
            works[i] = Work{A.get(), B.get(), C.get(), n, cur, cur + rows,
                            cpuId, cancel, 0, 0.0};
            cur += rows;
        }
        try {
            pool.reserve((size_t) t);
            // A dedicated native worker keeps affinity changes off the coroutine
            // pool thread, including in single-core mode.
            for (int i = 0; i < t; ++i) {
                pool.emplace_back(workerFn, &works[i]);
            }
        } catch (...) {
            if (cancel) cancel->store(true, std::memory_order_relaxed);
            for (auto& th : pool) if (th.joinable()) th.join();
            return -4.0;
        }
        for (auto& th : pool) th.join();

        const double t1 = nowSec();
        const bool wasCancelled = cancel && cancel->load(std::memory_order_relaxed);
        // Prevent dead-code elimination and sum per-worker progress without
        // putting an atomic operation in the measured inner loop.
        volatile double sink = 0;
        uint64_t outputsDone = 0;
        for (int i = 0; i < t; ++i) {
            sink += works[i].sink;
            outputsDone += works[i].outputsDone;
        }
        (void) sink;

        const double elapsed = t1 - t0;
        if (wasCancelled) {
            if (outputsDone == 0 || elapsed <= 0.0) return -2.0;
            return (2.0 * (double) outputsDone * (double) n) / (elapsed * 1e9);
        }
        if (elapsed <= 0.0) return -3.0;
        return (2.0 * (double) n * (double) n * (double) n) / (elapsed * 1e9);
    } catch (const std::bad_alloc&) {
        return -4.0;
    } catch (const std::exception&) {
        return -4.0;
    } catch (...) {
        return -4.0;
    }
}

} // extern "C"
