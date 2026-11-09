package volume_profile.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All the profiles to draw from one tape: for each kind switched on, the period holding {@code now} (the current
 * one, still growing) and up to {@code past} finished periods before it.
 *
 * <p>Rules:
 * <ul>
 *   <li>A period whose opening the tape does not reach (the history starts later) is left out - better no profile
 *       than a plausible wrong one. {@link #SLACK_MS} allows for a session that opens without trades.</li>
 *   <li>A finished period runs from its first minute to its last; it is never extended to the right.</li>
 *   <li>The current period is the one holding {@code now}; if no minute of it has closed yet, there is none.</li>
 *   <li>Finished periods are computed once and kept ({@link Cache}); the current one is recomputed from its
 *       minutes every time.</li>
 * </ul>
 */
public final class ProfileSet {

    /** A period counts as covered when the data starts no later than this after its opening. */
    public static final long SLACK_MS = 2 * 3_600_000L;

    private ProfileSet() { }

    /** What to build for one kind. */
    public record Want(int binTicks, int past) {
        public Want {
            if (binTicks < 1) throw new IllegalArgumentException("binTicks " + binTicks);
            if (past < 0) throw new IllegalArgumentException("past " + past);
        }
    }

    /** Finished profiles by (kind, bin, first minute, last minute): once built they never change. */
    public static final class Cache {
        private final Map<String, Profile> map = new HashMap<>();
        private static final int MAX = 2000;

        synchronized Profile get(String key) { return map.get(key); }

        synchronized void put(String key, Profile p) {
            if (map.size() >= MAX) map.clear();
            map.put(key, p);
        }

        public synchronized int size() { return map.size(); }
    }

    /** One period of the tape: its key, opening time and minute range. */
    record Period(long key, long start, int first, int last) { }

    /** The periods of a kind, oldest first. */
    static List<Period> periods(MinuteTape tape, SessionClock clock, Kind kind) {
        List<Period> out = new ArrayList<>();
        int n = tape.size();
        if (n == 0) return out;
        int first = 0;
        long key = clock.key(kind, tape.time(0));
        for (int i = 1; i < n; i++) {
            long k = clock.key(kind, tape.time(i));
            if (k != key) {
                out.add(new Period(key, clock.start(kind, key), first, i - 1));
                first = i;
                key = k;
            }
        }
        out.add(new Period(key, clock.start(kind, key), first, n - 1));
        return out;
    }

    public static List<Profile> build(MinuteTape tape, SessionClock clock, Map<Kind, Want> wanted, long now, Cache cache) {
        List<Profile> out = new ArrayList<>();
        if (tape.size() == 0) return out;
        Map<Kind, Want> order = new EnumMap<>(wanted);
        for (var e : order.entrySet()) {
            Kind kind = e.getKey();
            Want w = e.getValue();
            long nowKey = clock.key(kind, now);
            List<Period> all = periods(tape, clock, kind);
            // newest first: the current one (if the tape holds a minute of it), then the finished ones
            int taken = 0;
            for (int p = all.size() - 1; p >= 0; p--) {
                Period per = all.get(p);
                if (clock.start(kind, per.key()) > now) continue;                  // minutes stamped in the future: not shown
                boolean current = per.key() == nowKey;
                if (!current) {
                    if (taken >= w.past()) break;
                    taken++;
                }
                if (tape.first() > per.start() + SLACK_MS) break;                  // the history does not reach its opening
                out.add(profile(tape, kind, per, current, w.binTicks(), cache));
            }
        }
        return out;
    }

    private static Profile profile(MinuteTape tape, Kind kind, Period p, boolean current, int bin, Cache cache) {
        if (current || cache == null) return ProfileCalc.compute(kind, p.start(), current, tape, p.first(), p.last(), bin);
        String key = kind + ":" + bin + ":" + tape.time(p.first()) + ":" + tape.time(p.last());
        Profile hit = cache.get(key);
        if (hit != null) return hit;
        Profile made = ProfileCalc.compute(kind, p.start(), false, tape, p.first(), p.last(), bin);
        cache.put(key, made);
        return made;
    }

    /** How far back the history must reach for these wants at {@code now}. */
    public static long historyStart(SessionClock clock, Map<Kind, Want> wanted, long now) {
        long start = now;
        for (var e : wanted.entrySet()) start = Math.min(start, clock.startBack(e.getKey(), now, e.getValue().past()));
        return start;
    }
}
