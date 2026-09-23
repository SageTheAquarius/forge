package forge.compat;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Whole-type remap target for java.util.concurrent.atomic.LongAdder (Java 8,
 * absent on MobiVM). forge.ai.AiPerf keeps its counters in LongAdders and the
 * AI hot paths bump them constantly; an AtomicLong is slower under heavy
 * contention but there is one game thread here, so it does not matter.
 */
public class JLongAdder extends Number {
    private static final long serialVersionUID = 1L;
    private final AtomicLong value = new AtomicLong();

    public JLongAdder() { }

    public void add(long x) { value.addAndGet(x); }
    public void increment() { value.incrementAndGet(); }
    public void decrement() { value.decrementAndGet(); }
    public long sum() { return value.get(); }
    public void reset() { value.set(0L); }
    public long sumThenReset() { return value.getAndSet(0L); }

    @Override public long longValue() { return value.get(); }
    @Override public int intValue() { return (int) value.get(); }
    @Override public float floatValue() { return (float) value.get(); }
    @Override public double doubleValue() { return (double) value.get(); }
    @Override public String toString() { return Long.toString(value.get()); }
}
