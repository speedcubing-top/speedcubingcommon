package top.speedcubing.common.server;

import java.io.DataInputStream;
import java.util.concurrent.CompletableFuture;

public interface Writable {
    CompletableFuture<DataInputStream> write(byte[] data);
    void redisPublish(String channel, String message);
}
