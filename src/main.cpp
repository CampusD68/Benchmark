#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
#include <cctype>
#include <ctime>

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#else
#include <unistd.h>
#endif

namespace {
struct CpuSnapshot {
    std::uint64_t idle_ticks{};
    std::uint64_t total_ticks{};
};

struct MemoryStatus {
    std::uint64_t total_bytes{};
    std::uint64_t available_bytes{};
    bool valid{false};
};

struct TaskSummary {
    std::size_t total{};
    bool valid{false};
};

struct LoadAverages {
    double one{};
    double five{};
    double fifteen{};
    bool valid{false};
};

#ifdef _WIN32
bool enable_virtual_terminal_processing() {
    HANDLE handle = GetStdHandle(STD_OUTPUT_HANDLE);
    if (handle == INVALID_HANDLE_VALUE) {
        return false;
    }
    DWORD mode = 0;
    if (!GetConsoleMode(handle, &mode)) {
        return false;
    }
    if (mode & ENABLE_VIRTUAL_TERMINAL_PROCESSING) {
        return true;
    }
    return SetConsoleMode(handle, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
}
#endif

void clear_screen() {
    std::cout << "\x1b[2J\x1b[H";
}

#ifdef _WIN32
std::uint64_t file_time_to_uint64(const FILETIME &ft) {
    ULARGE_INTEGER li;
    li.LowPart = ft.dwLowDateTime;
    li.HighPart = ft.dwHighDateTime;
    return li.QuadPart;
}
#endif

bool sample_cpu(CpuSnapshot &snapshot) {
#ifdef _WIN32
    FILETIME idle{}, kernel{}, user{};
    if (!GetSystemTimes(&idle, &kernel, &user)) {
        return false;
    }
    const std::uint64_t idle_ticks = file_time_to_uint64(idle);
    const std::uint64_t kernel_ticks = file_time_to_uint64(kernel);
    const std::uint64_t user_ticks = file_time_to_uint64(user);

    snapshot.idle_ticks = idle_ticks;
    snapshot.total_ticks = kernel_ticks + user_ticks;
    return true;
#else
    std::ifstream stat("/proc/stat");
    if (!stat.is_open()) {
        return false;
    }
    std::string line;
    if (!std::getline(stat, line)) {
        return false;
    }
    std::istringstream iss(line);
    std::string cpu_label;
    iss >> cpu_label;
    if (cpu_label != "cpu") {
        return false;
    }

    std::uint64_t values[10] = {};
    for (int i = 0; i < 10 && iss; ++i) {
        iss >> values[i];
    }

    std::uint64_t total = 0;
    for (int i = 0; i < 10; ++i) {
        total += values[i];
    }
    const std::uint64_t idle = values[3] + values[4];

    snapshot.idle_ticks = idle;
    snapshot.total_ticks = total;
    return true;
#endif
}

double compute_cpu_usage(const CpuSnapshot &prev, const CpuSnapshot &curr) {
    const std::uint64_t idle_delta = curr.idle_ticks - prev.idle_ticks;
    const std::uint64_t total_delta = curr.total_ticks - prev.total_ticks;
    if (total_delta == 0) {
        return 0.0;
    }
    const std::uint64_t active_delta = total_delta - idle_delta;
    return static_cast<double>(active_delta) * 100.0 / static_cast<double>(total_delta);
}

MemoryStatus sample_memory() {
    MemoryStatus status{};
#ifdef _WIN32
    MEMORYSTATUSEX memory_info{};
    memory_info.dwLength = sizeof(memory_info);
    if (GlobalMemoryStatusEx(&memory_info)) {
        status.total_bytes = memory_info.ullTotalPhys;
        status.available_bytes = memory_info.ullAvailPhys;
        status.valid = true;
    }
#else
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) {
        return status;
    }
    std::string key;
    std::uint64_t value = 0;
    std::string unit;
    std::uint64_t mem_total = 0;
    std::uint64_t mem_available = 0;
    while (meminfo >> key >> value >> unit) {
        if (key == "MemTotal:") {
            mem_total = value * 1024;
        } else if (key == "MemAvailable:") {
            mem_available = value * 1024;
        }
        if (mem_total && mem_available) {
            break;
        }
    }
    if (mem_total) {
        status.total_bytes = mem_total;
        status.available_bytes = mem_available;
        status.valid = true;
    }
#endif
    return status;
}

TaskSummary sample_tasks() {
    TaskSummary summary{};
#ifdef _WIN32
    std::vector<DWORD> process_ids(1024);
    DWORD bytes_returned = 0;
    while (true) {
        if (!EnumProcesses(process_ids.data(),
                           static_cast<DWORD>(process_ids.size() * sizeof(DWORD)),
                           &bytes_returned)) {
            return summary;
        }
        const std::size_t count = bytes_returned / sizeof(DWORD);
        if (count < process_ids.size()) {
            summary.total = count;
            summary.valid = true;
            return summary;
        }
        process_ids.resize(process_ids.size() * 2);
    }
#else
    std::size_t count = 0;
    try {
        for (const auto &entry : std::filesystem::directory_iterator("/proc")) {
            if (!entry.is_directory()) {
                continue;
            }
            const auto name = entry.path().filename().string();
            if (!name.empty() && std::all_of(name.begin(), name.end(),
                                             [](unsigned char c) { return std::isdigit(c) != 0; })) {
                ++count;
            }
        }
        summary.total = count;
        summary.valid = true;
    } catch (const std::exception &) {
        // Leave summary invalid if /proc is unavailable.
    }
#endif
    return summary;
}

LoadAverages sample_load_averages() {
    LoadAverages averages{};
#ifdef _WIN32
    averages.valid = false;
#else
    double loads[3] = {};
    if (getloadavg(loads, 3) == 3) {
        averages.one = loads[0];
        averages.five = loads[1];
        averages.fifteen = loads[2];
        averages.valid = true;
    }
#endif
    return averages;
}

std::uint64_t uptime_seconds() {
#ifdef _WIN32
    return static_cast<std::uint64_t>(GetTickCount64() / 1000ULL);
#else
    std::ifstream uptime_file("/proc/uptime");
    if (!uptime_file.is_open()) {
        return 0;
    }
    double uptime = 0.0;
    uptime_file >> uptime;
    return static_cast<std::uint64_t>(uptime);
#endif
}

std::string format_uptime(std::uint64_t seconds) {
    std::ostringstream oss;
    if (seconds < 60) {
        oss << seconds << "s";
        return oss.str();
    }

    const std::uint64_t days = seconds / 86400ULL;
    seconds %= 86400ULL;
    const std::uint64_t hours = seconds / 3600ULL;
    seconds %= 3600ULL;
    const std::uint64_t minutes = seconds / 60ULL;

    if (days > 0) {
        oss << days << " day";
        if (days > 1) {
            oss << 's';
        }
        oss << ", ";
    }
    oss << std::setw(2) << std::setfill('0') << hours << ":"
        << std::setw(2) << std::setfill('0') << minutes;
    return oss.str();
}

std::string current_time_string() {
    const auto now = std::chrono::system_clock::now();
    const std::time_t now_c = std::chrono::system_clock::to_time_t(now);
#ifdef _WIN32
    std::tm local_tm;
    localtime_s(&local_tm, &now_c);
#else
    std::tm local_tm;
    localtime_r(&now_c, &local_tm);
#endif
    std::ostringstream oss;
    oss << std::put_time(&local_tm, "%H:%M:%S");
    return oss.str();
}

std::string format_memory_mib(std::uint64_t bytes) {
    const double mib = static_cast<double>(bytes) / (1024.0 * 1024.0);
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(1) << mib;
    return oss.str();
}
} // namespace

