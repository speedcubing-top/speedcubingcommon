package top.speedcubing.common.redis.message;

/**
 * Tells a server/proxy to re-read a player's rank and permissions from the database.
 * playerId 0 means every online player; reloadGroups also reloads permission group definitions first.
 */
public record RankUpdateMessage(int playerId, boolean reloadGroups) {
}
