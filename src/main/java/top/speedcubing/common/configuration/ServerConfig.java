package top.speedcubing.common.configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.speedcubing.common.CommonLib;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.events.ConfigReloadEvent;
import top.speedcubing.common.rank.RankLoader;
import top.speedcubing.common.server.MinecraftServer;
import top.speedcubing.lib.eventbus.CubingEventHandler;

import java.io.FileReader;

public class ServerConfig {
    private JsonObject config;
    private String configPath;
    private static ServerConfig instance;

    public ServerConfig() {
        instance = this;
    }

    public static ServerConfig getInstance() {
        return instance;
    }

    public static JsonObject getConfig() {
        return instance.config;
    }

    public void reload(String path, boolean init) {
        configPath = path;
        reload(init);
    }

    public void reload() {
        reload(false);
    }

    private void reload(boolean init) {
        try (FileReader reader = new FileReader(configPath)) {
            CommonLib.logger.info("loading common config");
            config = JsonParser.parseReader(reader).getAsJsonObject();

            if (init) {
                Database.connect();
            } else
                Database.reloadDataSourceConfig();

            RankLoader.loadRanks();
            MinecraftServer.loadServers();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    @CubingEventHandler(priority = 10)
    public void configReloadEvent(ConfigReloadEvent e) {
        reload(false);
    }
}
