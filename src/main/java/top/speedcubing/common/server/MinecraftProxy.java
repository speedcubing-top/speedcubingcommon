package top.speedcubing.common.server;

import java.io.DataInputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.io.RedisManager;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;
import top.speedcubing.lib.utils.SQL.SQLRow;
import top.speedcubing.lib.utils.internet.HostAndPort;

public class MinecraftProxy implements Writable {
    private static volatile Map<String, MinecraftProxy> proxies = new HashMap<>();

    public static MinecraftProxy getProxy(String name) {
        return proxies.get(name);
    }

    public static MinecraftProxy getProxy(HostAndPort listenerAddress) {
        for (MinecraftProxy s : proxies.values()) {
            if (s.getListenerAddress().equals(listenerAddress)) {
                return s;
            }
        }
        return null;
    }

    public static Collection<MinecraftProxy> getProxies() {
        return proxies.values();
    }

    public static void loadProxies() {
        Map<String, MinecraftProxy> newProxies = new HashMap<>();
        try (SQLConnection connection = Database.getConfig()) {
            SQLResult result = connection.select("name,host,port").from("mc_proxies").executeResult();
            for (SQLRow r : result) {
                String name = r.getString("name");
                String host = r.getString("host");
                int port = r.getInt("port");
                newProxies.put(name, new MinecraftProxy(name, new HostAndPort(host, port)));
            }
        }
        proxies = newProxies;
    }

    private final HostAndPort listenerAddress;
    private final String name;

    public MinecraftProxy(String name, HostAndPort address) {
        this.name = name;
        // Keep listenerAddress for legacy lookup compatibility; no longer used for TCP.
        this.listenerAddress = new HostAndPort(address.getHost(), address.getPort() + 1000);
        proxies.put(name, this);
    }

    /** No-op: connections are now stateless Redis publishes. */
    public void close() {}

    public int getPlayerCount() {
        String countStr = RedisManager.hget("sc:proxycounts", name);
        if (countStr != null) {
            try {
                return Integer.parseInt(countStr);
            } catch (NumberFormatException ignored) {}
        }
        try (SQLConnection connection = Database.getSystem()) {
            Integer count = connection.select("SUM(onlinecount)").from("stat_onlinecount").where("proxy='" + name + "'").executeResult().getInt();
            return count == null ? 0 : count;
        }
    }

    @Override
    public void write(byte[] data) {
        RedisManager.publish(name, data);
    }

    @Override
    public CompletableFuture<DataInputStream> writeRpc(byte[] data, long timeout, TimeUnit unit) {
        return RedisManager.publishAndAwaitReply(name, data, timeout, unit);
    }

    public HostAndPort getListenerAddress() {
        return listenerAddress;
    }

    public String getName() {
        return name;
    }
}
