package top.speedcubing.common;

import java.util.Timer;
import java.util.TimerTask;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.database.DatabaseData;
import top.speedcubing.common.events.CubingTickEvent;
import top.speedcubing.common.io.RedisManager;
import top.speedcubing.lib.utils.SQL.SQLConnection;

public class CubingTick {
    public static Timer tickTimer;
    public static int tick = 0;

    public static void init() {
        tickTimer = new Timer("Cubing-Tick-Thread");
        tickTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                try (SQLConnection cubing = Database.getCubing()) {
                    DatabaseData.champs.clear();
                    cubing.select("id").from("champ").executeResult().forEach(r -> r.forEach(f -> DatabaseData.champs.add(f.getInt())));
                    // Read total player count from Redis (written each tick by each proxy).
                    // This replaces the old SUM(onlinecount) FROM proxies DB query, giving
                    // a real-time value without a DB round-trip on every tick.
                    java.util.List<String> proxyCounts = RedisManager.hvals("sc:proxycounts");
                    if (!proxyCounts.isEmpty()) {
                        DatabaseData.onlineCount = proxyCounts
                                .stream()
                                .mapToInt(v -> { try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return 0; } })
                                .sum();
                    } else {
                        Integer dbTotal;
                        try (SQLConnection system = Database.getSystem()) {
                            dbTotal = system
                                    .select("SUM(onlinecount)")
                                    .from("proxies")
                                    .executeResult()
                                    .getInt();
                        }
                        DatabaseData.onlineCount = dbTotal == null ? 0 : dbTotal;
                    }
                    tick++;
                    CubingTickEvent event = new CubingTickEvent(tick);
                    event.call();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 1000);
    }
}
