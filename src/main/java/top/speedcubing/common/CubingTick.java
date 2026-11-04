package top.speedcubing.common;

import top.speedcubing.common.database.Database;
import top.speedcubing.common.database.DatabaseData;
import top.speedcubing.common.events.ChampsChangedEvent;
import top.speedcubing.common.events.CubingTickEvent;
import top.speedcubing.common.profiler.Profiler;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class CubingTick {
    public static Timer tickTimer;
    public static int tick = 0;
    private static int reportTick = 0;

    public static void init() {
        tickTimer = new Timer("Cubing-Tick-Thread");
        tickTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                long start = System.nanoTime();
                try (SQLConnection cubing = Database.getCubing();
                     SQLConnection system = Database.getSystem()) {
                    Set<Integer> champs = new HashSet<>();
                    cubing.select("id").from("champ").executeResult().forEach(r -> r.forEach(f -> champs.add(f.getInt())));
                    Set<Integer> previous = DatabaseData.champs;
                    DatabaseData.champs = Collections.unmodifiableSet(champs);

                    // gained or lost champ since the last tick
                    Set<Integer> changed = new HashSet<>(champs);
                    changed.addAll(previous);
                    Set<Integer> unchanged = new HashSet<>(champs);
                    unchanged.retainAll(previous);
                    changed.removeAll(unchanged);
                    if (!changed.isEmpty()) {
                        new ChampsChangedEvent(Collections.unmodifiableSet(changed)).call();
                    }

                    SQLResult result = system.select("SUM(onlinecount)").from("proxies").executeResult();
                    if (!result.isEmpty() && result.getInt() != null) {
                        DatabaseData.onlineCount = result.getInt();
                    }
                    tick++;
                    Profiler.record("tick.common.db", System.nanoTime() - start);
                    CubingTickEvent event = new CubingTickEvent(tick);
                    event.call();
                } catch (Throwable e) { // anything escaping run() cancels the timer for good
                    e.printStackTrace();
                }
                // whole tick including every service's CubingTickEvent handlers
                Profiler.record("tick.common", System.nanoTime() - start);
                if (++reportTick % 300 == 0) // every 5 minutes, independent of DB failures
                    CommonLib.logger.info(Profiler.report(true));
            }
        }, 0, 1000);
    }
}
