package top.speedcubing.common.redis;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import top.speedcubing.common.CommonLib;
import top.speedcubing.common.configuration.ServerConfig;

public class RedisBus {
    private static final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private static Thread subscriberThread;
    private static JedisPubSub activeSubscription;

    private RedisBus() {
    }

    public static boolean isEnabled() {
        JsonObject redis = getRedisConfig();
        return redis != null && redis.has("enabled") && redis.get("enabled").getAsBoolean();
    }

    public static String getChannelPrefix() {
        JsonObject redis = getRedisConfig();
        if (redis != null && redis.has("channelPrefix")) {
            return redis.get("channelPrefix").getAsString();
        }
        return "speedcubing";
    }

    public static void publish(String channel, String message) {
        if (!isEnabled()) {
            return;
        }

        try (Jedis jedis = new Jedis(getHost(), getPort())) {
            applyAuth(jedis);
            jedis.publish(channel, message);
        } catch (Exception e) {
            CommonLib.logger.warning("Failed to publish Redis message to " + channel + ": " + e.getMessage());
        }
    }

    public static synchronized void subscribe(String channel, Consumer<String> consumer) {
        if (!isEnabled()) {
            return;
        }

        handlers.put(channel, consumer);
        startSubscriberIfNeeded();
    }

    private static synchronized void startSubscriberIfNeeded() {
        if (handlers.isEmpty() || subscriberThread != null) {
            return;
        }

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = new Jedis(getHost(), getPort())) {
                    applyAuth(jedis);
                    activeSubscription = new JedisPubSub() {
                        @Override
                        public void onMessage(String incomingChannel, String message) {
                            Consumer<String> handler = handlers.get(incomingChannel);
                            if (handler != null) {
                                handler.accept(message);
                            }
                        }
                    };
                    jedis.subscribe(activeSubscription, handlers.keySet().toArray(new String[0]));
                } catch (Exception e) {
                    CommonLib.logger.warning("Redis subscriber stopped: " + e.getMessage());
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "redis-sub-bus");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public static synchronized void shutdown() {
        if (activeSubscription != null) {
            try {
                activeSubscription.unsubscribe();
            } catch (Exception ignored) {
            }
            activeSubscription = null;
        }

        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        handlers.clear();
    }

    private static JsonObject getRedisConfig() {
        JsonObject config = ServerConfig.getConfig();
        if (config == null || !config.has("redis")) {
            return null;
        }
        return config.getAsJsonObject("redis");
    }

    private static String getHost() {
        JsonObject redis = getRedisConfig();
        return redis != null && redis.has("host") ? redis.get("host").getAsString() : "127.0.0.1";
    }

    private static int getPort() {
        JsonObject redis = getRedisConfig();
        return redis != null && redis.has("port") ? redis.get("port").getAsInt() : 6379;
    }

    private static void applyAuth(Jedis jedis) {
        JsonObject redis = getRedisConfig();
        if (redis != null && redis.has("password") && !redis.get("password").isJsonNull()) {
            String password = redis.get("password").getAsString();
            if (!password.isEmpty()) {
                jedis.auth(password);
            }
        }
    }
}
