package volume_profile.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Which day, week, month and hour a minute belongs to, in exchange time (America/New_York), by the CME Globex
 * rule that Futures Tester uses: a trading day opens at 18:00 the evening before and ends at 17:00, so everything
 * from 18:00 on counts to the NEXT date. Sunday 18:00 opens Monday and its week; the evening of a month's last
 * day belongs to the next month. Hours are counted from the session open (18:00, 19:00, ...), as the tester does.
 *
 * <p>Times are absolute instants, so the trader's own time zone never matters, and daylight saving is handled by
 * the zone rules. (Copied from Median Price, where it is tested and in use; copies are the projects' practice.)
 */
public final class SessionClock {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int GLOBEX_START_HOUR = 18;
    private static final long HOUR_MS = 3_600_000L;

    private final ZoneId zone;

    public SessionClock(ZoneId zone) {
        this.zone = zone;
    }

    public static SessionClock newYork() { return new SessionClock(NEW_YORK); }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    /** CME trade date: the calendar date, or the next one from 18:00 on. */
    public LocalDate tradeDate(long epochMillis) {
        LocalDateTime t = local(epochMillis);
        return t.getHour() >= GLOBEX_START_HOUR ? t.toLocalDate().plusDays(1) : t.toLocalDate();
    }

    /** The session of a trade date opens at 18:00 the evening before (Sunday evening for a Monday). */
    public long dayStart(LocalDate tradeDate) {
        return tradeDate.minusDays(1).atTime(GLOBEX_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** The Monday of a trade date's week. */
    public static LocalDate weekMonday(LocalDate tradeDate) {
        return tradeDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Start of a month's trading: the evening before its first Monday - Friday date. */
    public long monthStart(YearMonth month) {
        LocalDate d = month.atDay(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return dayStart(d);
    }

    /** The key of the period a minute belongs to; equal keys = the same period. */
    public long key(Kind kind, long epochMillis) {
        LocalDate date = tradeDate(epochMillis);
        return switch (kind) {
            case MONTH -> { YearMonth m = YearMonth.from(date); yield m.getYear() * 12L + m.getMonthValue(); }
            case WEEK -> weekMonday(date).toEpochDay();
            case DAY -> date.toEpochDay();
            case HOUR -> hourStart(epochMillis);
        };
    }

    /** When the period with this key opened. */
    public long start(Kind kind, long key) {
        return switch (kind) {
            case MONTH -> { long y = Math.floorDiv(key - 1, 12); yield monthStart(YearMonth.of((int) y, (int) (key - y * 12))); }
            case WEEK, DAY -> dayStart(LocalDate.ofEpochDay(key));
            case HOUR -> key;
        };
    }

    /** The hour a minute belongs to, counted from its session's 18:00 open. */
    public long hourStart(long epochMillis) {
        long open = dayStart(tradeDate(epochMillis));
        return open + Math.floorDiv(epochMillis - open, HOUR_MS) * HOUR_MS;
    }

    /**
     * When the period holding {@code epochMillis} began, going back {@code back} periods (0 = this one). Used to
     * know how far back the history must reach; hours and days step back by the clock, so over a weekend this
     * reaches further than strictly needed, never less.
     */
    public long startBack(Kind kind, long epochMillis, int back) {
        LocalDate date = tradeDate(epochMillis);
        return switch (kind) {
            case MONTH -> monthStart(YearMonth.from(date).minusMonths(back));
            case WEEK -> dayStart(weekMonday(date).minusWeeks(back));
            case DAY -> dayStart(tradingDaysBack(date, back));
            case HOUR -> hourStart(epochMillis) - back * HOUR_MS - 2 * 24 * HOUR_MS;    // a weekend in between
        };
    }

    private static LocalDate tradingDaysBack(LocalDate date, int back) {
        LocalDate d = date;
        for (int left = back; left > 0; ) {
            d = d.minusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) left--;
        }
        return d;
    }
}
