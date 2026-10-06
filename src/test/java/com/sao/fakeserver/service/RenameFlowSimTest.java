package com.sao.fakeserver.service;

import com.sao.fakeserver.handler.MessageDispatcher;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.EconomyTables;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 改名流程（C2S 2101 / S2C 2301 / S2C 2302）按 APK 真实序列走一遍。
 *
 * <p>客户端依据：{@code MainPlayerSystem.cs:623-625} 发 {@code CCMsgPlayerName.PlayerName(name)}；
 * {@code PlayGameState.cs:5273-5293} 收 {@code CCMsgModifyRoleName_Ret}：retCode 0 = 成功（冒字 100935
 * 并覆盖 mName）、1 = 昵称重复（冒字 100936）；{@code CCMsgUpdatePlayerRoleName{dynID,newName}} 刷新别人
 * 视野里的名字。扣费 = GlobalSetup「攻略组改名钻石花费」（{@code EconomyTables.changeNameCostRmb}）。
 *
 * <p>测试数据落在 {@code target/test-data/players-rename-flow}，跨次运行会残留，所以每个用例都从
 * 固定两个账号的**全新**存档开始（名字/钻石全部重设）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-rename-flow",
        "sao.world-dir=target/test-data/world-rename-flow",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class RenameFlowSimTest {
    /** 发起改名的账号。 */
    private static final String ACCOUNT = "test-rename-me";
    /** 另一个存档账号（占位名字 / 在线广播的接收方）。 */
    private static final String OTHER = "test-rename-other";

    private static final String NAME_A = "RenameFlowA";
    private static final String NAME_B = "RenameFlowB";
    private static final String NAME_A2 = "RenameFlowA2";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private MessageDispatcher dispatcher;
    @Autowired
    private SessionHub hub;
    @Autowired
    private EconomyTables economy;

    private final List<GameSession> sessions = new ArrayList<>();
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (GameSession s : sessions) {
            hub.unbind(s);
        }
        sessions.clear();
        for (EmbeddedChannel ch : channels) {
            ch.finishAndReleaseAll();
        }
        channels.clear();
    }

    /**
     * 改名成功：回 2301 retCode=0 + newName，扣 {@code changeNameCostRmb} 钻，落档，
     * 且推 106（{@code EAttriType=4} 钻石新值）；自己收不到 2302。
     */
    @Test
    public void renameSuccessDeductsDiamondPushesAttriAndPersists() {
        int cost = economy.changeNameCostRmb;
        Assertions.assertTrue(cost > 0, "GlobalSetup「攻略组改名钻石花费」必须 > 0，当前=" + cost);
        PlayerRecord rec = freshPlayer(ACCOUNT, NAME_A, 500);
        int before = rec.diamond;
        GameSession s = bind(rec);

        dispatcher.dispatch(s, packet(MsgIds.C2S_MODIFY_ROLE_NAME, 11, nameBody(NAME_A2)));

        List<GamePacket> out = drain(s.channel());
        GamePacket ret = firstOf(out, MsgIds.S2C_MODIFY_ROLE_NAME_RET);
        Assertions.assertNotNull(ret, "改名必须回 2301");
        Assertions.assertEquals(11, ret.serial, "2301 必须带请求的 serial");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(0, f.getInt(1, -1), "成功 retCode=0（客户端冒字 100935）");
        Assertions.assertEquals(NAME_A2, f.getString(2), "2301 field2 = 新名字");
        Assertions.assertEquals(NAME_A2, rec.roleName, "存档 roleName 必须改掉");
        Assertions.assertEquals(before - cost, rec.diamond,
                "钻石必须正好扣「攻略组改名钻石花费」=" + cost);
        Assertions.assertEquals(rec.diamond, attriValue(out, 4), "必须推 106 钻石(类型4)新值");
        Assertions.assertEquals(NAME_A2, store.get(ACCOUNT).roleName, "必须已落档（store.get 回同一实例）");
        Assertions.assertFalse(has(out, MsgIds.S2C_UPDATE_PLAYER_ROLE_NAME),
                "改名者自己不该收到 2302（onModifyRoleName 按 account 跳过自己）");
    }

    /**
     * 昵称重复：别的存档已占该名字 → 回 2301 retCode=1，名字与钻石都不动、不推 106。
     */
    @Test
    public void renameDuplicateNameRejectedWithoutCharge() {
        PlayerRecord a = freshPlayer(ACCOUNT, NAME_A, 500);
        freshPlayer(OTHER, NAME_B, 500);
        GameSession s = bind(a);

        dispatcher.dispatch(s, packet(MsgIds.C2S_MODIFY_ROLE_NAME, 12, nameBody(NAME_B)));

        List<GamePacket> out = drain(s.channel());
        GamePacket ret = firstOf(out, MsgIds.S2C_MODIFY_ROLE_NAME_RET);
        Assertions.assertNotNull(ret, "重复昵称也要回 2301");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(1, f.getInt(1, -1), "昵称重复 retCode=1（客户端冒字 100936）");
        Assertions.assertEquals(NAME_A, a.roleName, "被拒后名字不能变");
        Assertions.assertEquals(500, a.diamond, "被拒后不能扣钻");
        Assertions.assertEquals(Integer.MIN_VALUE, attriValue(out, 4), "被拒不该推钻石变更");
    }

    /**
     * 钻石不足：{@code rec.diamond < changeNameCostRmb} → 回 2301 retCode=2，名字与钻石都不动。
     * （客户端没有该分支，只判 0/1：不冒字、不改名、按钮复位；服务端这道是防绕过。）
     */
    @Test
    public void renameWithoutEnoughDiamondRejected() {
        int cost = economy.changeNameCostRmb;
        Assertions.assertTrue(cost > 0, "GlobalSetup「攻略组改名钻石花费」必须 > 0，当前=" + cost);
        PlayerRecord rec = freshPlayer(ACCOUNT, NAME_A, cost - 1);
        GameSession s = bind(rec);

        dispatcher.dispatch(s, packet(MsgIds.C2S_MODIFY_ROLE_NAME, 13, nameBody(NAME_A2)));

        List<GamePacket> out = drain(s.channel());
        GamePacket ret = firstOf(out, MsgIds.S2C_MODIFY_ROLE_NAME_RET);
        Assertions.assertNotNull(ret, "钻石不足也要回 2301");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(2, f.getInt(1, -1), "钻石不足 retCode=2");
        Assertions.assertEquals(NAME_A, rec.roleName, "钻石不足不能改名");
        Assertions.assertEquals(cost - 1, rec.diamond, "钻石不足不能扣钻");
        Assertions.assertEquals(Integer.MIN_VALUE, attriValue(out, 4), "钻石不足不该推钻石变更");
    }

    /**
     * 在线广播：改名成功后，**另一个在线账号**收到 2302（dynID=改名者 playerId，newName），
     * 改名者自己收不到。两个会话都真的绑进 {@code SessionHub}。
     */
    @Test
    public void renameBroadcastsToOtherOnlinePlayers() {
        PlayerRecord a = freshPlayer(ACCOUNT, NAME_A, 300);
        PlayerRecord b = freshPlayer(OTHER, NAME_B, 300);
        GameSession sa = bind(a);
        GameSession sb = bind(b);
        Assertions.assertTrue(hub.onlineSnapshot().contains(sb),
                "EmbeddedChannel 必须 active 才会进 onlineSnapshot（否则 onModifyRoleName 的广播循环看不到它）");

        dispatcher.dispatch(sa, packet(MsgIds.C2S_MODIFY_ROLE_NAME, 14, nameBody(NAME_A2)));

        List<GamePacket> outA = drain(sa.channel());
        List<GamePacket> outB = drain(sb.channel());
        Assertions.assertEquals(0,
                Pb.read(firstOf(outA, MsgIds.S2C_MODIFY_ROLE_NAME_RET).body).getInt(1, -1),
                "改名者必须收到成功 2301");
        GamePacket upd = firstOf(outB, MsgIds.S2C_UPDATE_PLAYER_ROLE_NAME);
        Assertions.assertNotNull(upd, "另一个在线账号必须收到 2302 改名广播");
        Assertions.assertEquals(0, upd.serial, "广播是服务端主动推，serial=0");
        Pb.Fields f = Pb.read(upd.body);
        Assertions.assertEquals(a.playerId, f.getInt(1, -1), "2302 field1 dynID = 改名者 playerId");
        Assertions.assertEquals(NAME_A2, f.getString(2), "2302 field2 = 新名字");
        Assertions.assertFalse(has(outA, MsgIds.S2C_UPDATE_PLAYER_ROLE_NAME),
                "改名者自己不该收到 2302");
    }

    /* ---------------- 工具 ---------------- */

    /** 取存档并重置成「全新账号」：名字/钻石/邮件全部重设（target 下的存档跨次运行会残留）。 */
    private PlayerRecord freshPlayer(String account, String roleName, int diamond) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), 1, 1, roleName);
        }
        rec.ensureCollections();
        rec.roleName = roleName;
        rec.diamond = diamond;
        rec.mails.clear();
        rec.economy.mailGrantKeys.clear();
        store.save(rec);
        return rec;
    }

    private GameSession bind(PlayerRecord rec) {
        EmbeddedChannel ch = new EmbeddedChannel();
        channels.add(ch);
        GameSession session = new GameSession(ch);
        session.setAccount(rec.account);
        session.setPlayer(rec);
        hub.bind(session);
        sessions.add(session);
        return session;
    }

    private static GamePacket packet(int msgId, int serial, byte[] body) {
        return new GamePacket(msgId, serial, body);
    }

    /** C2S 2101 包体：{@code CCMsgPlayerName.PlayerName(name)} = field 1 字符串。 */
    private static byte[] nameBody(String name) {
        return Pb.write(o -> Pb.string(o, 1, name));
    }

    private static List<GamePacket> drain(io.netty.channel.Channel ch) {
        EmbeddedChannel embedded = (EmbeddedChannel) ch;
        List<GamePacket> out = new ArrayList<GamePacket>();
        GamePacket p;
        int guard = 0;
        while ((p = embedded.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
    }

    private static boolean has(List<GamePacket> list, int msgId) {
        return firstOf(list, msgId) != null;
    }

    private static GamePacket firstOf(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return p;
            }
        }
        return null;
    }

    /** 取 S2C 106（{@code dump.attri}：1=type / 2=value）里指定 EAttriType 的值；没有该类型返回 MIN_VALUE。 */
    private static int attriValue(List<GamePacket> list, int type) {
        for (GamePacket p : list) {
            if (p.msgId != MsgIds.S2C_ATTRI_UPDATE) {
                continue;
            }
            Pb.Fields f = Pb.read(p.body);
            if (f.getInt(1, -1) == type) {
                return f.getInt(2, Integer.MIN_VALUE);
            }
        }
        return Integer.MIN_VALUE;
    }
}
