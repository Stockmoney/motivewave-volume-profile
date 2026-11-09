package volume_profile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.motivewave.platform.sdk.common.Bar;
import com.motivewave.platform.sdk.common.BarSize;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.Settings;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.ColorDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import volume_profile.audit.AuditLog;
import volume_profile.core.Kind;
import volume_profile.core.MinuteTape;
import volume_profile.core.MinuteTape.Minute;
import volume_profile.core.Profile;
import volume_profile.core.ProfileSet;
import volume_profile.core.SessionClock;

/**
 * Volume Profile M/W/D/H
 * ----------------------
 * The volume profiles of Futures Tester (D-044) on a MotiveWave chart: month, week, CME day and hour, each with its
 * own switch, colour, transparency, row height and number of past periods. Volume by price is built from 1-minute
 * bars that the indicator asks the platform for itself (so a month is right whatever the chart's own bars are);
 * the arithmetic lives in {@code core} and is the tester's, row for row.
 *
 * <p>Display only: a plain Study, no order code. Only the public SDK is used, so a MotiveWave update that renames its
 * internal classes does not break it. One figure draws everything; it takes no clicks (the drawings under it stay
 * selectable) and lies under the candles.
 *
 * <p>Threads: the platform calls {@link #precalculate} and the settings callbacks on its own threads and draws on the
 * UI thread. The minutes are loaded on the indicator's own thread; every result is an immutable snapshot put into a
 * volatile field, and the drawing only reads the latest one.
 */
@StudyHeader(
        namespace = "com.zentrader",
        id = "VOLUME_PROFILE_MWDH",
        rb = "volume_profile.nls.strings",
        name = "STUDY_NAME",
        desc = "STUDY_DESC",
        menu = "MENU_GENERAL",
        overlay = true,
        studyOverlay = true,
        requiresBarUpdates = false
)
public class AlexVolumeProfile extends Study {

    static final String VERSION = "0.1.2";

    private static final long MIN = 60_000L;
    private static final long REFRESH_MS = 5_000L;          // how often the newest closed minutes are fetched
    private static final long RETRY_MS = 60_000L;           // after a failed history request
    private static final int FULL_TRIES = 5;                // a history that comes back short is asked for again ...
    private static final long FULL_RETRY_MS = 30_000L;      // ... this often
    private static final long CLOSE_GRACE_MS = 2_000L;      // a minute is taken 2 s after it ends (late trades)
    private static final long REFETCH_MS = 20 * 60_000L;    // the newest minutes are asked again on every update
    private static final float[] DASH = {5f, 4f};

    // setting keys: "<kind letter>.<name>", e.g. "D.show"
    private static String key(Kind k, String name) { return k.letter + "." + name; }
    private static final String WIDTH = "width";
    private static final String RULE = "rule";
    private static final String LINES = "lines";

    private static final Map<Kind, Color> DEFAULT_COLOR = Map.of(
            Kind.MONTH, new Color(0xa7, 0x8b, 0xfa), Kind.WEEK, new Color(0x38, 0xbd, 0xf8),
            Kind.DAY, new Color(0xf5, 0x9e, 0x0b), Kind.HOUR, new Color(0x22, 0xc5, 0x5e));

    /** Everything the settings say, read once per change. */
    record Config(Map<Kind, ProfileSet.Want> wants, Map<Kind, Color> colors, Map<Kind, Integer> transparency,
                  int widthPercent, boolean rule, boolean lines) {
        String text() {
            var sb = new StringBuilder();
            for (var e : wants.entrySet())
                sb.append(e.getKey().letter).append("(rows ").append(e.getValue().binTicks()).append(" ticks, past ")
                        .append(e.getValue().past()).append(") ");
            return (sb.length() == 0 ? "no profile switched on " : sb.toString()) + "width=" + widthPercent + "% rule=" + rule + " lines=" + lines;
        }
    }

    /** What the figure draws: the profiles and the settings and tick size they were made with. */
    record Snapshot(List<Profile> profiles, Config config, double tickSize) { }

    private final SessionClock clock = SessionClock.newYork();
    private final ProfileSet.Cache cache = new ProfileSet.Cache();
    private final Object work = new Object();               // one load / rebuild at a time
    private final ProfilesFigure figure = new ProfilesFigure();

