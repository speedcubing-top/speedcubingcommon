package top.speedcubing.common.io;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import top.speedcubing.lib.utils.internet.HostAndPort;

/**
 * Maintains a single persistent TCP connection to a remote host, reusing it
 * for all writes instead of opening a new connection per message.
 *
 * Responses are matched to requests via a FIFO queue — safe because TCP
 * preserves order and the remote SocketReader processes packets sequentially.
 *
 * Reconnects automatically after a 3-second delay if the channel drops.
 */
public class PersistentConnection {

    private static final EventLoopGroup sharedGroup = new NioEventLoopGroup();
    private static final int RECONNECT_DELAY_SECONDS = 3;

    private final HostAndPort hostPort;
    private final Bootstrap bootstrap;
    private final ConcurrentLinkedQueue<CompletableFuture<DataInputStream>> pendingResponses = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile Channel channel;
    private volatile boolean closed = false;

    public PersistentConnection(HostAndPort hostPort) {
        this.hostPort = hostPort;
        this.bootstrap = new Bootstrap()
                .group(sharedGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
                        pipeline.addLast(new LengthFieldPrepender(4));
                        pipeline.addLast(new ResponseHandler());
                    }
                });
        connect();
    }

    private void connect() {
        if (closed || !connecting.compareAndSet(false, true)) return;
        bootstrap.connect(hostPort.getHost(), hostPort.getPort()).addListener((ChannelFutureListener) future -> {
            connecting.set(false);
            if (future.isSuccess()) {
                channel = future.channel();
            } else if (!closed) {
                sharedGroup.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        });
    }

    /**
     * Send {@code data} on the persistent channel and return a future that
     * completes when the remote side sends back its response.
     *
     * Fails immediately if the channel is not currently connected; the caller
     * should handle or ignore the failure as appropriate.
     */
    public CompletableFuture<DataInputStream> write(byte[] data) {
        CompletableFuture<DataInputStream> future = new CompletableFuture<>();
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            pendingResponses.add(future);
            ch.writeAndFlush(Unpooled.wrappedBuffer(data)).addListener(f -> {
                if (!f.isSuccess()) {
                    pendingResponses.remove(future);
                    future.completeExceptionally(f.cause());
                }
            });
        } else {
            future.completeExceptionally(new IllegalStateException("Not connected to " + hostPort));
        }
        return future;
    }

    /**
     * Close the connection permanently (no reconnect). Call this when the
     * owning proxy/server is being replaced on config reload.
     */
    public void close() {
        closed = true;
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        CompletableFuture<DataInputStream> pending;
        while ((pending = pendingResponses.poll()) != null) {
            pending.completeExceptionally(new IllegalStateException("Connection closed to " + hostPort));
        }
    }

    private class ResponseHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            CompletableFuture<DataInputStream> future = pendingResponses.poll();
            if (future != null) {
                future.complete(new DataInputStream(new ByteArrayInputStream(bytes)));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            channel = null;
            // Fail any in-flight requests so callers aren't left hanging.
            CompletableFuture<DataInputStream> pending;
            while ((pending = pendingResponses.poll()) != null) {
                pending.completeExceptionally(new IllegalStateException("Connection lost to " + hostPort));
            }
            if (!closed) {
                sharedGroup.schedule(PersistentConnection.this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
