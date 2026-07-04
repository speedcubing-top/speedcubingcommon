package top.speedcubing.common.io;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import top.speedcubing.common.events.SocketReadEvent;

/**
 * Manages Redis connectivity and pub/sub messaging as a drop-in replacement
 * for the Netty-based SocketReader / SocketWriter / PersistentConnection
 * inter-server communication stack.
 *
 * <h3>Channel conventions</h3>
 * <ul>
 *   <li>{@code sc:<nodeName>} – unicast to a specific server/proxy node</li>
 *   <li>{@code sc:all}        – broadcast to every subscribed node</li>
 *   <li>{@code sc:reply:<id>} – single-use reply channel for request/response</li>
 * </ul>
 *
 * <h3>Message wire format</h3>
 * Each published message is a Base64-encoded byte sequence produced by a
 * {@link top.speedcubing.lib.utils.bytes.ByteArrayBuffer}:
 * <pre>
 *   [UTF packetID] [payload bytes…]
 * </pre>
 * Identical to what was previously sent over TCP, so existing
 * {@link SocketReadEvent} handlers require no changes.
 *
 * <h3>Request/response pattern</h3>
 * The old TCP layer was synchronous (one connection, FIFO responses).
 * For the few packets that need a reply (currently only {@code getping} in the
 * proxy), callers use {@link #publishAndAwaitReply(String, byte[], long, TimeUnit)}.
 * The publisher embeds a reply-channel suffix in the message:
 * <pre>
 *   [UTF "rpc"]  [UTF replyChannel]  [UTF packetID]  [payload bytes…]
 * </pre>
 * The subscriber detects the {@code "rpc"} wrapper, strips it, fires the
 * {@link SocketReadEvent} normally, then publishes the event's response buffer
 * back on the reply channel.
 */
public class RedisManager {

    /** Timeout in seconds waiting for a pub/sub reply. */
    private static final int REPLY_TIMEOUT_SECONDS = 5;

    private static volatile JedisPool pool;
    private static volatile String nodeName;
    private static volatile SubscriberThread subscriberThread;
    private static volatile Logger log;

    /** Pending RPC futures keyed by reply-channel name. */
    private static final Map<String, CompletableFuture<DataInputStream>> pendingReplies =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Called once at startup. Establishes the pool and starts the subscriber.
     *
     * @param cfg  JSON object from {@code server.json → "redis"} with keys
     *             {@code host}, {@code port} (optional, default 6379),
     *             {@code password} (optional).
     */
    public static synchronized void connect(JsonObject cfg) {
        if (cfg == null) {
            getLogger().warning("[RedisManager] No 'redis' section in server.json – Redis disabled.");
            return;
        }
        pool = buildPool(cfg);
        getLogger().info("[RedisManager] Connected to Redis.");
    }

    /**
     * Called on config reload. Replaces the pool; the subscriber thread
     * re-subscribes automatically when the next {@link #subscribe(String)} call
     * is made (or it re-uses the existing subscription if the channels are the
     * same).
     */
    public static synchronized void reload(JsonObject cfg) {
        if (cfg == null) return;
        JedisPool oldPool = pool;
        pool = buildPool(cfg);
        if (oldPool != null) {
            stopSubscriber();
            oldPool.close();
        }
        // Re-subscribe with the same node name if it was already set.
        if (nodeName != null) {
            subscribe(nodeName);
        }
        getLogger().info("[RedisManager] Redis connection reloaded.");
    }

    /** Shut down cleanly on plugin disable. */
    public static synchronized void shutdown() {
        stopSubscriber();
        if (pool != null && !pool.isClosed()) {
            pool.close();
            pool = null;
        }
    }

    // -----------------------------------------------------------------------
    // Subscription
    // -----------------------------------------------------------------------

