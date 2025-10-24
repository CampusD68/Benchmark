package com.campusd68.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.sun.management.OperatingSystemMXBean;

public final class BenchmarkMonitor {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final OperatingSystemMXBean OS_BEAN =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final long WINDOWS_BOOT_EPOCH_MILLIS = queryWindowsBootMillis();
    private static final DateTimeFormatter CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withLocale(Locale.JAPAN)
            .withZone(ZoneId.systemDefault());

    private BenchmarkMonitor() {
    }

    private static final class CpuSnapshot {
        long idleTicks;
        long totalTicks;
    }

    private static final class MemoryStatus {
        long totalBytes;
        long availableBytes;
        boolean valid;
    }

    private static final class TaskSummary {
        long total;
        boolean valid;
    }

    private static final class LoadAverages {
        double one;
        double five;
        double fifteen;
        boolean valid;
    }

    // WindowsコンソールにANSIエスケープを許可する。
    // VTモードを有効化できない環境では false を返し画面更新を諦める。
    private static boolean enableVirtualTerminalProcessing() {
        if (!IS_WINDOWS) {
            return true;
        }
        String script = String.join(" ",
                "Add-Type -Namespace VT -Name NativeMethods -MemberDefinition @'",
                "using System;",
                "using System.Runtime.InteropServices;",
                "[DllImport(\"kernel32.dll\", SetLastError = true)] public static extern IntPtr GetStdHandle(int nStdHandle);",
                "[DllImport(\"kernel32.dll\", SetLastError = true)] public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out int lpMode);",
                "[DllImport(\"kernel32.dll\", SetLastError = true)] public static extern bool SetConsoleMode(IntPtr hConsoleHandle, int dwMode);",
                "'@;",
                "$handle = [VT.NativeMethods]::GetStdHandle(-11);",
                "if ($handle -eq [IntPtr]::Zero) { exit 1 };",
                "$mode = 0;",
                "if (-not [VT.NativeMethods]::GetConsoleMode($handle, [ref]$mode)) { exit 1 };",
                "if ($mode -band 4) { exit 0 };",
                "if ([VT.NativeMethods]::SetConsoleMode($handle, $mode -bor 4)) { exit 0 } else { exit 1 };");
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    // 端末をクリアしカーソルを先頭へ戻す。
    // 毎秒呼び出して最新状態だけを表示するための制御。
    private static void clearScreen() {
        System.out.print("\u001B[2J\u001B[H");
    }

    // FILETIME を 64bit 値へ詰め替えるヘルパー。
    // CPU 時刻計算で扱いやすいよう整数化する。
    @SuppressWarnings("unused")
    private static long fileTimeToUint64(long high, long low) {
        return (high << 32) | (low & 0xFFFFFFFFL);
    }

    // OS 別にCPUの稼働/アイドル時間を取得してスナップショットに詰める。
    // 取得に失敗した場合は false を返し外側で終了を促す。
    private static boolean sampleCpu(CpuSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (IS_WINDOWS) {
            double load = OS_BEAN.getSystemCpuLoad();
            if (load < 0.0) {
                return false;
            }
            final long scale = 1_000_000L;
            long active = (long) (load * scale);
            snapshot.totalTicks = scale;
            snapshot.idleTicks = Math.max(0L, scale - active);
            return true;
        }
        Path statPath = Path.of("/proc/stat");
        if (!Files.isReadable(statPath)) {
            return false;
        }
        try (Stream<String> lines = Files.lines(statPath)) {
            Optional<String> first = lines.findFirst();
            if (first.isEmpty()) {
                return false;
            }
            String[] tokens = first.get().trim().split("\\s+");
            if (tokens.length < 5 || !"cpu".equals(tokens[0])) {
                return false;
            }
            long[] values = new long[Math.min(tokens.length - 1, 10)];
            for (int i = 0; i < values.length; i++) {
                values[i] = Long.parseLong(tokens[i + 1]);
            }
            long total = 0L;
            for (long value : values) {
                total += value;
            }
            long idle = values.length > 4 ? values[3] + values[4] : values[3];
            snapshot.totalTicks = total;
            snapshot.idleTicks = idle;
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    // 直近2回のCPUスナップショットの差分から使用率を算出する。
    // 分母がゼロの場合は0%として扱い例外的なスパイクを避ける。
    private static double computeCpuUsage(CpuSnapshot previous, CpuSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        long idleDelta = current.idleTicks - previous.idleTicks;
        long totalDelta = current.totalTicks - previous.totalTicks;
        if (totalDelta <= 0L) {
            return 0.0;
        }
        long activeDelta = totalDelta - idleDelta;
        return (double) activeDelta * 100.0 / (double) totalDelta;
    }

    // 利用可能メモリと総容量を取得し、MiB表示に利用する。
    // 取得できない環境では valid を false のままとして扱う。
    private static MemoryStatus sampleMemory() {
        MemoryStatus status = new MemoryStatus();
        long total = OS_BEAN.getTotalPhysicalMemorySize();
        long free = OS_BEAN.getFreePhysicalMemorySize();
        if (total > 0L) {
            status.totalBytes = total;
            status.availableBytes = Math.max(0L, free);
            status.valid = true;
        }
        return status;
    }

    // 稼働中プロセス数を数え上げ、Tasks 行に利用する。
    // WindowsはAPI列挙、Linuxは /proc を巡回して集計する。
    private static TaskSummary sampleTasks() {
        TaskSummary summary = new TaskSummary();
        try {
            long count = ProcessHandle.allProcesses().count();
            summary.total = count;
            summary.valid = true;
        } catch (SecurityException ex) {
            summary.valid = false;
        }
        return summary;
    }

    // システムのロードアベレージを取得する。
    // Windowsでは取得不能なので valid=false として N/A 表示にする。
    private static LoadAverages sampleLoadAverages() {
        LoadAverages averages = new LoadAverages();
        if (IS_WINDOWS) {
            averages.valid = false;
            return averages;
        }
        Path loadAvgPath = Path.of("/proc/loadavg");
        if (!Files.isReadable(loadAvgPath)) {
            return averages;
        }
        try (BufferedReader reader = Files.newBufferedReader(loadAvgPath)) {
            String[] tokens = reader.readLine().trim().split("\\s+");
            if (tokens.length < 3) {
                return averages;
            }
            averages.one = Double.parseDouble(tokens[0]);
            averages.five = Double.parseDouble(tokens[1]);
            averages.fifteen = Double.parseDouble(tokens[2]);
            averages.valid = true;
        } catch (IOException ex) {
            averages.valid = false;
        }
        return averages;
    }

    // システムの稼働秒数を取得し uptime 表示に使用する。
    // Windows は GetTickCount64、Linux は /proc/uptime を参照する。
    private static long uptimeSeconds() {
        if (IS_WINDOWS) {
            if (WINDOWS_BOOT_EPOCH_MILLIS <= 0L) {
                return 0L;
            }
            long diff = System.currentTimeMillis() - WINDOWS_BOOT_EPOCH_MILLIS;
            return diff > 0L ? diff / 1000L : 0L;
        }
        Path uptimePath = Path.of("/proc/uptime");
        if (!Files.isReadable(uptimePath)) {
            return 0L;
        }
        try (BufferedReader reader = Files.newBufferedReader(uptimePath)) {
            String[] tokens = reader.readLine().trim().split("\\s+");
            if (tokens.length == 0) {
                return 0L;
            }
            double uptime = Double.parseDouble(tokens[0]);
            return (long) uptime;
        } catch (IOException ex) {
            return 0L;
        }
    }

    // 秒数を「日, 時:分」形式へ整形し top 風の文字列を返す。
    // 1分未満の短時間は "XXs" として短く表示する。
    private static String formatUptime(long seconds) {
        if (seconds < 60L) {
            return seconds + "s";
        }
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            builder.append(days).append(" day");
            if (days > 1L) {
                builder.append('s');
            }
            builder.append(", ");
        }
        builder.append(String.format(Locale.ROOT, "%02d:%02d", hours, minutes));
        return builder.toString();
    }

    // 現在時刻を HH:MM:SS フォーマットで返す。
    // Windows/Linux それぞれのスレッドセーフな localtime を利用する。
    private static String currentTimeString() {
        return CLOCK_FORMATTER.format(Instant.now());
    }

    // バイト数を MiB 単位の少数表記へ変換する。
    // 表示整形専用のヘルパーで、1桁の小数精度を持たせる。
    private static String formatMemoryMib(long bytes) {
        double mib = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f", mib);
    }

    // 各種メトリクスを1秒周期で収集し、top風レイアウトで画面更新する。
    // 取得に失敗した場合はエラーメッセージを表示して終了する。
    public static void main(String[] args) {
        if (!enableVirtualTerminalProcessing()) {
            System.err.println("ANSIエスケープの有効化に失敗しました。表示が乱れる可能性があります。");
        }

        CpuSnapshot previous = new CpuSnapshot();
        if (!sampleCpu(previous)) {
            System.err.println("CPU情報の取得に失敗しました。終了します。");
            return;
        }

        while (true) {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            CpuSnapshot current = new CpuSnapshot();
            if (!sampleCpu(current)) {
                System.err.println("CPU情報の取得に失敗しました。終了します。");
                return;
            }

            double cpuUsage = computeCpuUsage(previous, current);
            previous = current;

            MemoryStatus memory = sampleMemory();
            TaskSummary tasks = sampleTasks();
            LoadAverages load = sampleLoadAverages();
            long uptime = uptimeSeconds();

            clearScreen();

            System.out.print("top - " + currentTimeString()
                    + " up " + formatUptime(uptime)
                    + ",  load average: ");
            if (load.valid) {
                System.out.printf(Locale.ROOT, "%.2f, %.2f, %.2f%n", load.one, load.five, load.fifteen);
            } else {
                System.out.println("N/A, N/A, N/A");
            }

            if (tasks.valid) {
                System.out.println("Tasks: " + tasks.total + " total"
                        + ", 1 running, 0 sleeping, 0 stopped, 0 zombie");
            } else {
                System.out.println("Tasks: N/A");
            }

            System.out.printf(Locale.ROOT, "%%Cpu(s): %.1f us, %.1f id%n", cpuUsage, 100.0 - cpuUsage);

            if (memory.valid) {
                long usedBytes = Math.max(0L, memory.totalBytes - memory.availableBytes);
                System.out.println("MiB Mem : " + formatMemoryMib(memory.totalBytes)
                        + " total, " + formatMemoryMib(usedBytes)
                        + " used, " + formatMemoryMib(memory.availableBytes)
                        + " free");
            } else {
                System.out.println("MiB Mem : N/A");
            }
            System.out.flush();
        }
    }

    private static long queryWindowsBootMillis() {
        if (!IS_WINDOWS) {
            return -1L;
        }
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "(Get-CimInstance -ClassName Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return -1L;
                }
                if (line == null || line.isBlank()) {
                    return -1L;
                }
                Instant boot = Instant.parse(line.trim());
                return boot.toEpochMilli();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (IOException ex) {
            return -1L;
        }
    }
}
