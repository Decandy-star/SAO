package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 大厅坐标：客户端 C2S 103 {@code CCMsgEntityPhyInfo} 写入内存 map，不落盘、不回包。
 * S2C 103 是出生点，不要在这里回。别人位置才是 S2C 104。
 */
@Service
public class CityPosService {
    private static final Logger log = LoggerFactory.getLogger(CityPosService.class);

    public static final class Pos {
        public int playerId;
        public String account = "";
        public float x;
        public float y;
        public float z;
        public float yaw;
        public long updatedAtMs;
    }

    private final ConcurrentHashMap<Integer, Pos> byPlayerId = new ConcurrentHashMap<Integer, Pos>();
    private final ConcurrentHashMap<String, Pos> byAccount = new ConcurrentHashMap<String, Pos>();

    public void put(PlayerRecord rec, float x, float y, float z, float yaw) {
        if (rec == null) {
            return;
        }
        Pos pos = new Pos();
        pos.playerId = rec.playerId;
        pos.account = rec.account == null ? "" : rec.account;
        pos.x = x;
        pos.y = y;
        pos.z = z;
        pos.yaw = yaw;
        pos.updatedAtMs = System.currentTimeMillis();
        byPlayerId.put(Integer.valueOf(rec.playerId), pos);
        if (!pos.account.isEmpty()) {
            byAccount.put(pos.account, pos);
        }
    }

    public Pos getByPlayerId(int playerId) {
        return byPlayerId.get(Integer.valueOf(playerId));
    }

    public Pos getByAccount(String account) {
        return account == null ? null : byAccount.get(account);
    }

    /** C2S 103：pos=field1 嵌套 CCMsgVector3（fixed32 xyz），yaw=field2 fixed32。 */
    public void onPhyUpdate(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields body = Pb.read(pkt.body);
        Pb.Fields pos = Pb.read(body.getBytes(1));
        float x = pos.getFloat(1, 0f);
        float y = pos.getFloat(2, 0f);
        float z = pos.getFloat(3, 0f);
        float yaw = body.getFloat(2, 0f);
        put(rec, x, y, z, yaw);
        log.debug("city pos account={} id={} ({}, {}, {}) yaw={}", rec.account, rec.playerId, x, y, z, yaw);
    }
}
