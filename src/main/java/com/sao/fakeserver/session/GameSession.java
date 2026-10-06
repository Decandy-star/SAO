package com.sao.fakeserver.session;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.service.FightWireTap;
import com.sao.fakeserver.store.PlayerRecord;
import io.netty.channel.Channel;

public class GameSession {
    private final Channel channel;
    private String account;
    private PlayerRecord player;
    /** JJC 开战～结算：全量网线旁路。 */
    private volatile boolean jjcFightWire;
    private FightWireTap wireTap;

    public GameSession(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    public String account() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public PlayerRecord player() {
        return player;
    }

    public void setPlayer(PlayerRecord player) {
        this.player = player;
    }

    public boolean isJjcFightWire() {
        return jjcFightWire;
    }

    public void setJjcFightWire(boolean jjcFightWire) {
        this.jjcFightWire = jjcFightWire;
    }

    public void setWireTap(FightWireTap wireTap) {
        this.wireTap = wireTap;
    }

    public void send(int msgId, int serial, byte[] body) {
        byte[] b = body == null ? new byte[0] : body;
        if (jjcFightWire && wireTap != null) {
            wireTap.onS2C(this, msgId, serial, b);
        }
        channel.writeAndFlush(new GamePacket(msgId, serial, b));
    }

    public void send(int msgId, GamePacket request, byte[] body) {
        send(msgId, request.serial, body);
    }
}