    private volatile Config config;
    private volatile Instrument instrument;
    private volatile Snapshot snapshot = new Snapshot(List.of(), null, 0);
    private volatile ScheduledExecutorService exec;

    // owned by the worker thread (under `work`)
    private MinuteTape tape = MinuteTape.EMPTY;
    private long fullNeed = Long.MIN_VALUE;                 // the start the last full loads were asked for
    private int fullTries;                                  // how many full loads for that start
    private long fullAt;                                    // when the last one was made
    private long lastFail = 0;
    private Config builtWith;
    private long builtTapeLast = Long.MIN_VALUE;
    private int builtTapeSize = -1;
    private String builtNowKeys = "";
    private int fetches;
    private long lastLiveLog;
    private final Set<String> logged = new HashSet<>();
    private final Map<String, String> currentLogged = new HashMap<>();
    private final Map<String, Long> currentLoggedAt = new HashMap<>();

    @Override
    public void initialize(Defaults defaults) {
        var sd = createSD();
        var tab = sd.addTab(get("TAB_PROFILES"));
        for (Kind k : Kind.values()) {
            var g = tab.addGroup(get(switch (k) {
                case MONTH -> "GRP_MONTH"; case WEEK -> "GRP_WEEK"; case DAY -> "GRP_DAY"; case HOUR -> "GRP_HOUR";
            }));
            g.addRow(new BooleanDescriptor(key(k, "show"), get("LBL_SHOW_" + k.letter), k == Kind.DAY),
                    new ColorDescriptor(key(k, "color"), get("LBL_COLOR_" + k.letter), DEFAULT_COLOR.get(k)));
            g.addRow(new IntegerDescriptor(key(k, "transparency"), get("LBL_TRANSPARENCY"), 70, 0, 95, 5),
                    new IntegerDescriptor(key(k, "bin"), get("LBL_BIN"), k.defaultBinTicks, 1, 10_000, 1));
            g.addRow(new IntegerDescriptor(key(k, "past"), get("LBL_PAST"), k.defaultPast, 0, 120, 1));
        }
        var display = sd.addTab(get("TAB_DISPLAY"));
        var draw = display.addGroup(get("GRP_DRAW"));
        draw.addRow(new IntegerDescriptor(WIDTH, get("LBL_WIDTH"), 60, 5, 100, 5));
        draw.addRow(new BooleanDescriptor(RULE, get("LBL_RULE"), true));
        draw.addRow(new BooleanDescriptor(LINES, get("LBL_LINES"), true));

        sd.addQuickSettings(key(Kind.MONTH, "show"), key(Kind.WEEK, "show"), key(Kind.DAY, "show"), key(Kind.HOUR, "show"),
                key(Kind.MONTH, "color"), key(Kind.WEEK, "color"), key(Kind.DAY, "color"), key(Kind.HOUR, "color"), WIDTH);
    }

