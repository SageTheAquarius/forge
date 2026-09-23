package forge.compat.mgmt;

import java.util.Collections;
import java.util.List;

/**
 * Whole-type remap target for java.lang.management.ManagementFactory (absent
 * on MobiVM). Backed by java.lang.Runtime; satisfies the boot-time probes of
 * apfloat's ApfloatContext, tinylog's runtime provider, RestartUtil, and the
 * EconomyDraft bridge's game-thread CPU sampler (which learns that per-thread
 * CPU time is unsupported and reports -1, as it does on any JVM without it).
 */
public final class ManagementFactory {
    private static final long START = System.currentTimeMillis();
    private static final RuntimeMXBean RUNTIME = new RuntimeMXBean() {
        @Override
        public String getName() {
            return "1@forge-ios";
        }

        @Override
        public long getStartTime() {
            return START;
        }

        @Override
        public long getUptime() {
            return System.currentTimeMillis() - START;
        }

        @Override
        public List<String> getInputArguments() {
            return Collections.emptyList();
        }
    };
    private static final MemoryMXBean MEMORY = new MemoryMXBean() {
        @Override
        public MemoryUsage getHeapMemoryUsage() {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            return new MemoryUsage(rt.totalMemory(), used, rt.totalMemory(), max);
        }

        @Override
        public MemoryUsage getNonHeapMemoryUsage() {
            return new MemoryUsage(0, 0, 0, -1);
        }
    };
    private static final ThreadMXBean THREADS = new ThreadMXBean() {
        @Override public boolean isThreadCpuTimeSupported() { return false; }
        @Override public boolean isCurrentThreadCpuTimeSupported() { return false; }
        @Override public long getThreadCpuTime(long id) { return -1L; }
        @Override public long getCurrentThreadCpuTime() { return -1L; }
        @Override public int getThreadCount() { return Thread.activeCount(); }
    };
    private static final OperatingSystemMXBean OS = new OperatingSystemMXBean() {
        @Override public String getName() { return System.getProperty("os.name", "iOS"); }
        @Override public String getArch() { return System.getProperty("os.arch", "arm64"); }
        @Override public String getVersion() { return System.getProperty("os.version", ""); }
        @Override public int getAvailableProcessors() { return Runtime.getRuntime().availableProcessors(); }
        @Override public double getSystemLoadAverage() { return -1.0; }
    };

    private ManagementFactory() { }

    public static RuntimeMXBean getRuntimeMXBean() {
        return RUNTIME;
    }

    public static MemoryMXBean getMemoryMXBean() {
        return MEMORY;
    }

    public static ThreadMXBean getThreadMXBean() {
        return THREADS;
    }

    public static OperatingSystemMXBean getOperatingSystemMXBean() {
        return OS;
    }
}
