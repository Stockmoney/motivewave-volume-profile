package volume_profile.core;

/**
 * The volume profile of a run of minutes, exactly as Futures Tester computes it
 * ({@code src/FuturesTester.Web/Lab/VolumeProfiles.cs}, D-044): a minute's volume is spread evenly over the rows its
 * high-low range covers; POC = the fullest row (a tie goes to the row nearer the middle of the range); the value
 * area grows from the POC two rows at a time towards the side whose next two rows hold more volume (the upper side
 * on a tie) until it holds 70 % of the volume - the usual Market Profile rule.
 *
 * <p>The sums are made in the same order as the tester's, so equal minutes give equal numbers bit for bit (ties
 * included). The running period is always recomputed from its minutes, never patched, for the same reason.
 */
public final class ProfileCalc {

    public static final double VALUE_AREA_SHARE = 0.70;

    private ProfileCalc() { }

    /** Minutes [{@code first}, {@code last}] of the tape. */
    public static Profile compute(Kind kind, long startMs, boolean current, MinuteTape tape, int first, int last, int bin) {
        if (first > last) throw new IllegalArgumentException("no minutes");
        if (bin < 1) throw new IllegalArgumentException("bin " + bin);
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        long total = 0;
        for (int i = first; i <= last; i++) {
            low = Math.min(low, tape.low(i));
            high = Math.max(high, tape.high(i));
            total += tape.volume(i);
        }
        int lowRow = Math.floorDiv(low, bin);
        double[] volumes = new double[Math.floorDiv(high, bin) - lowRow + 1];
        for (int i = first; i <= last; i++) {
            int a = Math.floorDiv(tape.low(i), bin) - lowRow, b = Math.floorDiv(tape.high(i), bin) - lowRow;
            double share = (double) tape.volume(i) / (b - a + 1);
            for (int r = a; r <= b; r++) volumes[r] += share;
        }
        int[] va = valueArea(volumes);
        return new Profile(kind, startMs, tape.time(first), tape.time(last), current, lowRow * bin, bin, volumes,
                va[0], va[1], va[2], last - first + 1, total);
    }

    /** {POC, VAL row, VAH row}. */
    public static int[] valueArea(double[] volumes) {
        int n = volumes.length;
        double middle = (n - 1) / 2.0;
        int poc = 0;
        for (int r = 1; r < n; r++)
            if (volumes[r] > volumes[poc] || volumes[r] == volumes[poc] && Math.abs(r - middle) < Math.abs(poc - middle))
                poc = r;
        double total = 0;
        for (double v : volumes) total += v;                     // LINQ Sum() adds in order too
        double inside = volumes[poc];
        int lo = poc, hi = poc;
        while (inside < total * VALUE_AREA_SHARE && (lo > 0 || hi < n - 1)) {
            double up = hi < n - 1 ? volumes[hi + 1] + (hi + 2 < n ? volumes[hi + 2] : 0) : -1;
            double down = lo > 0 ? volumes[lo - 1] + (lo - 2 >= 0 ? volumes[lo - 2] : 0) : -1;
            if (up >= down) { inside += up; hi = Math.min(n - 1, hi + 2); }
            else { inside += down; lo = Math.max(0, lo - 2); }
        }
        return new int[]{poc, lo, hi};
    }
}
