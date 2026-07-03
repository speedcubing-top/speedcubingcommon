package top.speedcubing.common;

import java.util.logging.Logger;
import top.speedcubing.common.configuration.ServerConfig;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.io.RedisManager;
import top.speedcubing.common.namedb.NameDb;
import top.speedcubing.lib.eventbus.CubingEventManager;

public class CommonLib {
    public static Logger logger;

    public static void init(Logger logger) {
        CommonLib.logger = logger;
        init("/storage/server.json", logger);
    }

    public static void init(String configPath, Logger logger) {
        CommonLib.logger = logger;
        RedisManager.setLogger(logger);
        ServerConfig config = new ServerConfig();
        config.reload(configPath, true);
        CubingEventManager.registerListeners(config);
        CubingEventManager.registerListeners(new NameDb());
        CubingTick.init();
    }

    public static void shutdown() {
        CubingTick.tickTimer.cancel();
        Database.closeAllConnections();
        RedisManager.shutdown();
    }
}
