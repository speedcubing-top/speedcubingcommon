package top.speedcubing.common.rank;

import top.speedcubing.common.database.Database;
import top.speedcubing.common.redis.message.RankUpdateMessage;
import top.speedcubing.common.server.MinecraftProxy;
import top.speedcubing.common.server.MinecraftServer;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLRow;

/**
 * Broadcasts "re-read this player's rank/perms" to every server and proxy, including the caller.
 * The message only carries the id; receivers reload from the database, so duplicates or
 * reordering are harmless.
 */
public class RankUpdate {
    public static final String CHANNEL = "rankupdate";

    public static void publish(int playerId, boolean reloadGroups) {
        RankUpdateMessage message = new RankUpdateMessage(playerId, reloadGroups);
        for (MinecraftServer server : MinecraftServer.getServers()) {
            server.redisPublish(CHANNEL, message);
        }
        try (SQLConnection connection = Database.getSystem()) {
            for (SQLRow row : connection.executeResult("SELECT `name` FROM `proxies`")) {
                MinecraftProxy.getProxy(row.getString(0)).redisPublish(CHANNEL, message);
            }
        }
    }
}
