package forge.compat.mgmt;

/** Remap target for java.lang.management.OperatingSystemMXBean. */
public interface OperatingSystemMXBean {
    String getName();
    String getArch();
    String getVersion();
    int getAvailableProcessors();
    double getSystemLoadAverage();
}
