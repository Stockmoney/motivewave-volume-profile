package volume_profile.core;

/**
 * One period's volume by price. {@code volumes[i]} is the volume of the row
 * [{@code fromTicks + i * binTicks}, + {@code binTicks}) ticks. POC is the fullest row, the value area the 70 %
 * around it (rows {@code valRow} .. {@code vahRow}).
 *
 * @param startMs     when the period opened (18:00 for a day, the hour for an hour)
 * @param firstMinute start of the first minute counted
 * @param lastMinute  start of the last minute counted
 * @param current     the period still running: it grows with every closed minute
 * @param minutes     how many minutes were counted
 * @param totalVolume their volume
 */
public record Profile(Kind kind, long startMs, long firstMinute, long lastMinute, boolean current,
                      int fromTicks, int binTicks, double[] volumes, int pocRow, int valRow, int vahRow,
                      int minutes, long totalVolume) {

    public double from(double tickSize) { return fromTicks * tickSize; }
    public double bin(double tickSize) { return binTicks * tickSize; }
    /** The middle of the POC row, as Futures Tester shows it. */
    public double poc(double tickSize) { return (fromTicks + (pocRow + 0.5) * binTicks) * tickSize; }
    /** The top of the value area's upper row. */
    public double vah(double tickSize) { return (fromTicks + (vahRow + 1L) * binTicks) * tickSize; }
    /** The bottom of the value area's lower row. */
    public double val(double tickSize) { return (fromTicks + (long) valRow * binTicks) * tickSize; }

    public double maxVolume() {
        double m = 0;
        for (double v : volumes) m = Math.max(m, v);
        return m;
    }
}
