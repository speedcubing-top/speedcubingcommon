package top.speedcubing.common;

import top.speedcubing.common.database.Database;
import top.speedcubing.common.database.DatabaseData;
import top.speedcubing.common.events.CubingTickEvent;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;

import java.util.Timer;
import java.util.TimerTask;

public class CubingTick {
    public static Timer tickTimer;
    public static int tick = 0;

    public static void init() {
        tickTimer = new Timer("Cubing-Tick-Thread");
        tickTimer.schedule(new TimerTask() {

            @Override
            public void run() {
                try (SQLConnection cubing = Database.getCubing();
                     SQLConnection system = Database.getSystem()) {
                    DatabaseData.champs.clear();
                    cubing.select("id").from("champ").executeResult().forEach(r -> r.forEach(f -> DatabaseData.champs.add(f.getInt())));

                    SQLResult result = system.select("SUM(onlinecount)").from("proxies").executeResult();
                    if (!result.isEmpty()) {
                        DatabaseData.onlineCount = result.getInt();
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
