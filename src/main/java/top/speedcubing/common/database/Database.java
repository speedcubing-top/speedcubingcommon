package top.speedcubing.common.database;

import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import top.speedcubing.common.CommonLib;
import top.speedcubing.common.configuration.ServerConfig;
import top.speedcubing.lib.utils.SQL.SQLConnection;

public class Database {

    public static volatile Map<String, HikariDataSource> dataSourceMap;

    public static SQLConnection getCubing() {
        return get("speedcubing");
    }

    public static SQLConnection getSystem() {
        return get("speedcubingsystem");
    }

    public static SQLConnection getConfig() {
        return get("sc_config");
    }

    public static SQLConnection get(String database) {
        HikariDataSource dataSource = dataSourceMap.get(database);
        if (dataSource == null) {
            throw new IllegalArgumentException("No such database");
        }

        try {
            return new SQLConnection(dataSource.getConnection());
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void connect() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            CommonLib.logger.info("MySQL JDBC Driver Registered!");
        } catch (ClassNotFoundException e) {
            CommonLib.logger.warning("MySQL JDBC Driver not found.");
            return;
        }

        String[] databases = {"sc_config", "speedcubing", "speedcubingsystem"};
        JsonObject dbConfig = ServerConfig.getConfig().getAsJsonObject("database");
        String url = dbConfig.get("url").getAsString();
        String user = dbConfig.get("user").getAsString();
        String password = dbConfig.get("password").getAsString();
        Map<String, HikariDataSource> newDataSourceMap = new HashMap<>();
        for (String db : databases) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url.replace("%db%", db));
            config.setUsername(user);
            config.setPassword(password);
            HikariDataSource dataSource = new HikariDataSource(config);
            reloadDataSourceConfig(dataSource);
            newDataSourceMap.put(db, dataSource);
        }
        dataSourceMap = newDataSourceMap;
    }

    public static void reloadDataSourceConfig() {
        for (HikariDataSource dataSource : dataSourceMap.values()) {
            reloadDataSourceConfig(dataSource);
        }
    }

    public static void reloadDataSourceConfig(HikariDataSource dataSource) {
        JsonObject hikariCPConfig = ServerConfig.getConfig().getAsJsonObject("database").getAsJsonObject("hikaricp");
        if (hikariCPConfig.has("connectionTimeout"))
            dataSource.setConnectionTimeout(hikariCPConfig.get("connectionTimeout").getAsLong());

        if (hikariCPConfig.has("validationTimeout"))
            dataSource.setValidationTimeout(hikariCPConfig.get("validationTimeout").getAsLong());

        if (hikariCPConfig.has("idleTimeout"))
            dataSource.setIdleTimeout(hikariCPConfig.get("idleTimeout").getAsLong());

        if (hikariCPConfig.has("leakDetectionThreshold"))
            dataSource.setLeakDetectionThreshold(hikariCPConfig.get("leakDetectionThreshold").getAsLong());

        if (hikariCPConfig.has("maxLifetime"))
            dataSource.setMaxLifetime(hikariCPConfig.get("maxLifetime").getAsLong());

        if (hikariCPConfig.has("maxPoolSize"))
            dataSource.setMaximumPoolSize(hikariCPConfig.get("maxPoolSize").getAsInt());

        if (hikariCPConfig.has("minIdle"))
            dataSource.setMinimumIdle(hikariCPConfig.get("minIdle").getAsInt());
    }

    public static void closeAllConnections() {
        for (Map.Entry<String, HikariDataSource> entry : dataSourceMap.entrySet()) {
            String dbName = entry.getKey();
            HikariDataSource dataSource = entry.getValue();
            try {
                if (dataSource != null && !dataSource.isClosed()) {
                    dataSource.close();
                    CommonLib.logger.info("Closed connection pool for database: " + dbName);
                }
            } catch (Exception e) {
                CommonLib.logger.warning("Failed to close connection pool for database: " + dbName + " - " + e.getMessage());
            }
        }
        dataSourceMap.clear();
    }
}
