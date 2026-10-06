package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.session.GameSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * JJC 开战～结算期间全量网线旁路：C2S/S2C 每包一行（msgId/serial/len/hex）。
 * 用于确认战斗期到底交互了什么（尤其有无 Hurt）。
 */
@Service
public class FightWireTap {
    private static final Logger log = LoggerFactory.getLogger(FightWireTap.class);
    private static final int HEX_CAP = 256;

    public void start(GameSession session, String reason) {
        session.setJjcFightWire(true);
        log.info("JJC-WIRE START account={} reason={}", session.account(), reason);
    }

    public void stop(GameSession session, String reason) {
        if (!session.isJjcFightWire()) {
            return;
        }
        session.setJjcFightWire(false);
        log.info("JJC-WIRE STOP account={} reason={}", session.account(), reason);
    }

    public void onC2S(GameSession session, GamePacket pkt) {
        if (session == null || !session.isJjcFightWire()) {
            return;
        }
        log.info("JJC-WIRE C2S msgId={} name={} serial={} len={} hex={}",
                pkt.msgId, c2sName(pkt.msgId), pkt.serial, pkt.body.length, hex(pkt.body));
    }

    public void onS2C(GameSession session, int msgId, int serial, byte[] body) {
        if (session == null || !session.isJjcFightWire()) {
            return;
        }
        byte[] b = body == null ? new byte[0] : body;
        log.info("JJC-WIRE S2C msgId={} name={} serial={} len={} hex={}",
                msgId, s2cName(msgId), serial, b.length, hex(b));
    }

    private static String hex(byte[] body) {
        int n = Math.min(body.length, HEX_CAP);
        StringBuilder sb = new StringBuilder(n * 2 + 16);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", body[i] & 0xff));
        }
        if (body.length > HEX_CAP) {
            sb.append("...+").append(body.length - HEX_CAP);
        }
        return sb.toString();
    }

    private static String c2sName(int id) {
        switch (id) {
            case MsgIds.C2S_HEARTBEAT:
                return "HEARTBEAT";
            case MsgIds.C2S_JJC_FIGHT:
                return "JJC_FIGHT";
            case MsgIds.C2S_JJC_RESULT:
                return "JJC_RESULT";
            case MsgIds.C2S_FIGHT_SYNC_PACK:
                return "FIGHT_SYNC";
            case MsgIds.C2S_ENTITY_PHY_UPDATE:
                return "PHY_UPDATE";
            default:
                return "?";
        }
    }

    private static String s2cName(int id) {
        switch (id) {
            case MsgIds.S2C_HEARTBEAT:
                return "HEARTBEAT";
            case MsgIds.S2C_JJC_FIGHT_TEAMS:
                return "JJC_FIGHT_TEAMS";
            case MsgIds.S2C_CHANGE_REGION_RET:
                return "CHANGE_REGION";
            case MsgIds.S2C_FIGHT_SYNC_PACK:
                return "FIGHT_SYNC";
            case MsgIds.S2C_JJC_LAST_CHALLENGE:
                return "JJC_LAST_CHALLENGE";
            case MsgIds.S2C_JJC_RESET_TIMES:
                return "JJC_RESET_TIMES";
            case MsgIds.S2C_ATTRI_UPDATE:
                return "ATTRI_UPDATE";
            default:
                return "?";
        }
    }
}
