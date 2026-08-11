package top.speedcubing.common.redis.message;

public record ConnectServerMessage(int id, String src, String dst) {
}
