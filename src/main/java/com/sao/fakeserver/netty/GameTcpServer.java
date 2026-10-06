package com.sao.fakeserver.netty;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.handler.MessageDispatcher;
import com.sao.fakeserver.service.MineService;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GameTcpServer {
    private static final Logger log = LoggerFactory.getLogger(GameTcpServer.class);

    private final SaoProperties props;
    private final MessageDispatcher dispatcher;
    private final SessionHub sessions;
    private final MineService mine;
    private final PlayerStore store;
    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel channel;

    public GameTcpServer(SaoProperties props, MessageDispatcher dispatcher, SessionHub sessions, MineService mine,
                         PlayerStore store) {
        this.props = props;
        this.dispatcher = dispatcher;
        this.sessions = sessions;
        this.mine = mine;
        this.store = store;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        channel = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new PacketFrameDecoder())
                                .addLast(new PacketEncoder())
                                .addLast(new GameChannelHandler(dispatcher, sessions, mine, store));
                    }
                })
                .bind(props.getGamePort())
                .sync()
                .channel();
        log.info("game TCP {}:{}", props.getPublicIp(), props.getGamePort());
    }

    @PreDestroy
    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
    }
}
