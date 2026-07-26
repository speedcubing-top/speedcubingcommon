package top.speedcubing.common.redis.message;

public record VelocityUpdateMessage(int playerId, boolean enabled, double horizontal, double vertical) {
}