    /**
     * Subscribe this node to its unicast channel ({@code sc:<name>}) and the
     * broadcast channel ({@code sc:all}).  Must be called after
     * {@link #connect(JsonObject)}.
     *
     * @param name  the logical name of this node (e.g. {@code "proxy0"},
     *              {@code "lobby"}, {@code "practice"}).
     */
    public static synchronized void subscribe(String name) {
        if (pool == null) return;
        stopSubscriber();
        nodeName = name;
        subscriberThread = new SubscriberThread(name);
        subscriberThread.setDaemon(true);
        subscriberThread.setName("redis-subscriber-" + name);
        subscriberThread.start();
        getLogger().info("[RedisManager] Subscribed as '" + name + "' on channels sc:" + name + ", sc:all, sc:reply:*");
    }

    private static void stopSubscriber() {
        SubscriberThread old = subscriberThread;
        if (old != null) {
            old.unsubscribeAll();
            subscriberThread = null;
        }
    }

    // -----------------------------------------------------------------------
    // Publishing
    // -----------------------------------------------------------------------

    /**
     * Fire-and-forget publish to the named target channel ({@code sc:<target>}).
     *
     * @param target  logical node name of the recipient
     * @param data    raw message bytes (packetID UTF + payload), as produced by
     *                {@link top.speedcubing.lib.utils.bytes.ByteArrayBuffer#toByteArray()}
     */
    public static void publish(String target, byte[] data) {
        publishRaw("sc:" + target, data);
    }

    /**
     * Broadcast to all subscribers ({@code sc:all}).
     */
    public static void broadcast(byte[] data) {
        publishRaw("sc:all", data);
    }

    // -----------------------------------------------------------------------
    // Key-value / hash operations (used for lightweight shared state such as
    // per-proxy player counts, where pub/sub is not the right fit)
    // -----------------------------------------------------------------------

