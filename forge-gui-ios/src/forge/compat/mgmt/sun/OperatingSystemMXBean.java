package forge.compat.mgmt.sun;

/**
 * Remap target for com.sun.management.OperatingSystemMXBean. Nothing on
 * MobiVM implements it, so `os instanceof com.sun.management.OperatingSystemMXBean`
 * (the bridge's process-CPU sampler) is simply false instead of a
 * NoClassDefFoundError.
 */
public interface OperatingSystemMXBean extends forge.compat.mgmt.OperatingSystemMXBean {
    long getProcessCpuTime();
    double getProcessCpuLoad();
    double getSystemCpuLoad();
}
