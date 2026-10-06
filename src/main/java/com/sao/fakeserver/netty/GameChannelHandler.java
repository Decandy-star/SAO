package com.sao.fakeserver.netty;

import com.sao.fakeserver.handler.MessageDispatcher;
import com.sao.fakeserver.service.MineService;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameChannelHandler extends SimpleChannelInboundHandler<GamePacket> {
    private static final Logger log = LoggerFactory.getLogger(GameChannelHandler.class);
    static final AttributeKey<GameSession> SESSION = AttributeKey.valueOf("sao.session");

    private final MessageDispatcher dispatcher;
    private final SessionHub sessions;
    private final MineService mine;
    private final PlayerStore store;

    public GameChannelHandler(MessageDispatcher dispatcher, SessionHub sessions, MineService mine,
                             PlayerStore store) {
        this.dispatcher = dispatcher;
        this.sessions = sessions;
        this.mine = mine;
        this.store = store;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().attr(SESSION).set(new GameSession(ctx.channel()));
        log.info("client {}", ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GamePacket pkt) {
        GameSession session = ctx.channel().attr(SESSION).get();
        dispatcher.dispatch(session, pkt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        GameSession session = ctx.channel().attr(SESSION).get();
        if (session != null && session.account() != null) {
            mine.onAttackerDisconnect(session.account());
            // 好友列表 CFriendBase.8 OfflineTime 要用真实离线时刻
            // （FriendItem.cs:108 对该字段 DateTime.ParseExact 且不判空）。
            PlayerRecord rec = session.player();
            if (rec != null) {
                rec.lastLogoutAtMs = System.currentTimeMillis();
                store.save(rec);
            }
        }
        sessions.unbind(session);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("channel error: {}", cause.toString());
        ctx.close();
    }
}
