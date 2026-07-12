package top.speedcubing.common.redis.message;

public class VanishUpdateMessage {
    public int playerId;
    public boolean state;

    public VanishUpdateMessage(int playerId, boolean state) {
        this.playerId = playerId;
        this.state = state;
    }
}
