package top.speedcubing.common.server;

import java.io.DataInputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.io.SocketWriter;
import top.speedcubing.common.redis.RedisBus;
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
        this.listenerAddress = new HostAndPort(address.getHost(), address.getPort() + 1000);
        proxies.put(name, this);
    }

    public int getPlayerCount() {
        try (SQLConnection connection = Database.getSystem()) {
            return connection.select("SUM(onlinecount)").from("stat_onlinecount").where("server='" + name + "'").executeResult().getInt();
        }
    }

    @Override
    public CompletableFuture<DataInputStream> write(byte[] data) {
        return SocketWriter.writeResponse(listenerAddress, data);
    }

    @Override
    public void redisPublish(String channel, String message) {
        RedisBus.publish(RedisBus.getChannelPrefix() + ":proxy:" + name + ":" + channel, message);
    }

    public HostAndPort getListenerAddress() {
        return listenerAddress;
    }

    public String getName() {
        return name;
    }
}
