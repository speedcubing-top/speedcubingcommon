package top.speedcubing.common.configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import top.speedcubing.common.CommonLib;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.events.ConfigReloadEvent;
import top.speedcubing.common.rank.RankLoader;
import top.speedcubing.common.server.MinecraftProxy;
import top.speedcubing.common.server.MinecraftServer;
import top.speedcubing.lib.eventbus.CubingEventHandler;

public class ServerConfig {
    private JsonObject config;
    private String configPath;
    private static ServerConfig instance;

    public ServerConfig() {
        instance = this;
    }

    public static JsonObject getConfig() {
        return instance.config;
    }

    public void reload(String path, boolean init) {
        configPath = path;
        reload(init);
    }

    public void reload(boolean init) {
        try {
            CommonLib.logger.info("loading common config");
            config = loadConfig();

            if (init) {
                Database.connect();
                top.speedcubing.common.io.RedisManager.connect(config.getAsJsonObject("redis"));
            } else {
                Database.reloadDataSourceConfig();
                top.speedcubing.common.io.RedisManager.reload(config.getAsJsonObject("redis"));
            }

            RankLoader.loadRanks();
            MinecraftServer.loadServers();
            MinecraftProxy.loadProxies();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private JsonObject loadConfig() throws Exception {
        String configJson = System.getenv("SC_SERVER_CONFIG_JSON");
        if (configJson != null && !configJson.isBlank()) {
            CommonLib.logger.info("loading common config from SC_SERVER_CONFIG_JSON");
            return JsonParser.parseString(configJson).getAsJsonObject();
        }

        if ("env".equalsIgnoreCase(System.getenv("SC_CONFIG_SOURCE"))) {
            CommonLib.logger.info("loading common config from environment");
            return buildEnvConfig();
        }

        if (configPath != null && Files.isReadable(Path.of(configPath))) {
            try (FileReader reader = new FileReader(configPath)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }

        CommonLib.logger.info("common config file is not readable; falling back to environment");
        return buildEnvConfig();
    }

    private JsonObject buildEnvConfig() {
        JsonObject root = new JsonObject();

        JsonObject database = new JsonObject();
        database.addProperty("url", env("SC_DB_URL", "jdbc:mysql://" + env("SC_DB_HOST", "mariadb") + ":" + env("SC_DB_PORT", "3306") + "/%db%?useSSL=false&autoReconnect=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"));
        database.addProperty("user", env("SC_DB_USER", "cubing"));
        database.addProperty("password", env("SC_DB_PASSWORD", "cubing"));

        JsonObject hikaricp = new JsonObject();
        hikaricp.addProperty("connectionTimeout", longEnv("SC_DB_CONNECTION_TIMEOUT", 30000L));
        hikaricp.addProperty("validationTimeout", longEnv("SC_DB_VALIDATION_TIMEOUT", 5000L));
        hikaricp.addProperty("maxLifetime", longEnv("SC_DB_MAX_LIFETIME", 1800000L));
        hikaricp.addProperty("maxPoolSize", intEnv("SC_DB_MAX_POOL_SIZE", 10));
        hikaricp.addProperty("minIdle", intEnv("SC_DB_MIN_IDLE", 2));
        database.add("hikaricp", hikaricp);
        root.add("database", database);

        JsonObject redis = new JsonObject();
        redis.addProperty("host", env("SC_REDIS_HOST", "redis"));
        redis.addProperty("port", intEnv("SC_REDIS_PORT", 6379));
        redis.addProperty("password", env("SC_REDIS_PASSWORD", "cubing-redis"));
        root.add("redis", redis);

        root.addProperty("leftcpslimit", intEnv("SC_LEFT_CPS_LIMIT", 50));
        root.addProperty("rightcpslimit", intEnv("SC_RIGHT_CPS_LIMIT", 50));
        root.addProperty("removeLogs", boolEnv("SC_REMOVE_LOGS", false));

        addEmptyArray(root, "filteredtext");
        addEmptyArray(root, "spigotblockedlog");
        addEmptyArray(root, "blockedmod");
        addEmptyArray(root, "blacklistedmod");
        addEmptyArray(root, "onlinecrash");
        addEmptyArray(root, "blockedtext");
        addEmptyArray(root, "bungeeblockedlog");
        addEmptyArray(root, "blockedcidr");
        addEmptyArray(root, "ipwhitelist");
        addEmptyArray(root, "iplimitwhitelist");
        addEmptyArray(root, "blockedcommand");
        addEmptyArray(root, "allowedURL");

        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("delay", intEnv("SC_BROADCAST_DELAY", 300000));
        broadcast.addProperty("message", env("SC_BROADCAST_MESSAGE", "Welcome to speedcubing.top!"));
        root.add("broadcast", broadcast);

        JsonObject defaultKbffa = new JsonObject();
        defaultKbffa.addProperty("x", 2023);
        defaultKbffa.addProperty("y", 70);
        defaultKbffa.addProperty("z", 1225);
        defaultKbffa.addProperty("yaw", -45);
        defaultKbffa.addProperty("holox", 2026.5);
        defaultKbffa.addProperty("holoz", 1226.5);
        defaultKbffa.addProperty("spawnproty", 144);
        root.add("defaultkbffa", defaultKbffa);

        JsonObject reduceKb = new JsonObject();
        reduceKb.addProperty("hor", 1);
        reduceKb.addProperty("ver", 0.35);
        reduceKb.addProperty("airhor", 1.28);
        reduceKb.addProperty("airver", 0.363);
        reduceKb.addProperty("reach", 3.5);
        root.add("reducekb", reduceKb);

        root.add("clutchkb", JsonParser.parseString("{\"low\":[0.65,0.392,0.4,0.375],\"mid\":[1.205,0.392,0.55,0.375],\"diff\":[1.36,0.392,0.78,0.375],\"high\":[1.55,0.392,1,0.375],\"extreme\":[2.4,0.392,1.35,0.375]}").getAsJsonObject());

        return root;
    }

    private static void addEmptyArray(JsonObject object, String key) {
        object.add(key, JsonParser.parseString("[]").getAsJsonArray());
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long longEnv(String key, long fallback) {
        try {
            return Long.parseLong(env(key, Long.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    @CubingEventHandler(priority = 10)
    public void configReloadEvent(ConfigReloadEvent e) {
        reload(false);
    }
}
