package top.speedcubing.common.server;

import java.io.DataInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public interface Writable {
    /**
     * Fire-and-forget publish. The old TCP send-and-await-response semantics
     * are replaced with Redis pub/sub; most callers do not need a reply.
     */
    void write(byte[] data);

    /**
     * Publish and await a reply on a single-use Redis reply channel.
     * Use this only for packets that genuinely require a synchronous response
     * (e.g. {@code getping}).
     */
    CompletableFuture<DataInputStream> writeRpc(byte[] data, long timeout, TimeUnit unit);
}
