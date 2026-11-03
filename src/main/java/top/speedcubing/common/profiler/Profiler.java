package top.speedcubing.common.profiler;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import top.speedcubing.common.database.Database;

/**
 * Lightweight always-on counters/timers for things spark's sampler can't attribute:
 * DB pool waits and per-caller connection hold time, login phases, cache hit rate, rank recalculation.
 * Thread-safe and allocation-free on the hot path except for the first use of a name.
 */
public final class Profiler {
    private static final Map<String, Stat> stats = new ConcurrentHashMap<>();
    private static volatile long since = System.currentTimeMillis();

    private Profiler() {
    }

    private static final class Stat {
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
    }

    private static Stat stat(String name) {
        return stats.computeIfAbsent(name, n -> new Stat());
    }

    public static void record(String name, long nanos) {
        Stat s = stat(name);
        s.count.increment();
        s.totalNanos.add(nanos);
        s.maxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void count(String name) {
        stat(name).count.increment();
    }

    /** try (Profiler.Timer t = Profiler.timer("login.server")) { ... } */
    public static Timer timer(String name) {
        return new Timer(name);
    }

    public static final class Timer implements AutoCloseable {
        private final String name;
        private final long start = System.nanoTime();

        private Timer(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            record(name, System.nanoTime() - start);
        }
    }

    /** Human-readable report since the last reset: timers sorted by total time, then counters, then DB pools. */
    public static String report(boolean reset) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        b.append(String.format("[Profiler] last %ds%n", (now - since) / 1000));

        List<Map.Entry<String, Stat>> entries = new ArrayList<>(stats.entrySet());
        entries.sort((x, y) -> Long.compare(y.getValue().totalNanos.sum(), x.getValue().totalNanos.sum()));
        for (Map.Entry<String, Stat> e : entries) {
            Stat s = e.getValue();
            long n = s.count.sum();
            long total = s.totalNanos.sum();
            if (n == 0)
                continue;
            if (total == 0)
                b.append(String.format("  %-48s count=%d%n", e.getKey(), n));
            else
                b.append(String.format("  %-48s count=%-7d avg=%.2fms max=%.2fms total=%.0fms%n",
                        e.getKey(), n, total / 1e6 / n, s.maxNanos.get() / 1e6, total / 1e6));
        }

        if (Database.dataSourceMap != null) {
            for (Map.Entry<String, HikariDataSource> e : Database.dataSourceMap.entrySet()) {
                HikariPoolMXBean pool = e.getValue().getHikariPoolMXBean();
                if (pool != null)
                    b.append(String.format("  pool %-18s active=%d idle=%d total=%d max=%d waiting=%d%n",
                            e.getKey(), pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections(),
                            e.getValue().getMaximumPoolSize(), pool.getThreadsAwaitingConnection()));
            }
        }

        if (reset) {
            stats.clear();
            since = now;
        }
        return b.toString();
    }
}
