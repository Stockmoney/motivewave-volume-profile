package volume_profile.core;

/**
 * The four profiles, as in Futures Tester (D-044): month, week, CME day and hour.
 *
 * <p>Row height (ticks) and the visibility rule are the tester's: a profile is worth drawing only on a chart whose
 * bars are shorter than its period - hour up to 15-minute bars, day up to 4 hours, week up to a day, month up to
 * a week.
 */
public enum Kind {
    MONTH('M', 40, 3, 7 * 24 * 60),
    WEEK('W', 20, 8, 24 * 60),
    DAY('D', 8, 10, 4 * 60),
    HOUR('H', 4, 24, 15);

    public final char letter;
    /** Row height in ticks by default (MNQ: month 10 points, week 5, day 2, hour 1). */
    public final int defaultBinTicks;
    /** How many finished periods are drawn besides the current one, by default. */
    public final int defaultPast;
    /** The longest chart bar, in minutes, on which this profile is shown. */
    public final int maxChartMinutes;

    Kind(char letter, int defaultBinTicks, int defaultPast, int maxChartMinutes) {
        this.letter = letter;
        this.defaultBinTicks = defaultBinTicks;
        this.defaultPast = defaultPast;
        this.maxChartMinutes = maxChartMinutes;
    }

    /**
     * @param chartBarMinutes the chart's bar length in minutes; 0 or less when the bars are not time bars
     *                        (tick, range, renko), where the rule does not apply and the profile is shown
     */
    public boolean shownOn(int chartBarMinutes) {
        return chartBarMinutes <= 0 || chartBarMinutes <= maxChartMinutes;
    }
}
