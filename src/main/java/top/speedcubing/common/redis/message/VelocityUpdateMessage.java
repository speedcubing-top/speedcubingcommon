package top.speedcubing.common.redis.message;

public class VelocityUpdateMessage {
    public int playerId;
    public boolean enabled;
    public double horizontal;
    public double vertical;

    public VelocityUpdateMessage(int playerId, boolean enabled, double horizontal, double vertical) {
        this.playerId = playerId;
        this.enabled = enabled;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }
}