int main() {
#ifdef _WIN32
    enable_virtual_terminal_processing();
#endif

    CpuSnapshot previous_snapshot{};
    if (!sample_cpu(previous_snapshot)) {
        std::cerr << "Failed to read CPU statistics. Exiting.\n";
        return 1;
    }

    while (true) {
        std::this_thread::sleep_for(std::chrono::seconds(1));

        CpuSnapshot current_snapshot{};
        if (!sample_cpu(current_snapshot)) {
            std::cerr << "Failed to read CPU statistics. Exiting.\n";
            return 1;
        }

        const double cpu_usage = compute_cpu_usage(previous_snapshot, current_snapshot);
        previous_snapshot = current_snapshot;

        const MemoryStatus memory = sample_memory();
        const TaskSummary tasks = sample_tasks();
        const LoadAverages loads = sample_load_averages();
        const std::uint64_t uptime = uptime_seconds();

        clear_screen();

        std::cout << "top - " << current_time_string()
                  << " up " << format_uptime(uptime)
                  << ",  load average: ";
        if (loads.valid) {
            std::cout << std::fixed << std::setprecision(2)
                      << loads.one << ", " << loads.five << ", " << loads.fifteen;
        } else {
            std::cout << "N/A, N/A, N/A";
        }
        std::cout << "\n";

        if (tasks.valid) {
            std::cout << "Tasks: " << tasks.total << " total"
                      << ", 1 running, 0 sleeping, 0 stopped, 0 zombie\n";
        } else {
            std::cout << "Tasks: N/A\n";
        }

        std::cout << std::fixed << std::setprecision(1)
                  << "%Cpu(s): " << cpu_usage
                  << " us, " << (100.0 - cpu_usage)
                  << " id\n";

        if (memory.valid) {
            const auto used = memory.total_bytes > memory.available_bytes
                                  ? memory.total_bytes - memory.available_bytes
                                  : 0ULL;
            std::cout << "MiB Mem : " << format_memory_mib(memory.total_bytes)
                      << " total, " << format_memory_mib(used)
                      << " used, " << format_memory_mib(memory.available_bytes)
                      << " free\n";
        } else {
            std::cout << "MiB Mem : N/A\n";
        }

        std::cout.flush();
    }

    return 0;
}
