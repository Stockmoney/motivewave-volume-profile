package volume_profile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import volume_profile.core.Kind;
import volume_profile.core.MinuteTape;
import volume_profile.core.MinuteTape.Minute;
import volume_profile.core.Profile;
import volume_profile.core.ProfileCalc;
import volume_profile.core.ProfileSet;
import volume_profile.core.SessionClock;

/** Plain-Java tests (no framework): run by build.sh; any failure stops the build. */
public final class Tests {

    private static int passed, failed;
    private static final SessionClock CLOCK = SessionClock.newYork();
    private static final long MIN = 60_000L;

    public static void main(String[] args) {
        valueAreaLikeTheTester();
        dayProfileIsTheVolumeSpreadOverTheRanges();
        periodsFollowTheCmeClock();
        hoursCountFromTheSessionOpen();
        historyThatStartsLateLeavesThePeriodOut();
        pastPeriodsAndTheCurrentOne();
        noFutureMinuteIsRead();
        finishedProfilesAreCachedTheCurrentIsNot();
        ticksFromFloatPrices();
        visibilityByChartBars();
        tapeMerging();
        historyStartReachesBackFarEnough();
        negativeTicksFloorLikeTheTester();
        System.out.println("tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers
    private static void check(boolean ok, String what) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + what); }
    }

    private static void eq(Object expected, Object actual, String what) {
        check(java.util.Objects.equals(expected, actual), what + " expected " + expected + " got " + actual);
    }

    private static long ny(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(SessionClock.NEW_YORK).toInstant().toEpochMilli();
    }

    /** Minutes every minute from {@code from} for {@code count} minutes, a random walk around {@code base} ticks. */
    private static Minute[] walk(long from, int count, int base, long seed) {
        Random r = new Random(seed);
        Minute[] out = new Minute[count];
        int price = base;
        for (int i = 0; i < count; i++) {
            price += r.nextInt(9) - 4;
            int lo = price - r.nextInt(6), hi = price + r.nextInt(6);
            out[i] = new Minute(from + i * MIN, hi, lo, 1 + r.nextInt(300));
        }
        return out;
    }

    /** Globex minutes only (no 17:00-18:00 break, no weekend), from {@code from} to {@code to}. */
    private static Minute[] globex(long from, long to, int base, long seed) {
        List<Minute> keep = new ArrayList<>();
        for (Minute m : walk(from, (int) ((to - from) / MIN), base, seed)) {
            var t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(m.time()), SessionClock.NEW_YORK);
            var dow = t.getDayOfWeek();
            boolean closed = t.getHour() == 17
                    || dow == java.time.DayOfWeek.SATURDAY
                    || dow == java.time.DayOfWeek.FRIDAY && t.getHour() >= 17
                    || dow == java.time.DayOfWeek.SUNDAY && t.getHour() < 18;
            if (!closed) keep.add(m);
        }
        return keep.toArray(new Minute[0]);
    }

    private static Map<Kind, ProfileSet.Want> want(Kind k, int bin, int past) {
        Map<Kind, ProfileSet.Want> m = new EnumMap<>(Kind.class);
        m.put(k, new ProfileSet.Want(bin, past));
        return m;
    }

    // ---------------------------------------------------------------- the tester's value-area cases (LabProfileTests.cs)
    private static void valueAreaLikeTheTester() {
        //              rows: 0  1  2   3  4  5  6  7    total 30, 70 % = 21; up 4+5 = 11 vs down 2+1 = 6 -> up, 21 inside
        eq("[3, 3, 5]", Arrays.toString(ProfileCalc.valueArea(new double[]{1, 2, 4, 10, 6, 5, 1, 1})), "value area up");
        // total 25, 70 % = 17.5; down 2+6 = 8 > up 1+1 -> rows 1..3 = 18
        eq("[3, 1, 3]", Arrays.toString(ProfileCalc.valueArea(new double[]{5, 6, 2, 10, 1, 1, 0, 0})), "value area down");
        eq("[0, 0, 0]", Arrays.toString(ProfileCalc.valueArea(new double[]{7})), "one row");
        // two rows at a time: up 4+4 beats down 5+0, both up rows join at once (18 of 23 >= 16.1)
        eq("[3, 3, 5]", Arrays.toString(ProfileCalc.valueArea(new double[]{0, 0, 5, 10, 4, 4, 0})), "two rows at a time");
        // a tie for the POC goes to the row nearer the middle; a tie up/down goes up
        eq(2, ProfileCalc.valueArea(new double[]{5, 1, 5, 1, 1})[0], "POC tie -> nearer the middle");
        eq("[2, 2, 4]", Arrays.toString(ProfileCalc.valueArea(new double[]{1, 1, 6, 1, 1})), "up/down tie -> up");
        // exactly at the threshold: 69 of 100 is not yet 70 %, so one more step (down 28+1 beats up 1)
        eq("[3, 1, 5]", Arrays.toString(ProfileCalc.valueArea(new double[]{1, 1, 28, 40, 0, 29, 1})), "69 % is not enough");
    }

    // ---------------------------------------------------------------- the profile itself, against a reference written apart
    private static void dayProfileIsTheVolumeSpreadOverTheRanges() {
        long open = ny(2026, 11, 2, 18, 0);                                  // Tuesday 3 Nov opens Monday 18:00
        Minute[] ms = walk(open, 600, 120_000, 7);
        MinuteTape tape = MinuteTape.EMPTY.with(ms);
        Profile p = ProfileCalc.compute(Kind.DAY, open, true, tape, 0, tape.size() - 1, 8);
        int low = Arrays.stream(ms).mapToInt(Minute::low).min().orElseThrow();
        int high = Arrays.stream(ms).mapToInt(Minute::high).max().orElseThrow();
        int lowRow = (int) Math.floor(low / 8.0), rows = (int) Math.floor(high / 8.0) - lowRow + 1;
        double[] ref = new double[rows];
        long total = 0;
        for (Minute m : ms) {
            int a = (int) Math.floor(m.low() / 8.0) - lowRow, z = (int) Math.floor(m.high() / 8.0) - lowRow;
            for (int r = a; r <= z; r++) ref[r] += (double) m.volume() / (z - a + 1);
            total += m.volume();
        }
        eq(lowRow * 8, p.fromTicks(), "from");
        check(Arrays.equals(ref, p.volumes()), "volumes bit for bit");
        int poc = 0;
        for (int r = 1; r < rows; r++) if (ref[r] > ref[poc]) poc = r;
        check(ref[p.pocRow()] == ref[poc], "POC is the fullest row");
        double inside = 0, sum = 0;
        for (int r = 0; r < rows; r++) { sum += ref[r]; if (r >= p.valRow() && r <= p.vahRow()) inside += ref[r]; }
        check(inside >= 0.7 * sum - 1e-9, "value area holds 70 %");
        check(p.valRow() <= p.pocRow() && p.pocRow() <= p.vahRow(), "VAL <= POC <= VAH");
        eq(total, p.totalVolume(), "total volume");
        eq(600, p.minutes(), "minutes");
        // prices as the tester shows them: POC = middle of its row, VAH = top of the upper row, VAL = bottom of the lower
        double tick = 0.25;
        eq((p.fromTicks() + (p.pocRow() + 0.5) * 8) * tick, p.poc(tick), "POC price");
        eq((p.fromTicks() + (p.vahRow() + 1) * 8) * tick, p.vah(tick), "VAH price");
        eq((p.fromTicks() + p.valRow() * 8) * tick, p.val(tick), "VAL price");
    }

    // ---------------------------------------------------------------- periods
    private static void periodsFollowTheCmeClock() {
        // Sunday 18:00 opens Monday, its day and its week
        long sun18 = ny(2026, 11, 8, 18, 0), sun1759 = ny(2026, 11, 8, 17, 59);
        eq(LocalDate.of(2026, 11, 9), CLOCK.tradeDate(sun18), "Sunday 18:00 = Monday");
        eq(LocalDate.of(2026, 11, 8), CLOCK.tradeDate(sun1759), "17:59 = same date");
        eq(CLOCK.key(Kind.WEEK, ny(2026, 11, 13, 16, 59)), CLOCK.key(Kind.WEEK, sun18), "Sunday evening and Friday afternoon: one week");
        check(CLOCK.key(Kind.WEEK, ny(2026, 11, 6, 16, 59)) != CLOCK.key(Kind.WEEK, sun18), "the Friday before is another week");
        eq(sun18, CLOCK.start(Kind.WEEK, CLOCK.key(Kind.WEEK, ny(2026, 11, 11, 10, 0))), "week opens Sunday 18:00");
        // the evening of a month's last day belongs to the next month
        long oct30eve = ny(2026, 10, 29, 18, 30);                 // trade date Fri 30 Oct
        eq(CLOCK.key(Kind.MONTH, ny(2026, 10, 1, 10, 0)), CLOCK.key(Kind.MONTH, oct30eve), "30 Oct evening is still October");
        long nov = ny(2026, 11, 1, 18, 0);                         // Sunday 1 Nov 18:00 -> Monday 2 Nov
        eq(CLOCK.key(Kind.MONTH, ny(2026, 11, 20, 10, 0)), CLOCK.key(Kind.MONTH, nov), "1 Nov evening is November");
        eq(nov, CLOCK.monthStart(YearMonth.of(2026, 11)), "November opens Sunday 1 Nov 18:00 (first weekday is Mon 2)");
        eq(ny(2026, 9, 30, 18, 0), CLOCK.monthStart(YearMonth.of(2026, 10)), "October opens 30 Sep 18:00");
        // daylight saving ends Sunday 1 Nov 2026 at 02:00: 18:00 is still 18:00 New York
        eq(ny(2026, 10, 25, 18, 0) + 7 * 24 * 3_600_000L + 3_600_000L, ny(2026, 11, 1, 18, 0), "DST: the week is one hour longer");
        eq(LocalDate.of(2026, 11, 2), CLOCK.tradeDate(ny(2026, 11, 1, 18, 0)), "after DST the open is still 18:00");
    }

    private static void hoursCountFromTheSessionOpen() {
        long open = ny(2026, 11, 9, 18, 0);
        eq(open, CLOCK.hourStart(ny(2026, 11, 9, 18, 59)), "18:59 is in the 18:00 hour");
        eq(open + 3_600_000L, CLOCK.hourStart(ny(2026, 11, 9, 19, 0)), "19:00 opens the next hour");
        eq(ny(2026, 11, 10, 9, 0), CLOCK.hourStart(ny(2026, 11, 10, 9, 45)), "09:45 is in the 09:00 hour");
        // the 16:00 hour runs to 16:59, the last hour of the day
        eq(ny(2026, 11, 10, 16, 0), CLOCK.hourStart(ny(2026, 11, 10, 16, 59)), "16:59 is in the 16:00 hour");
        // a whole day of hours: 23 periods, each starting on the hour
        Minute[] ms = globex(open, open + 23 * 3_600_000L, 100_000, 3);
        MinuteTape tape = MinuteTape.EMPTY.with(ms);
        List<Profile> hours = ProfileSet.build(tape, CLOCK, want(Kind.HOUR, 4, 100), open + 23 * 3_600_000L - 1, null);
        eq(23, hours.size(), "23 hours in a day");
        for (Profile p : hours) check(p.firstMinute() == p.startMs() && p.minutes() == 60, "hour starts on the hour, 60 minutes");
    }

    // ---------------------------------------------------------------- coverage, past, current
    private static void historyThatStartsLateLeavesThePeriodOut() {
        long monOpen = ny(2026, 11, 8, 18, 0);
        // the history starts Monday 03:00 - Monday's day (opened Sunday 18:00) is not known whole
        Minute[] ms = globex(ny(2026, 11, 9, 3, 0), ny(2026, 11, 10, 12, 0), 100_000, 4);
        MinuteTape tape = MinuteTape.EMPTY.with(ms);
        List<Profile> days = ProfileSet.build(tape, CLOCK, want(Kind.DAY, 8, 10), ny(2026, 11, 10, 12, 0), null);
        eq(1, days.size(), "only Tuesday");
        eq(ny(2026, 11, 9, 18, 0), days.get(0).startMs(), "Tuesday opens Monday 18:00");
        check(days.get(0).current(), "Tuesday is current");
        // within the slack (data from 18:30): Monday counts
        MinuteTape tape2 = MinuteTape.EMPTY.with(globex(monOpen + 30 * MIN, ny(2026, 11, 10, 12, 0), 100_000, 5));
        eq(2, ProfileSet.build(tape2, CLOCK, want(Kind.DAY, 8, 10), ny(2026, 11, 10, 12, 0), null).size(), "an open without trades still counts");
        // the week: the history starts on Monday 03:00, the week opened Sunday 18:00 -> no week profile
        eq(0, ProfileSet.build(tape, CLOCK, want(Kind.WEEK, 20, 8), ny(2026, 11, 10, 12, 0), null).size(), "the week is not known whole");
    }

    private static void pastPeriodsAndTheCurrentOne() {
        long from = ny(2026, 11, 1, 18, 0), now = ny(2026, 11, 13, 10, 30);          // two weeks, now Friday morning
        MinuteTape tape = MinuteTape.EMPTY.with(globex(from, now + MIN, 100_000, 6));
        List<Profile> days = ProfileSet.build(tape, CLOCK, want(Kind.DAY, 8, 3), now, null);
        eq(4, days.size(), "current + 3 past days");
        eq(1L, days.stream().filter(Profile::current).count(), "one current");
        Profile cur = days.get(0);
        eq(ny(2026, 11, 12, 18, 0), cur.startMs(), "current day opened Thursday 18:00");
        eq(now, cur.lastMinute(), "current ends at the last closed minute");
        Profile thu = days.get(1);
        eq(ny(2026, 11, 12, 16, 59), thu.lastMinute(), "a finished day ends with its last minute (16:59), not extended");
        // on Saturday nothing is current; Friday is the newest finished day
        long sat = ny(2026, 11, 14, 12, 0);
        MinuteTape full = MinuteTape.EMPTY.with(globex(from, ny(2026, 11, 13, 17, 0), 100_000, 6));
        List<Profile> sd = ProfileSet.build(full, CLOCK, want(Kind.DAY, 8, 2), sat, null);
        eq(2, sd.size(), "Saturday: 2 past days, no current");
        check(sd.stream().noneMatch(Profile::current), "nothing current on Saturday");
        // 0 past = only the current one
        eq(1, ProfileSet.build(tape, CLOCK, want(Kind.DAY, 8, 0), now, null).size(), "past 0 -> current only");
        // the month of 1-13 Nov is current; October is not in the tape -> not drawn
        List<Profile> months = ProfileSet.build(tape, CLOCK, want(Kind.MONTH, 40, 3), now, null);
        eq(1, months.size(), "the month: only November (October is not in the history)");
        // the current hour is the 10:00 hour with 31 minutes (10:00-10:30)
        Profile hour = ProfileSet.build(tape, CLOCK, want(Kind.HOUR, 4, 0), now, null).get(0);
        eq(31, hour.minutes(), "current hour 10:00-10:30");
    }

    private static void noFutureMinuteIsRead() {
        // every profile built at a moment equals the one built from the history cut at that moment
        long from = ny(2026, 10, 25, 18, 0), to = ny(2026, 11, 13, 16, 0);
        MinuteTape full = MinuteTape.EMPTY.with(globex(from, to, 100_000, 8));
        Map<Kind, ProfileSet.Want> all = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) all.put(k, new ProfileSet.Want(k.defaultBinTicks, k.defaultPast));
        for (long at : new long[]{ny(2026, 11, 2, 9, 29), ny(2026, 11, 5, 12, 3), ny(2026, 11, 6, 16, 59), ny(2026, 11, 10, 3, 11)}) {
            MinuteTape cut = full.cutBefore(at + MIN);
            List<Profile> a = ProfileSet.build(cut, CLOCK, all, at, null);
            List<Profile> b = ProfileSet.build(cut, CLOCK, all, at, new ProfileSet.Cache());
            check(same(a, b), "cache changes nothing at " + at);
            // a finished period of the cut history equals the same period of the full history
            List<Profile> fullAt = ProfileSet.build(full, CLOCK, all, at, null);
            for (Profile p : a) {
                if (p.current()) continue;
                Profile q = fullAt.stream().filter(x -> x.kind() == p.kind() && x.startMs() == p.startMs()).findFirst().orElse(null);
                check(q != null && Arrays.equals(p.volumes(), q.volumes()) && p.pocRow() == q.pocRow(), "finished " + p.kind() + " same in full history");
            }
            for (Profile p : a) check(p.lastMinute() <= at, "no minute after the moment");
        }
    }

    private static boolean same(List<Profile> a, List<Profile> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Profile x = a.get(i), y = b.get(i);
            if (x.kind() != y.kind() || x.startMs() != y.startMs() || x.current() != y.current() || x.fromTicks() != y.fromTicks()
                    || !Arrays.equals(x.volumes(), y.volumes()) || x.pocRow() != y.pocRow() || x.valRow() != y.valRow() || x.vahRow() != y.vahRow())
                return false;
        }
        return true;
    }

    private static void finishedProfilesAreCachedTheCurrentIsNot() {
        long from = ny(2026, 11, 8, 18, 0), now = ny(2026, 11, 11, 10, 0);
        MinuteTape tape = MinuteTape.EMPTY.with(globex(from, now + MIN, 100_000, 9));
        var cache = new ProfileSet.Cache();
        List<Profile> first = ProfileSet.build(tape, CLOCK, want(Kind.DAY, 8, 5), now, cache);
        eq(2, cache.size(), "two finished days cached");
        // one more minute: the current day changes, the finished ones are the very same objects
        MinuteTape more = tape.with(new Minute[]{new Minute(now + MIN, 100_500, 100_400, 9999)});
        List<Profile> second = ProfileSet.build(more, CLOCK, want(Kind.DAY, 8, 5), now + MIN, cache);
        check(first.get(1) == second.get(1) && first.get(2) == second.get(2), "finished days reused");
        check(second.get(0).totalVolume() == first.get(0).totalVolume() + 9999, "current day grew by the new minute");
        Profile fresh = ProfileCalc.compute(Kind.DAY, second.get(0).startMs(), true, more, more.indexFrom(second.get(0).startMs()), more.size() - 1, 8);
        check(Arrays.equals(fresh.volumes(), second.get(0).volumes()), "current day = computed afresh");
    }

    // ---------------------------------------------------------------- prices, visibility, the tape
    private static void ticksFromFloatPrices() {
        eq(45001, MinuteTape.ticks(4500.1f, 0.1), "MGC 4500.1 (float) -> 45001 ticks");
        eq(122_545, MinuteTape.ticks(30636.25f, 0.25), "MNQ 30636.25 -> 122545 ticks");
        eq(-3, MinuteTape.ticks(-0.75, 0.25), "negative price");
        eq(45003, MinuteTape.ticks(4500.3f, 0.1), "MGC 4500.3 is 4500.2998 as float -> rounded, not cut, to 45003");
    }

    private static void visibilityByChartBars() {
        check(Kind.HOUR.shownOn(15) && !Kind.HOUR.shownOn(30), "hour: up to 15m");
        check(Kind.DAY.shownOn(240) && !Kind.DAY.shownOn(1440), "day: up to 4h");
        check(Kind.WEEK.shownOn(1440) && !Kind.WEEK.shownOn(10080), "week: up to 1d");
        check(Kind.MONTH.shownOn(10080) && !Kind.MONTH.shownOn(43200), "month: up to 1w");
        check(Kind.MONTH.shownOn(0) && Kind.HOUR.shownOn(-1), "tick/range bars: shown");
    }

    private static void tapeMerging() {
        long t = ny(2026, 11, 9, 10, 0);
        MinuteTape a = MinuteTape.EMPTY.with(new Minute[]{new Minute(t + 2 * MIN, 5, 1, 10), new Minute(t, 5, 1, 1), new Minute(t + MIN, 5, 1, 2)});
        eq(3, a.size(), "three minutes");
        check(a.time(0) == t && a.time(1) == t + MIN && a.time(2) == t + 2 * MIN, "sorted");
        MinuteTape b = a.with(new Minute[]{new Minute(t + MIN, 6, 2, 20), new Minute(t + 3 * MIN, 7, 3, 30)});
        eq(4, b.size(), "one replaced, one added");
        eq(20L, b.volume(1), "same start time -> replaced");
        eq(30L, b.volume(3), "newer appended");
        eq(3, a.size(), "the old tape is untouched");
        MinuteTape c = b.with(new Minute[]{new Minute(t + MIN, 6, 2, 21), new Minute(t + MIN, 6, 2, 22)});
        eq(22L, c.volume(1), "duplicates in one batch: the last wins");
        eq(4, c.size(), "still four");
        eq(2, c.dropBefore(t + 2 * MIN).size(), "drop older minutes");
        eq(1, c.cutBefore(t + MIN).size(), "cut");
        boolean threw = false;
        try { new Minute(t, 1, 2, 3); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "high below low refused");
    }

    private static void historyStartReachesBackFarEnough() {
        long now = ny(2026, 11, 11, 10, 0);
        Map<Kind, ProfileSet.Want> w = new EnumMap<>(Kind.class);
        w.put(Kind.MONTH, new ProfileSet.Want(40, 3));
        w.put(Kind.DAY, new ProfileSet.Want(8, 10));
        eq(CLOCK.monthStart(YearMonth.of(2026, 8)), ProfileSet.historyStart(CLOCK, w, now), "3 past months -> from August");
        Map<Kind, ProfileSet.Want> d = want(Kind.DAY, 8, 2);
        eq(ny(2026, 11, 8, 18, 0), ProfileSet.historyStart(CLOCK, d, now), "Wednesday, 2 past days -> Monday's open (Sun 18:00)");
        Map<Kind, ProfileSet.Want> mon = want(Kind.DAY, 8, 1);
        eq(ny(2026, 11, 5, 18, 0), ProfileSet.historyStart(CLOCK, mon, ny(2026, 11, 9, 10, 0)), "Monday, 1 past day -> Friday (Thu 18:00)");
        Map<Kind, ProfileSet.Want> wk = want(Kind.WEEK, 20, 2);
        eq(ny(2026, 10, 25, 18, 0), ProfileSet.historyStart(CLOCK, wk, now), "2 past weeks -> week of 26 Oct");
    }

    private static void negativeTicksFloorLikeTheTester() {
        // C# Math.Floor((double)a / b) and Java floorDiv agree on negative prices (spreads, adjusted history)
        MinuteTape tape = MinuteTape.EMPTY.with(new Minute[]{new Minute(0, -1, -9, 10)});
        Profile p = ProfileCalc.compute(Kind.DAY, 0, false, tape, 0, 0, 8);
        eq(-16, p.fromTicks(), "floor(-9/8) = -2 rows -> -16 ticks");
        eq(2, p.volumes().length, "rows -2 and -1");
    }
}