    /** Set a field in a Redis hash ({@code HSET key field value}). */
    public static void hset(String key, String field, String value) {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(key, field, value);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] hset failed on key '" + key + "': " + e.getMessage());
        }
    }

    /** Remove a field from a Redis hash ({@code HDEL key field}). */
    public static void hdel(String key, String field) {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(key, field);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] hdel failed on key '" + key + "': " + e.getMessage());
        }
    }

    /** Return all values of a Redis hash ({@code HVALS key}). */
    public static List<String> hvals(String key) {
        if (pool == null) return Collections.emptyList();
        try (Jedis jedis = pool.getResource()) {
            return jedis.hvals(key);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] hvals failed on key '" + key + "': " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Return one field from a Redis hash ({@code HGET key field}). */
    public static String hget(String key, String field) {
        if (pool == null) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(key, field);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] hget failed on key '" + key + "': " + e.getMessage());
            return null;
        }
    }

    /** Set a string value with an expiry ({@code SETEX key seconds value}). */
    public static void setex(String key, int seconds, String value) {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key, seconds, value);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] setex failed on key '" + key + "': " + e.getMessage());
        }
    }

    /** Return a string value and delete the key. */
    public static String getdel(String key) {
        if (pool == null) return null;
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(key);
            if (value != null) {
                jedis.del(key);
            }
            return value;
        } catch (Exception e) {
            getLogger().warning("[RedisManager] getdel failed on key '" + key + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Publish {@code data} to {@code sc:<target>} and block until the remote
     * side sends a reply or the timeout elapses.
     *
     * @return a {@link DataInputStream} over the reply bytes, or an
     *         exceptionally-completed future on timeout/error.
     */
    public static CompletableFuture<DataInputStream> publishAndAwaitReply(
            String target, byte[] data, long timeout, TimeUnit unit) {
        if (pool == null) {
            CompletableFuture<DataInputStream> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Redis not connected"));
            return f;
        }
        String replyChannel = "sc:reply:" + UUID.randomUUID();
        CompletableFuture<DataInputStream> future = new CompletableFuture<>();
        pendingReplies.put(replyChannel, future);

        // Wrap the original payload in an RPC envelope:
        // [UTF "rpc"] [UTF replyChannel] [original data bytes...]
        byte[] wrapped = wrapRpc(replyChannel, data);
        publishRaw("sc:" + target, wrapped);

        // Auto-fail after timeout
        future.orTimeout(timeout == 0 ? REPLY_TIMEOUT_SECONDS : timeout,
                timeout == 0 ? TimeUnit.SECONDS : unit)
                .whenComplete((r, ex) -> pendingReplies.remove(replyChannel));

        return future;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static void publishRaw(String channel, byte[] data) {
        if (pool == null) {
            getLogger().warning("[RedisManager] publish called but pool is null (channel=" + channel + ")");
            return;
        }
        String encoded = Base64.getEncoder().encodeToString(data);
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, encoded);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] publish failed on channel '" + channel + "': " + e.getMessage());
        }
    }

    private static JedisPool buildPool(JsonObject cfg) {
        String host = cfg.has("host") ? cfg.get("host").getAsString() : "127.0.0.1";
        int port = cfg.has("port") ? cfg.get("port").getAsInt() : 6379;
        String password = cfg.has("password") && !cfg.get("password").getAsString().isEmpty()
                ? cfg.get("password").getAsString() : null;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);

        if (password != null) {
            return new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            return new JedisPool(poolConfig, host, port, 2000);
        }
    }

    private static byte[] wrapRpc(String replyChannel, byte[] payload) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF("rpc");
            dos.writeUTF(replyChannel);
            dos.write(payload);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void handleMessage(String channel, String message) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(message);
        } catch (Exception e) {
            getLogger().warning("[RedisManager] Failed to decode message on channel " + channel + ": " + e.getMessage());
            return;
        }

        // Check for reply channel
        if (channel.startsWith("sc:reply:")) {
            CompletableFuture<DataInputStream> future = pendingReplies.remove(channel);
            if (future != null) {
                future.complete(new DataInputStream(new ByteArrayInputStream(raw)));
            }
            return;
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            String firstUTF = in.readUTF();

            if ("rpc".equals(firstUTF)) {
                // RPC envelope: read reply channel, then dispatch normally
                String replyChannel = in.readUTF();
                // Remaining bytes are the real payload
                byte[] innerBytes = in.readAllBytes();
                DataInputStream innerIn = new DataInputStream(new ByteArrayInputStream(innerBytes));
                String packetID = innerIn.readUTF();
                SocketReadEvent event = new SocketReadEvent(packetID, innerIn);
                event.call();
                byte[] replyBytes = event.getBuffer().toByteArray();
                if (replyBytes.length == 0) {
                    // Write default "OK" reply
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF("OK");
                    replyBytes = baos.toByteArray();
                }
                publishRaw(replyChannel, replyBytes);
            } else {
                // Normal fire-and-forget packet: firstUTF is already the packetID
                SocketReadEvent event = new SocketReadEvent(firstUTF, in);
                event.call();
            }
        } catch (Exception e) {
            getLogger().warning("[RedisManager] Error handling message on channel " + channel + ": " + e.getMessage());
        }
    }

    private static Logger getLogger() {
        Logger l = log;
        return l != null ? l : Logger.getLogger("RedisManager");
    }

    public static void setLogger(Logger logger) {
        RedisManager.log = logger;
    }

    // -----------------------------------------------------------------------
    // Subscriber thread
    // -----------------------------------------------------------------------

    private static class SubscriberThread extends Thread {
        private final String name;
        private final AtomicReference<JedisPubSub> pubSubRef = new AtomicReference<>();

        SubscriberThread(String name) {
            this.name = name;
        }

        void unsubscribeAll() {
            JedisPubSub ps = pubSubRef.get();
            if (ps != null && ps.isSubscribed()) {
                try { ps.unsubscribe(); } catch (Exception ignored) {}
            }
            interrupt();
        }

        @Override
        public void run() {
            while (!isInterrupted() && pool != null && !pool.isClosed()) {
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        RedisManager.handleMessage(channel, message);
                    }

                    @Override
                    public void onPMessage(String pattern, String channel, String message) {
                        RedisManager.handleMessage(channel, message);
                    }
                };
                pubSubRef.set(pubSub);
                try (Jedis jedis = pool.getResource()) {
                    // Subscribe to unicast, broadcast, and all reply channels
                    jedis.psubscribe(pubSub,
                            "sc:" + name,
                            "sc:all",
                            "sc:reply:*");
                } catch (Exception e) {
                    if (!isInterrupted()) {
                        getLogger().warning("[RedisManager] Subscriber disconnected, reconnecting in 3s: " + e.getMessage());
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        }
    }
}