    private Config readConfig() {
        Settings s = getSettings();
        Map<Kind, ProfileSet.Want> wants = new EnumMap<>(Kind.class);
        Map<Kind, Color> colors = new EnumMap<>(Kind.class);
        Map<Kind, Integer> transparency = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            colors.put(k, s.getColor(key(k, "color"), DEFAULT_COLOR.get(k)));
            transparency.put(k, clamp(s.getInteger(key(k, "transparency"), 70), 0, 95));
            if (s.getBoolean(key(k, "show"), k == Kind.DAY))
                wants.put(k, new ProfileSet.Want(clamp(s.getInteger(key(k, "bin"), k.defaultBinTicks), 1, 10_000),
                        clamp(s.getInteger(key(k, "past"), k.defaultPast), 0, 120)));
        }
        return new Config(wants, colors, transparency, clamp(s.getInteger(WIDTH, 60), 5, 100),
                s.getBoolean(RULE, true), s.getBoolean(LINES, true));
    }

    private static int clamp(Integer v, int lo, int hi) {
        int x = v == null ? lo : v;
        return Math.max(lo, Math.min(hi, x));
    }

    // ==================== platform callbacks ====================
    @Override
    protected void precalculate(DataContext ctx) {
        config = readConfig();
        instrument = ctx.getInstrument();
        if (!getFigures().contains(figure)) {
            figure.setUnderlay(true);
            addFigure(figure);
        }
        DataSeries ds = ctx.getDataSeries();
        AuditLog.log("SETUP", "v" + VERSION + " " + (instrument == null ? "?" : instrument.getSymbol())
                + " tick=" + (instrument == null ? "?" : instrument.getTickSize())
                + " chart=" + (ds == null || ds.getBarSize() == null ? "?" : ds.getBarSize()) + " " + config.text()
                + " #" + Integer.toHexString(System.identityHashCode(this) & 0xffff));
        start().execute(this::refresh);
    }

    @Override
    public void onSettingsUpdated(DataContext ctx) {
        config = readConfig();
        AuditLog.log("SETTINGS", config.text());
        start().execute(this::refresh);
        super.onSettingsUpdated(ctx);
    }

    @Override
    public void destroy() {
        var e = exec;
        exec = null;
        if (e != null) e.shutdownNow();
        AuditLog.log("LIFECYCLE", "destroyed #" + Integer.toHexString(System.identityHashCode(this) & 0xffff));
        super.destroy();
    }

    private synchronized ScheduledExecutorService start() {
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VolumeProfile-" + Integer.toHexString(System.identityHashCode(this) & 0xffff));
                t.setDaemon(true);
                return t;
            });
            exec.scheduleWithFixedDelay(this::refresh, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS);
        }
        return exec;
    }

    // ==================== minutes: load, keep fresh, build ====================
    /** Runs on the indicator's own thread only. Never throws: the scheduler would stop. */
    private void refresh() {
        try {
            synchronized (work) {
                Config c = config;
                Instrument in = instrument;
                if (c == null || in == null) return;
                long now = System.currentTimeMillis();
                if (!c.wants().isEmpty()) {
                    long need = ProfileSet.historyStart(clock, c.wants(), now);
                    boolean short_ = tape.size() == 0 || tape.first() > need + ProfileSet.SLACK_MS;
                    if (short_ && need != fullNeed) { fullNeed = need; fullTries = 0; }
                    if (short_ && fullTries < FULL_TRIES && (fullTries == 0 || now - fullAt >= FULL_RETRY_MS)) {
                        // 9 Nov 2026 (MGCZ6): after the month profile was switched on, the platform answered the longer
                        // request with no bars at all; a fresh instance got all of them a minute later - so ask again
                        load(in, need, now, true);
                        if (fullTries == FULL_TRIES && tape.first() > need + ProfileSet.SLACK_MS)
                            AuditLog.log("DATA", "history still starts " + fmt(tape.first()) + " after " + FULL_TRIES
                                    + " requests: periods before it stay out");
                    } else if (tape.size() > 0 && now >= tape.last() + 2 * MIN + CLOSE_GRACE_MS && now - lastFail >= RETRY_MS) {
                        // a new minute has ended: fetch it, and the last REFETCH_MS again - the platform calls its newest
                        // bars "not complete" for ~15 minutes and may still correct them, so a later answer replaces them
                        load(in, tape.last() - REFETCH_MS, now, false);
                    }
                }
                rebuild(c, in, now);
            }
        } catch (Throwable t) {
            AuditLog.log("ERROR", "refresh: " + t);
        }
    }

    private void load(Instrument in, long from, long now, boolean full) {
        long t0 = System.nanoTime();
        List<?> bars;
        try {
            bars = in.getBars(from, now, BarSize.minute(1), false);
        } catch (Exception e) {
            lastFail = System.currentTimeMillis();
            if (full) { fullTries++; fullAt = lastFail; }
            AuditLog.log("ERROR", "1-minute history " + fmt(from) + " .. " + fmt(now) + ": " + e);
            return;
        }
        double tick = in.getTickSize();
        List<Minute> add = new ArrayList<>();
        int incomplete = 0, forming = 0;
        for (Object o : bars) {
            if (!(o instanceof Bar b)) continue;
            // a minute counts once its time is over (9 Nov 2026: the platform flags its last ~15 one-minute bars as
            // not complete although they are over, so isComplete() made the current profile lag by up to 15 minutes)
            if (b.getStartTime() + MIN + CLOSE_GRACE_MS > now) { forming++; continue; }
            if (!b.isComplete()) incomplete++;
            if (b.getHigh() < b.getLow() || b.getVolume() < 0) continue;
            add.add(new Minute(b.getStartTime(), MinuteTape.ticks(b.getHigh(), tick), MinuteTape.ticks(b.getLow(), tick), b.getVolume()));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        MinuteTape before = tape;
        tape = tape.with(add.toArray(new Minute[0]));
        if (full) { fullTries++; fullAt = System.currentTimeMillis(); }
        fetches++;
        if (full || fetches <= 5 || ms > 1000 || add.isEmpty() && fetches % 60 == 0)
            AuditLog.log("DATA", (full ? "history" : "update") + " asked " + fmt(from) + " .. " + fmt(now) + ": " + bars.size()
                    + " bars (" + forming + " still forming, " + incomplete + " over but flagged not complete) in " + ms + " ms; tape " + before.size() + " -> " + tape.size()
                    + (tape.size() > 0 ? " minutes, " + fmt(tape.first()) + " .. " + fmt(tape.last()) : "")
                    + (full && tape.size() > 0 && tape.first() > from + ProfileSet.SLACK_MS
                        ? " - WARNING: the platform's 1-minute history starts after " + fmt(from) + ": older periods are left out, not guessed" : ""));
    }

    private void rebuild(Config c, Instrument in, long now) {
        var sb = new StringBuilder();
        for (Kind k : c.wants().keySet()) sb.append(clock.key(k, now)).append(',');
        String nowKeys = sb.toString();
        if (c == builtWith && tape.size() == builtTapeSize && tape.last() == builtTapeLast && nowKeys.equals(builtNowKeys)) return;
        long t0 = System.nanoTime();
        List<Profile> profiles = ProfileSet.build(tape, clock, c.wants(), now, cache);
        double tick = in.getTickSize();
        snapshot = new Snapshot(List.copyOf(profiles), c, tick);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (c != builtWith || ms > 500 || now - lastLiveLog >= 10 * MIN) {
            lastLiveLog = now;
            AuditLog.log("BUILD", profiles.size() + " profiles from " + tape.size() + " minutes in " + ms + " ms; newest minute "
                    + fmt(tape.last()) + " (now " + fmt(now) + ")");
        }
        builtWith = c;
        builtTapeSize = tape.size();
        builtTapeLast = tape.last();
        builtNowKeys = nowKeys;
        journal(profiles, tick, now);
        notifyRedraw();
    }

    /** Each finished profile once; the current ones when their levels change, at most every 15 minutes. */
    private void journal(List<Profile> profiles, double tick, long now) {
        for (Profile p : profiles) {
            String id = p.kind().letter + ":" + p.binTicks() + ":" + p.startMs();
            String levels = String.format(Locale.ROOT, "POC=%s VAH=%s VAL=%s", px(p.poc(tick)), px(p.vah(tick)), px(p.val(tick)));
            String line = String.format(Locale.ROOT, "%s %s opened %s minutes %s .. %s (%d) volume %d rows %d x %d ticks from %s %s",
                    p.kind(), p.current() ? "CURRENT" : "closed", fmt(p.startMs()), fmt(p.firstMinute()), fmt(p.lastMinute()),
                    p.minutes(), p.totalVolume(), p.volumes().length, p.binTicks(), px(p.from(tick)), levels);
            if (!p.current()) {
                if (logged.add(id + ":" + p.lastMinute())) AuditLog.log("PROFILE", line);
            } else {
                Long at = currentLoggedAt.get(id);
                if (!levels.equals(currentLogged.get(id)) && (at == null || now - at >= 15 * MIN)) {
                    currentLogged.put(id, levels);
                    currentLoggedAt.put(id, now);
                    AuditLog.log("PROFILE", line);
                }
            }
        }
    }

    private static String px(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private static String fmt(long ms) {
        if (ms == Long.MAX_VALUE || ms == Long.MIN_VALUE) return "-";
        return Instant.ofEpochMilli(ms).atZone(SessionClock.NEW_YORK).toLocalDateTime().withNano(0).toString();
    }

    /** Minutes of one chart bar; 0 when the chart is not made of time bars (tick, range, renko...). */
    private static int chartMinutes(BarSize bs) {
        if (bs == null) return 0;
        try {
            if (bs.getType() != com.motivewave.platform.sdk.common.Enums$BarSizeType.LINEAR) return 0;
            return Math.max(0, bs.getIntervalMinutes());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ==================== drawing ====================
    /**
     * Under the candles: each profile's histogram from the period's first bar to the right (rows of the 70 % value
     * area fuller), the POC solid and VAH/VAL dashed across the period, in the period's colour and transparency -
     * as Futures Tester draws them.
     */
    private final class ProfilesFigure extends Figure {

        @Override public boolean contains(double x, double y, DrawContext ctx) { return false; }     // never takes a click

        @Override public boolean isVisible(DrawContext ctx) { return !snapshot.profiles().isEmpty(); }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            Snapshot snap = snapshot;
            Config c = snap.config();
            if (c == null || snap.profiles().isEmpty()) return;
            DataSeries ds = ctx.getDataContext().getDataSeries();
            if (ds == null || ds.size() == 0) return;
            int chartMin = chartMinutes(ds.getBarSize());
            Rectangle bounds = ctx.getBounds();
            double barWidth = Math.max(1, ctx.getBarWidthAsDouble());
            double tick = snap.tickSize();
            Stroke oldStroke = gc.getStroke();
            for (Profile p : snap.profiles()) {
                if (!c.wants().containsKey(p.kind())) continue;
                if (c.rule() && !p.kind().shownOn(chartMin)) continue;
                double xa = barX(ctx, ds, p.firstMinute()), xb = barX(ctx, ds, p.lastMinute());
                if (Double.isNaN(xa) || Double.isNaN(xb)) continue;
                double left = xa - barWidth / 2, right = xb + barWidth / 2;
                if (right < bounds.x || left > bounds.x + bounds.width) continue;
                double span = Math.max(4, right - left) * c.widthPercent() / 100.0;
                double max = p.maxVolume();
                if (max <= 0) continue;
                Color base = c.colors().get(p.kind());
                float alpha = (100 - c.transparency().get(p.kind())) / 100f;
                Color inside = withAlpha(base, alpha), outside = withAlpha(base, alpha * 0.5f);
                double from = p.from(tick), bin = p.bin(tick);
                double[] v = p.volumes();
                for (int i = 0; i < v.length; i++) {
                    if (v[i] <= 0) continue;
                    double top = ctx.translateValueD(from + (i + 1) * bin), bottom = ctx.translateValueD(from + i * bin);
                    if (bottom < bounds.y || top > bounds.y + bounds.height) continue;
                    gc.setColor(i >= p.valRow() && i <= p.vahRow() ? inside : outside);
                    gc.fill(new Rectangle2D.Double(left, top, v[i] / max * span, Math.max(1, bottom - top - 0.5)));
                }
                if (c.lines()) {
                    gc.setColor(withAlpha(base, Math.min(1f, alpha + 0.4f)));
                    float w = p.kind() == Kind.HOUR ? 1f : 1.5f;
                    line(gc, ctx, left, right, p.poc(tick), new BasicStroke(w));
                    Stroke dashed = new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, DASH, 0f);
                    line(gc, ctx, left, right, p.vah(tick), dashed);
                    line(gc, ctx, left, right, p.val(tick), dashed);
                }
            }
            gc.setStroke(oldStroke);
        }

        private void line(Graphics2D gc, DrawContext ctx, double left, double right, double price, Stroke stroke) {
            double y = ctx.translateValueD(price);
            gc.setStroke(stroke);
            gc.draw(new Line2D.Double(left, y, right, y));
        }

        /** The x of the chart bar holding this minute (the bar's centre). */
        private double barX(DrawContext ctx, DataSeries ds, long minute) {
            int i = ds.findIndex(minute);
            if (i < 0 || i >= ds.size()) return ctx.translateTimeD(minute);
            return ctx.translateTimeD(ds.getStartTime(i));
        }
    }

    private static Color withAlpha(Color c, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
