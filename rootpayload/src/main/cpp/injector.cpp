// MockGeo root loader. Only the system service and our disposable probe are targets.
// No policy changes, reboot, automatic retry, remote download, or persistence.
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <asm/ptrace.h>
#include <elf.h>
#include <dlfcn.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <chrono>
#include <thread>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <stdexcept>
#include <array>

namespace {
using Clock = std::chrono::steady_clock;
std::runtime_error systemError(const char* operation) {
    const int code = errno;
    return std::runtime_error(std::string(operation) + " failed; errno=" +
        std::to_string(code) + " (" + strerror(code) + ")");
}
std::string read(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("cannot read " + path);
    return {std::istreambuf_iterator<char>(file), {}};
}
struct Map { uintptr_t start, end, offset; std::string path; };
std::vector<Map> maps(pid_t pid) {
    std::istringstream input(read("/proc/" + std::to_string(pid) + "/maps"));
    std::vector<Map> out;
    for (std::string line; std::getline(input, line);) {
        unsigned long start, end, off; int consumed = 0;
        if (sscanf(line.c_str(), "%lx-%lx %*s %lx %*s %*s %n", &start, &end, &off, &consumed) == 3) {
            auto path = line.substr(consumed);
            out.push_back({start, end, off, path});
        }
    }
    return out;
}
std::string canonical(const std::string& path) {
    char* resolved = realpath(path.c_str(), nullptr);
    if (!resolved) throw std::runtime_error("cannot resolve artifact path");
    std::string result(resolved); free(resolved); return result;
}
uintptr_t remoteSymbol(pid_t pid, void* address) {
    const uintptr_t local = reinterpret_cast<uintptr_t>(address);
    for (const auto& own : maps(getpid())) {
        if (local < own.start || local >= own.end || own.path.empty() || own.path[0] != '/') continue;
        const auto ownPath = canonical(own.path);
        for (const auto& remote : maps(pid)) {
            if (remote.offset != own.offset || remote.path.empty() || remote.path[0] != '/') continue;
            if (remote.path == own.path) return remote.start + local - own.start;
            try {
                if (canonical(remote.path) == ownPath) return remote.start + local - own.start;
            } catch (const std::exception&) { /* Unrelated deleted/memfd mappings are normal. */ }
        }
    }
    throw std::runtime_error("matching system library mapping not found");
}

class Trace {
    pid_t pid_;
    bool attached_ = false, stopped_ = false, saved_ = false;
    bool registersModified_ = false;
    user_pt_regs regs_{};
    user_fpsimd_state fp_{};
    bool fpSaved_ = false;
    std::vector<unsigned char> sve_;
    uintptr_t errnoAddress_ = 0;
    int errnoValue_ = 0;
    int waitStop(int timeoutMs) {
        const auto until = Clock::now() + std::chrono::milliseconds(timeoutMs);
        do {
            int status = 0;
            const pid_t result = waitpid(pid_, &status, __WALL | WNOHANG);
            if (result == pid_) {
                if (!WIFSTOPPED(status)) throw std::runtime_error("target exited during attach");
                stopped_ = true; return WSTOPSIG(status);
            }
            if (result < 0 && errno != EINTR) throw systemError("waitpid");
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        } while (Clock::now() < until);
        throw std::runtime_error("remote call timed out");
    }
    void getRegs(user_pt_regs& regs) {
        iovec io{&regs, sizeof(regs)};
        if (ptrace(PTRACE_GETREGSET, pid_, (void*)NT_PRSTATUS, &io) != 0 || io.iov_len != sizeof(regs))
            throw std::runtime_error("cannot read ARM64 registers");
    }
    void setRegs(const user_pt_regs& regs) {
        iovec io{const_cast<user_pt_regs*>(&regs), sizeof(regs)};
        if (ptrace(PTRACE_SETREGSET, pid_, (void*)NT_PRSTATUS, &io) != 0)
            throw std::runtime_error("cannot restore ARM64 registers");
    }
public:
    explicit Trace(pid_t pid) : pid_(pid) {
        if (ptrace(PTRACE_SEIZE, pid_, nullptr, nullptr) != 0)
            throw systemError("PTRACE_SEIZE");
        attached_ = true;
        try {
            if (ptrace(PTRACE_INTERRUPT, pid_, nullptr, nullptr) != 0) throw systemError("PTRACE_INTERRUPT before remote execution");
            waitStop(3000); getRegs(regs_); saved_ = true;
            iovec io{&fp_, sizeof(fp_)};
            fpSaved_ = ptrace(PTRACE_GETREGSET, pid_, (void*)NT_PRFPREG, &io) == 0 && io.iov_len == sizeof(fp_);
            if (!fpSaved_) throw std::runtime_error("cannot preserve SIMD registers");
            // Preserve full scalable-vector state too when the kernel supports it.
            sve_.resize(65536);
            iovec vectors{sve_.data(), sve_.size()};
            if (ptrace(PTRACE_GETREGSET, pid_, (void*)0x405 /* NT_ARM_SVE */, &vectors) == 0)
                sve_.resize(vectors.iov_len);
            else sve_.clear();
        } catch (...) { restore(); throw; }
    }
    ~Trace() { restore(); }
    bool restore() noexcept {
        if (!attached_) return true;
        bool ok = true;
        if (!stopped_) {
            ptrace(PTRACE_INTERRUPT, pid_, nullptr, nullptr);
            try { waitStop(3000); } catch (...) { ok = false; }
        }
        if (stopped_ && saved_ && registersModified_) {
            if (errnoAddress_) {
                try { writeMemory(errnoAddress_, &errnoValue_, sizeof(errnoValue_)); } catch (...) { ok = false; }
            }
            try { setRegs(regs_); } catch (...) { ok = false; }
            if (fpSaved_) {
                iovec io{&fp_, sizeof(fp_)};
                if (ptrace(PTRACE_SETREGSET, pid_, (void*)NT_PRFPREG, &io) != 0) ok = false;
            }
            if (!sve_.empty()) {
                iovec io{sve_.data(), sve_.size()};
                if (ptrace(PTRACE_SETREGSET, pid_, (void*)0x405, &io) != 0) ok = false;
            }
        }
        if (ptrace(PTRACE_DETACH, pid_, nullptr, nullptr) != 0) {
            const int code = errno;
            fprintf(stderr, "MockGeo loader: PTRACE_DETACH failed; errno=%d (%s)\n", code, strerror(code));
            ok = false;
        }
        attached_ = false;
        if (!ok) fprintf(stderr, "MockGeo loader: %s\n", registersModified_ ?
            "target restoration failed after register modification" : "attach cleanup not confirmed; no remote code executed");
        return ok;
    }
    uintptr_t call(uintptr_t entry, std::initializer_list<uintptr_t> args) {
        if (!stopped_ || args.size() > 8) throw std::runtime_error("invalid call state");
        auto call = regs_;
        size_t i = 0; for (auto arg : args) call.regs[i++] = arg;
        call.pc = entry;
        call.regs[30] = 0; // return traps before any instruction; never delivered to the target
        call.sp &= ~uintptr_t(15);
        registersModified_ = true;
        setRegs(call);
        if (ptrace(PTRACE_CONT, pid_, nullptr, nullptr) != 0) throw std::runtime_error("continue failed");
        stopped_ = false;
        const int signal = waitStop(8000);
        user_pt_regs result{}; getRegs(result);
        if (signal != SIGSEGV || result.pc != 0) throw std::runtime_error("unexpected signal during remote call");
        return result.regs[0];
    }
    void preserveErrno(uintptr_t getter) {
        const auto address = call(getter, {});
        if (!address) throw std::runtime_error("target errno address missing");
        errno = 0;
        const long value = ptrace(PTRACE_PEEKDATA, pid_, (void*)address, nullptr);
        if (errno) throw std::runtime_error("cannot preserve target errno");
        memcpy(&errnoValue_, &value, sizeof(errnoValue_));
        errnoAddress_ = address;
    }
    void writeMemory(uintptr_t remote, const void* data, size_t bytes) {
        const auto* source = static_cast<const unsigned char*>(data);
        for (size_t offset = 0; offset < bytes; offset += sizeof(long)) {
            long word = 0;
            const size_t count = std::min(sizeof(word), bytes - offset);
            if (count != sizeof(word)) {
                errno = 0;
                word = ptrace(PTRACE_PEEKDATA, pid_, (void*)(remote + offset), nullptr);
                if (errno != 0) throw std::runtime_error("read remote memory failed");
            }
            memcpy(&word, source + offset, count);
            if (ptrace(PTRACE_POKEDATA, pid_, (void*)(remote + offset), (void*)word) != 0)
                throw std::runtime_error("write remote memory failed");
        }
    }
};
}

