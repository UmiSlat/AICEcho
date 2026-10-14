#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/system_properties.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#include <dobby.h>

namespace {

constexpr char kLogTag[] = "AICEchoPmmPatch";
constexpr std::array<std::uint8_t, 8> kPmm = {0x00, 0xF1, 0x00, 0x00, 0x00, 0x01, 0x43, 0x00};
constexpr char kLegacySymbol[] = "_Z23nfa_dm_check_set_confighPhb";
constexpr char kVendorSymbol[] = "_Z17HalSendDownstreamPvPKhm";
constexpr char kModernEnabledProperty[] = "vendor.aicecho.pmm.enabled";
constexpr char kLegacyEnabledProperty[] = "tmp.aicecho.pmm.enabled";

using LegacyFunction = void (*)(std::uint8_t, std::uint8_t*, bool);
using VendorFunction = int (*)(void*, const std::uint8_t*, std::size_t);

LegacyFunction originalLegacy = nullptr;
VendorFunction originalVendor = nullptr;
std::once_flag installOnce;

bool propertyEnabled(const char* name) {
    std::array<char, PROP_VALUE_MAX> value = {};
    if (__system_property_get(name, value.data()) <= 0) return false;
    return std::strcmp(value.data(), "1") == 0 || std::strcmp(value.data(), "true") == 0;
}

bool patchEnabled() {
    return propertyEnabled(kModernEnabledProperty) || propertyEnabled(kLegacyEnabledProperty);
}

bool patchParameter(std::uint8_t identifier, std::uint8_t* lengthAndValue) {
    if (lengthAndValue == nullptr) return false;
    const std::size_t length = lengthAndValue[0];
    std::uint8_t* value = lengthAndValue + 1;
    if (identifier == 0x51 && length >= kPmm.size()) {
        std::copy(kPmm.begin(), kPmm.end(), value);
        return true;
    } else if (identifier == 0x40 && length >= 0x12) {
        std::copy(kPmm.begin(), kPmm.end(), value + length - kPmm.size());
        return true;
    }
    return false;
}

bool patchCoreSetConfig(std::uint8_t* packet, std::size_t size) {
    if (packet == nullptr || size < 5 || packet[0] != 0x20 || packet[1] != 0x02) return false;
    const std::size_t payloadEnd = std::min(size, static_cast<std::size_t>(3 + packet[2]));
    if (payloadEnd <= 4) return false;
    std::size_t cursor = 4;
    bool patched = false;
    while (cursor + 2 <= payloadEnd) {
        const std::uint8_t identifier = packet[cursor];
        const std::size_t length = packet[cursor + 1];
        if (cursor + 2 + length > payloadEnd) break;
        patched = patchParameter(identifier, packet + cursor + 1) || patched;
        cursor += 2 + length;
    }
    return patched;
}

void replacementLegacy(std::uint8_t identifier, std::uint8_t* lengthAndValue, bool local) {
    if (patchEnabled() && patchParameter(identifier, lengthAndValue)) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "patched legacy PMm parameter 0x%02x", identifier);
    }
    originalLegacy(identifier, lengthAndValue, local);
}

int replacementVendor(void* context, const std::uint8_t* packet, std::size_t size) {
    if (packet == nullptr || size < 5 || packet[0] != 0x20 || packet[1] != 0x02) {
        return originalVendor(context, packet, size);
    }
    if (!patchEnabled()) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "PMm patch disabled; passing through ST HAL CORE_SET_CONFIG");
        return originalVendor(context, packet, size);
    }
    std::vector<std::uint8_t> mutablePacket(packet, packet + size);
    if (patchCoreSetConfig(mutablePacket.data(), mutablePacket.size())) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "patched ST HAL CORE_SET_CONFIG PMm");
    }
    return originalVendor(context, mutablePacket.data(), mutablePacket.size());
}

int patchExecutablePointerSlots(void* original, void* replacement) {
    char executable[512];
    const ssize_t executableLength = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (executableLength <= 0) return -1;
    executable[executableLength] = '\0';

    FILE* maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return -1;

    char line[768];
    int patched = 0;
    const long pageSize = sysconf(_SC_PAGESIZE);
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        if (std::strstr(line, executable) == nullptr) continue;

        unsigned long start = 0;
        unsigned long end = 0;
        char permissions[8] = {};
        if (std::sscanf(line, "%lx-%lx %7s", &start, &end, permissions) != 3) continue;
        if (permissions[0] != 'r' || permissions[2] == 'x') continue;

        int originalProtection = PROT_READ;
        if (permissions[1] == 'w') originalProtection |= PROT_WRITE;
        for (unsigned long address = start; address + sizeof(void*) <= end; address += sizeof(void*)) {
            auto** slot = reinterpret_cast<void**>(address);
            if (*slot != original) continue;

            const auto page = address & ~(static_cast<unsigned long>(pageSize) - 1UL);
            if (mprotect(reinterpret_cast<void*>(page), static_cast<std::size_t>(pageSize),
                         originalProtection | PROT_WRITE) != 0) {
                continue;
            }
            *slot = replacement;
            mprotect(reinterpret_cast<void*>(page), static_cast<std::size_t>(pageSize), originalProtection);
            patched++;
        }
    }
    std::fclose(maps);
    return patched;
}

bool hookSymbol(const char* name, void* replacement, void** original) {
    void* address = dlsym(RTLD_DEFAULT, name);
    if (address == nullptr) return false;
    const int status = DobbyHook(address, replacement, original);
    __android_log_print(status == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kLogTag,
                        "hook %s: %d", name, status);
    return status == 0;
}

bool hookVendorSymbol() {
    void* address = dlsym(RTLD_DEFAULT, kVendorSymbol);
    if (address == nullptr) return false;

    originalVendor = reinterpret_cast<VendorFunction>(address);
    const int patched = patchExecutablePointerSlots(address, reinterpret_cast<void*>(replacementVendor));
    __android_log_print(patched > 0 ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, kLogTag,
                        "ST HAL GOT patch: %d slot(s)", patched);
    if (patched > 0) return true;

    return hookSymbol(kVendorSymbol, reinterpret_cast<void*>(replacementVendor),
                      reinterpret_cast<void**>(&originalVendor));
}

void installHooks() {
    std::call_once(installOnce, [] {
        bool installed = hookVendorSymbol();
        installed = hookSymbol(kLegacySymbol, reinterpret_cast<void*>(replacementLegacy),
                               reinterpret_cast<void**>(&originalLegacy)) || installed;
        if (installed) __system_property_set("tmp.aicecho.pmm.active", "true");
        else __android_log_print(ANDROID_LOG_WARN, kLogTag, "no supported NFC symbol was found");
    });
}

}  // namespace

__attribute__((constructor)) static void onLibraryLoaded() {
    installHooks();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    installHooks();
    return JNI_VERSION_1_6;
}
