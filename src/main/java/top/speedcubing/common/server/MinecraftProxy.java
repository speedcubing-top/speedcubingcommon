package top.speedcubing.common.server;

import com.google.gson.Gson;
import top.speedcubing.common.io.SocketWriter;
import top.speedcubing.common.redis.RedisBus;
import top.speedcubing.lib.utils.internet.HostAndPort;

import java.io.DataInputStream;
import java.util.concurrent.CompletableFuture;

public class MinecraftProxy implements Writable {

    public static MinecraftProxy getProxy(String name) {
        return new MinecraftProxy(name);
    }

    private final String name;

    private MinecraftProxy(String name) {
        this.name = name;
    }

    @Override
    public CompletableFuture<DataInputStream> write(byte[] data) {
        return SocketWriter.writeResponse(new HostAndPort(name, 26565), data);
    }

    @Override
    public void redisPublish(String channel, Object obj) {
        String message;
        if (obj == null) {
            message = null;
        } else if (obj instanceof String s) {
            message = s;
        } else {
            message = new Gson().toJson(obj);
        }
        RedisBus.publish(RedisBus.getChannelPrefix() + ":proxy:" + name + ":" + channel, message);
    }
}
