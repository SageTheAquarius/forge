package forge.compat.mgmt;

/** Remap target for java.lang.management.ThreadMXBean: no per-thread CPU
 *  accounting on MobiVM, so callers take their "unsupported" branch. */
public interface ThreadMXBean {
    boolean isThreadCpuTimeSupported();
    boolean isCurrentThreadCpuTimeSupported();
    long getThreadCpuTime(long id);
    long getCurrentThreadCpuTime();
    int getThreadCount();
}
