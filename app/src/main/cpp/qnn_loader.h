#pragma once

#include <atomic>
#include <mutex>
#include <string>

enum HtpPerformanceProfile {
    HTP_PROFILE_DEFAULT = 0,
    HTP_PROFILE_HIGH_PERFORMANCE = 1,
    HTP_PROFILE_BURST = 2,
    HTP_PROFILE_POWER_SAVER = 3
};

class QnnDynamicLoader {
public:
    static QnnDynamicLoader& getInstance();

    QnnDynamicLoader(const QnnDynamicLoader&) = delete;
    QnnDynamicLoader& operator=(const QnnDynamicLoader&) = delete;

    bool setHtpPerformanceProfile(int profileOrdinal);
    int getHtpPerformanceProfile() const;
    const char* getProfileCornerName(int profileOrdinal) const;

private:
    QnnDynamicLoader();
    ~QnnDynamicLoader() = default;

    std::atomic<int> currentProfileOrdinal_{0};
    mutable std::mutex profileMutex_;
};
