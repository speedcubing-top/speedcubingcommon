package top.speedcubing.common.redis.message;

public record VanishUpdateMessage(int playerId, boolean state) {
}
