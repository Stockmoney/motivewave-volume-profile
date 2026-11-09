package volume_profile.core;

import java.util.Arrays;

/**
 * Closed one-minute bars in time order: start time, high and low in ticks, volume. Immutable: {@link #with} returns
 * a new tape, so the drawing thread may keep reading the old one while the next is being built.
 */
public final class MinuteTape {

    public static final MinuteTape EMPTY = new MinuteTape(new long[0], new int[0], new int[0], new long[0], 0);

    private final long[] time;
    private final int[] high;
    private final int[] low;
    private final long[] volume;
    private final int size;

    private MinuteTape(long[] time, int[] high, int[] low, long[] volume, int size) {
        this.time = time;
        this.high = high;
        this.low = low;
        this.volume = volume;
        this.size = size;
    }

    public int size() { return size; }
    public long time(int i) { check(i); return time[i]; }
    public int high(int i) { check(i); return high[i]; }
    public int low(int i) { check(i); return low[i]; }
    public long volume(int i) { check(i); return volume[i]; }
    public long first() { return size == 0 ? Long.MAX_VALUE : time[0]; }
    public long last() { return size == 0 ? Long.MIN_VALUE : time[size - 1]; }

    private void check(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " of " + size);
    }

    /** A price as whole ticks; the platform hands prices over as float (4500.1 is not exact), so it is rounded. */
    public static int ticks(double price, double tickSize) {
        return (int) Math.round(price / tickSize);
    }

    /** One minute to add: start time (ms), high and low in ticks, volume. */
    public record Minute(long time, int high, int low, long volume) {
        public Minute {
            if (high < low) throw new IllegalArgumentException("high " + high + " < low " + low);
            if (volume < 0) throw new IllegalArgumentException("volume " + volume);
        }
    }

    /**
     * This tape with the given minutes merged in: a minute with the same start time replaces the one held, the
     * others are inserted in time order. The input need not be sorted.
     */
    public MinuteTape with(Minute[] minutes) {
        if (minutes.length == 0) return this;
        Minute[] add = minutes.clone();
        Arrays.sort(add, (a, b) -> Long.compare(a.time(), b.time()));
        int cap = size + add.length;
        long[] t = new long[cap]; int[] h = new int[cap]; int[] l = new int[cap]; long[] v = new long[cap];
        int i = 0, j = 0, n = 0;
        while (i < size || j < add.length) {
            boolean takeNew;
            if (i >= size) takeNew = true;
            else if (j >= add.length) takeNew = false;
            else takeNew = add[j].time() <= time[i];
            if (takeNew) {
                Minute m = add[j++];
                if (i < size && time[i] == m.time()) i++;                       // the new value replaces the old one
                if (n > 0 && t[n - 1] == m.time()) n--;                         // duplicates in the input: the last wins
                t[n] = m.time(); h[n] = m.high(); l[n] = m.low(); v[n] = m.volume(); n++;
            } else {
                t[n] = time[i]; h[n] = high[i]; l[n] = low[i]; v[n] = volume[i]; n++; i++;
            }
        }
        return new MinuteTape(t, h, l, v, n);
    }

    /** The first index whose time is at or after {@code ms} (size when none). */
    public int indexFrom(long ms) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (time[mid] < ms) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Only the minutes that started before {@code ms} (tests: "the same history, cut at a moment"). */
    public MinuteTape cutBefore(long ms) {
        int n = indexFrom(ms);
        return new MinuteTape(Arrays.copyOf(time, n), Arrays.copyOf(high, n), Arrays.copyOf(low, n), Arrays.copyOf(volume, n), n);
    }

    /** Only the minutes from {@code ms} on (older ones are no longer needed). */
    public MinuteTape dropBefore(long ms) {
        int k = indexFrom(ms);
        if (k == 0) return this;
        int n = size - k;
        return new MinuteTape(Arrays.copyOfRange(time, k, size), Arrays.copyOfRange(high, k, size),
                Arrays.copyOfRange(low, k, size), Arrays.copyOfRange(volume, k, size), n);
    }
}
