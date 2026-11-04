package top.speedcubing.common.io;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import top.speedcubing.lib.utils.internet.HostAndPort;

public class SocketWriter {

    private static final EventLoopGroup sharedGroup = new NioEventLoopGroup();

    public static CompletableFuture<DataInputStream> writeResponse(HostAndPort hostPort, byte[] data) {
        CompletableFuture<DataInputStream> futureResponse = new CompletableFuture<>();

        Bootstrap b = new Bootstrap();
        b.group(sharedGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4)); // same limit as SocketReader
                        pipeline.addLast(new LengthFieldPrepender(4));
                        pipeline.addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
                                byte[] receivedBytes = new byte[buf.readableBytes()];
                                buf.readBytes(receivedBytes);
                                futureResponse.complete(new DataInputStream(new ByteArrayInputStream(receivedBytes)));
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                futureResponse.completeExceptionally(cause);
                                ctx.close();
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                // peer closed without answering; no-op if the response already completed the future
                                futureResponse.completeExceptionally(new ClosedChannelException());
                                ctx.fireChannelInactive();
                            }
                        });
                    }
                });

        b.connect(hostPort.getHost(), hostPort.getPort()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                future.channel().writeAndFlush(Unpooled.wrappedBuffer(data)).addListener((ChannelFutureListener) write -> {
                    if (!write.isSuccess()) {
                        futureResponse.completeExceptionally(write.cause());
                        write.channel().close();
                    }
                });
            } else {
                futureResponse.completeExceptionally(future.cause());
            }
        });

        return futureResponse;
    }
}
