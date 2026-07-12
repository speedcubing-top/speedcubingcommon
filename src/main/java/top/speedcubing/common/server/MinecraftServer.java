package top.speedcubing.common.server;

import java.io.DataInputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.io.SocketWriter;
import top.speedcubing.common.redis.RedisBus;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;
import top.speedcubing.lib.utils.SQL.SQLRow;
import top.speedcubing.lib.utils.internet.HostAndPort;

public class MinecraftServer implements Writable {
    private static volatile Map<String, MinecraftServer> servers = new HashMap<>();

    public static MinecraftServer getServer(String name) {
        return servers.get(name);
    }

    public static MinecraftServer getServer(HostAndPort listenerAddress) {
        for (MinecraftServer s : servers.values()) {
            if (s.getListenerAddress().equals(listenerAddress)) {
                return s;
            }
        }
        return null;
    }

    public static Collection<MinecraftServer> getServers() {
        return servers.values();
    }

    public static void loadServers() {
        Map<String, MinecraftServer> newServers = new HashMap<>();
        try (SQLConnection connection = Database.getConfig()) {
            SQLResult result = connection.select("name,host,port,accept_socket").from("mc_servers").executeResult();
            for (SQLRow r : result) {
                String name = r.getString("name");
                String host = r.getString("host");
                int port = r.getInt("port");
                boolean accept_socket = r.getBoolean("accept_socket");
                newServers.put(name, new MinecraftServer(name, new HostAndPort(host, port), accept_socket));
            }
        }
        servers = newServers;
    }

    private final HostAndPort listenerAddress;
    private final String name;
    private final boolean acceptSocket;

    public MinecraftServer(String name, HostAndPort address, boolean accept_socket) {
        this.name = name;
        this.listenerAddress = new HostAndPort(address.getHost(), address.getPort() + 1000);
        this.acceptSocket = accept_socket;
        servers.put(name, this);
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
        RedisBus.publish(RedisBus.getChannelPrefix() + ":server:" + name + ":" + channel, message);
    }

    public HostAndPort getListenerAddress() {
        return listenerAddress;
    }

    public String getName() {
        return name;
    }

    public String getWebhook() {
        try (SQLConnection connection = Database.getConfig()) {
            return connection.select("discord_webhook").from("mc_servers").where("name='" + name + "'").executeResult().getString();
        }
    }

    public boolean isAcceptSocket() {
        return acceptSocket;
    }
}
