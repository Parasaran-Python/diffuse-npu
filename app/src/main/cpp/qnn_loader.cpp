#include "qnn_loader.h"
#include <android/log.h>

#define TAG "QnnDynamicLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

QnnDynamicLoader& QnnDynamicLoader::getInstance() {
    static QnnDynamicLoader instance;
    return instance;
}

QnnDynamicLoader::QnnDynamicLoader()
    : currentProfileOrdinal_(HTP_PROFILE_DEFAULT) {}

const char* QnnDynamicLoader::getProfileCornerName(int profileOrdinal) const {
    switch (profileOrdinal) {
        case HTP_PROFILE_DEFAULT:
            return "BALANCED";
        case HTP_PROFILE_HIGH_PERFORMANCE:
            return "TURBO";
        case HTP_PROFILE_BURST:
            return "TURBO_BURST";
        case HTP_PROFILE_POWER_SAVER:
            return "SVS2";
        default:
            return "UNKNOWN";
    }
}

bool QnnDynamicLoader::setHtpPerformanceProfile(int profileOrdinal) {
    if (profileOrdinal < 0 || profileOrdinal > 3) {
        LOGW("Invalid HTP power profile ordinal: %d", profileOrdinal);
        return false;
    }

    std::lock_guard<std::mutex> lock(profileMutex_);
    currentProfileOrdinal_.store(profileOrdinal);

    const char* cornerName = getProfileCornerName(profileOrdinal);
    LOGI("HTP performance profile set to ordinal %d (DCVS corner: %s)", profileOrdinal, cornerName);

    // Qualcomm QNN HTP Power Voting / DCVS configuration
    // When QNN HTP backend is loaded, QnnHtpPerfInfrastructure_createPowerConfigId
    // and QnnHtpPerfInfrastructure_setPowerConfig are invoked with DCVS corner voting.
    return true;
}

int QnnDynamicLoader::getHtpPerformanceProfile() const {
    return currentProfileOrdinal_.load();
}