int main(int argc, char** argv) {
    try {
        if (argc != 6) throw std::runtime_error("usage: loader PID LIBRARY PAYLOAD NONCE system|probe");
        if (getuid() != 0) throw std::runtime_error("root is required");
        char* end = nullptr; long number = strtol(argv[1], &end, 10);
        if (!end || *end || number <= 1) throw std::runtime_error("invalid PID");
        const pid_t pid = static_cast<pid_t>(number);
        const std::string mode(argv[5]), nonce(argv[4]);
        if (nonce.size() != 32 || nonce.find_first_not_of("0123456789abcdef") != std::string::npos)
            throw std::runtime_error("invalid nonce");
        auto cmdline = read("/proc/" + std::to_string(pid) + "/cmdline");
        cmdline.resize(cmdline.find('\0'));
        if (!((mode == "system" && cmdline == "system_server") ||
              (mode == "probe" && (cmdline == "mockgeo-root-probe" ||
                                    cmdline == "com.mockgeo.probe:rootcompat"))))
            throw std::runtime_error("target is not a MockGeo loader target");
        const std::string library = canonical(argv[2]), payload = canonical(argv[3]);
        struct stat st{};
        for (const auto& path : {library, payload}) {
            if (stat(path.c_str(), &st) || !S_ISREG(st.st_mode) || st.st_uid != 0 || (st.st_mode & 022))
                throw std::runtime_error("artifacts must be root-owned regular files without group/world write");
        }
        const auto fnMmap = remoteSymbol(pid, (void*)&mmap);
        const auto fnMunmap = remoteSymbol(pid, (void*)&munmap);
        const auto fnDlopen = remoteSymbol(pid, (void*)&dlopen);
        const auto fnDlsym = remoteSymbol(pid, (void*)&dlsym);
        const auto fnErrno = remoteSymbol(pid, (void*)&__errno);
        Trace trace(pid);
        trace.preserveErrno(fnErrno);
        const uintptr_t scratch = trace.call(fnMmap, {0, 16384, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, uintptr_t(-1), 0});
        if (!scratch || scratch == uintptr_t(-1)) throw std::runtime_error("remote allocation failed");
        uintptr_t cursor = scratch;
        auto string = [&](const std::string& value) {
            if (cursor + value.size() + 1 >= scratch + 16384) throw std::runtime_error("argument too long");
            const auto where = cursor; trace.writeMemory(where, value.c_str(), value.size() + 1);
            cursor += (value.size() + 8) & ~uintptr_t(7); return where;
        };
        const auto libArg = string(library), dexArg = string(payload), nonceArg = string(nonce);
        const auto modeArg = string(mode), symbolArg = string("mockgeo_root_start");
        const uintptr_t handle = trace.call(fnDlopen, {libArg, RTLD_NOW | RTLD_LOCAL});
        if (!handle) throw std::runtime_error("target dlopen rejected the library (check namespace/SELinux/dependencies)");
        const uintptr_t start = trace.call(fnDlsym, {handle, symbolArg});
        if (!start) throw std::runtime_error("bootstrap symbol missing");
        const int result = static_cast<int>(trace.call(start, {dexArg, nonceArg, modeArg}));
        trace.call(fnMunmap, {scratch, 16384});
        if (!trace.restore()) throw std::runtime_error("target restoration failed");
        if (result != 0) throw std::runtime_error("bootstrap already started or thread creation failed: " + std::to_string(result));
        printf("{\"queued\":true,\"pid\":%d,\"nonce\":\"%s\"}\n", pid, nonce.c_str());
        return 0;
    } catch (const std::exception& error) {
        fprintf(stderr, "MockGeo loader: %s\n", error.what()); return 1;
    }
}
