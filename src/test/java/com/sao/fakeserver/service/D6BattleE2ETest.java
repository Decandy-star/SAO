package com.sao.fakeserver.service;

import com.sao.fakeserver.handler.MessageDispatcher;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.UnionCfg;
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
 * D6 公会三条战斗链路的**端到端**用例：全部从真实入口
 * {@link MessageDispatcher#dispatch(GameSession, GamePacket)} 进，按 APK 真实 C2S 顺序走完
 * 「开始 → 结束 → 奖励 → 协议形状」四段，而不是直接调 {@code UnionService.onXxx}。
 *
 * <p>三条链路：
 * <ol>
 *   <li>{@link #bossWarRoomFullFlow()} 作战室 Boss：1526/1528/1529/1530/1532/1533 + 1525，
 *       校验 1923/1925/1926/1927/1929/1930/1922 与 mailType 3 名次邮。</li>
 *   <li>{@link #unionPvpFullFlow()} 公会战 PvP：1549…1568 全序列 + 结算邮件 12/13/15。</li>
 *   <li>{@link #escortCartFullFlow()} 押镖：1541…1548，发镖/劫镖/掠夺榜/刷新/领奖。</li>
 * </ol>
 *
 * <p>时间窗靠 {@code union.clockSecOverride} 钉住（{@code UnionService.secOfDay()} 优先读它）：
 * 押镖发镖窗口 18:00–20:00、劫镖窗口 20:00–22:00（{@code UnionMaJiuTime.txt}），
 * 公会战报名窗口 19:00–21:00 之外才可报名。
 *
 * <p>另有一条回归用例，见 {@link #auctionShelfKeepsKillLootOnFirstOpen()}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-d6-e2e",
        "sao.world-dir=target/test-data/world-d6-e2e",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class D6BattleE2ETest {

    private static final String OWNER = "test-d6-e2e-owner";
    private static final String MEMBER = "test-d6-e2e-member";
    private static final String RIVAL = "test-d6-e2e-rival";

    @Autowired
    PlayerStore store;
    @Autowired
    PlayerDumpService dump;
    @Autowired
    UnionService union;
    @Autowired
    WorldStore world;
    @Autowired
    SessionHub hub;
    @Autowired
    UnionCfg unionCfg;
    @Autowired
    EconomyTables economy;
    @Autowired
    ProgressService progress;
    @Autowired
    MessageDispatcher dispatcher;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (EmbeddedChannel c : channels) {
            c.finishAndReleaseAll();
        }
        channels.clear();
    }

    // ================================================================== 链路 1：作战室 Boss

    /**
     * 作战室 Boss 全流程（真实路由）：
     * 1530 → 1526 → 1528 → 1529(未杀) → 1529(击杀) → 1532 → 1530 → 1533 → 1526 → 1525。
     *
     * <p>APK 依据：1528 挑战前 1526 拉 Boss 面板；1529 {@code CCMsgFightBossFinishInfo}
     * 的 f1 是**嵌套** {@code CCMsgUnionBossInfo}（章节在嵌套里），服务端必须解嵌套才能定位章节；
     * 1532 打开结算面板（1929），1533 会长花公会晶石重置本章（1930 + 1923）。
     */
    @Test
    public void bossWarRoomFullFlow() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "E2E王会", "direct");
        // 作战室（建筑类型 6）等级 5 ⇒ UnionBoss.txt 的章节解锁列全开，1923 才有章节列表。
        u.crystal = 100000;
        u.buildings.put(Integer.valueOf(6), Integer.valueOf(5));
        world.saveUnions();
        drain(ch);

        UnionCfg.BossRow row = unionCfg.boss(1);
        Assertions.assertNotNull(row, "UnionBoss.txt 必须有第 1 章");

        // 先开一次拍卖页（1530）：ensureAuction 会把 u.auctionDay 刷成今天。
        // 真实客户端进作战室拍卖页也走这一路；不先走的话，击杀后第一次 1530 会把刚掉的货清掉
        // （见 auctionShelfKeepsKillLootOnFirstOpen，已单独钉住）。
        send(session, MsgIds.C2S_AUCTION_INFO, 9, new byte[0]);
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_AUCTION_INFO), "1530 → 1927");

        // ---- 1526 拉 Boss 面板 → 1923
        send(session, MsgIds.C2S_UNION_BOSS_INFO, 10, new byte[0]);
        Pb.Fields info = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_BOSS_INFO).body);
        Assertions.assertTrue(info.fieldKeys().contains(Integer.valueOf(1)), "1923 f1 playTime 必下发");
        Assertions.assertFalse(info.getBytesList(3).isEmpty(), "1923 f3 已解锁章节列表非空");

        // ---- 1528 挑战 → 1925 + 1967 + 1923
        send(session, MsgIds.C2S_UNION_BOSS_FIGHT, 11, Pb.write(o -> Pb.int32Always(o, 1, 1)));
        List<GamePacket> fight = drain(ch);
        Pb.Fields fightRet = Pb.read(pick(fight, MsgIds.S2C_UNION_BOSS_FIGHT_RET).body);
        Assertions.assertEquals(1, Pb.read(fightRet.getBytes(1)).getInt(1, 0),
                "1925 f1 bossInfo.chapterID = 请求章节");
        Assertions.assertTrue(hasIn(fight, MsgIds.S2C_UNION_BOSS_PLAY_TIME), "1528 必须推 1967 次数");
        Assertions.assertTrue(hasIn(fight, MsgIds.S2C_UNION_BOSS_INFO), "1528 必须跟推 1923");
        Assertions.assertEquals(1, owner.guild.bossPlayTimes.get(Integer.valueOf(1)).intValue(),
                "当日次数 1/2（Union.txt 每日次数 2）");

        // ---- 1529 上报伤害（未击杀）→ 1926，每场全额金币/兄弟币/勇气币
        send(session, MsgIds.C2S_UNION_BOSS_RESULT, 12, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 5000);
        }));
        Pb.Fields hit = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_BOSS_RESULT_RET).body);
        for (int field : new int[]{1, 3, 4, 5, 6, 7, 8}) {
            Assertions.assertTrue(hit.fieldKeys().contains(Integer.valueOf(field)),
                    "1926 f" + field + " 必下发（UnionZhanDouJieSuan 消费 1/3/4/5/6/7/8/9/10）");
        }
        Assertions.assertTrue(hit.getInt(3, 0) > 0, "1926 f3 金币 > 0");
        Assertions.assertTrue(hit.getInt(3, 0) <= row.gold, "1926 f3 不超过该章总金币池");
        Assertions.assertEquals(1, u.bossChapter, "未击杀不换章");
        Assertions.assertEquals(row.brotherCoin, owner.guild.brotherCoin, "每场兄弟币");
        Assertions.assertEquals(row.courageCoin, owner.guild.courageCoin, "每场勇气币");

        // ---- 1529 上报伤害（击杀）→ 1926 f9 掉落 + 主动推 1929/1927/1923 + mailType 3 名次邮
        owner.mails.clear();
        store.save(owner);
        int crystalBeforeKill = u.crystal;
        int brotherBeforeKill = owner.guild.brotherCoin;
        int courageBeforeKill = owner.guild.courageCoin;
        // 第二次 1528：服务端 1528 写 bossPending 凭证、1529 凭它结算（防伪造伤害包）。
        // Union.txt 每日次数 2 ⇒ 本章第 2 次挑战合法。
        send(session, MsgIds.C2S_UNION_BOSS_FIGHT, 20, Pb.write(o -> Pb.int32Always(o, 1, 1)));
        drain(ch);
        send(session, MsgIds.C2S_UNION_BOSS_RESULT, 13, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 999999999);
        }));
        List<GamePacket> kill = drain(ch);
        Pb.Fields killRet = Pb.read(pick(kill, MsgIds.S2C_UNION_BOSS_RESULT_RET).body);
        Assertions.assertFalse(killRet.getBytesList(9).isEmpty(), "1926 f9 击杀掉落非空");
        Assertions.assertTrue(hasIn(kill, MsgIds.S2C_BOSS_FINISH_INFO), "击杀主动推 1929 结算面板");
        Assertions.assertTrue(hasIn(kill, MsgIds.S2C_AUCTION_INFO), "击杀主动推 1927 货架");
        Assertions.assertTrue(hasIn(kill, MsgIds.S2C_UNION_BOSS_INFO), "击杀跟推 1923");
        Assertions.assertEquals(2, u.bossChapter, "击杀后推进到第 2 章");
        Assertions.assertFalse(u.auctionLots.isEmpty(), "掉落进作战室货架");
        Assertions.assertEquals(crystalBeforeKill + row.settleCrystal, u.crystal, "击杀结算晶石入公会");
        Assertions.assertEquals(brotherBeforeKill + row.brotherCoin, owner.guild.brotherCoin, "击杀场兄弟币");
        Assertions.assertEquals(courageBeforeKill + row.courageCoin, owner.guild.courageCoin, "击杀场勇气币");
        Assertions.assertEquals(1, owner.mails.size(), "名次奖只走邮件（不内联双发）");
        PlayerRecord.Mail rankMail = owner.mails.get(0);
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_BOSS, rankMail.mailType, "mailType 3 = 公会 Boss 名次奖");
        Assertions.assertEquals(3, rankMail.paras.size(), "邮件参数 = boss 名 / 伤害占比 / 名次");
        Assertions.assertEquals(UnionCfg.rankAward(row.settleBrother, 1), rankMail.brotherCoin,
                "第 1 名兄弟币 = UnionBoss.txt 结算兄弟币阈值表");
        Assertions.assertEquals(UnionCfg.rankAward(row.settleCourage, 1), rankMail.courageCoin,
                "第 1 名勇气币 = UnionBoss.txt 结算勇气币阈值表");

        // ---- 1532 打开结算面板：请求体 f1 是嵌套 CCMsgUnionBossInfo，回 1929
        send(session, MsgIds.C2S_BOSS_FINISH, 14, Pb.write(o ->
                Pb.bytesAlways(o, 1, Pb.write(n -> Pb.int32Always(n, 1, 1)))));
        Pb.Fields finish = Pb.read(pick(drain(ch), MsgIds.S2C_BOSS_FINISH_INFO).body);
        Assertions.assertEquals(1, Pb.read(finish.getBytes(1)).getInt(1, 0),
                "1929 f1 bossInfo.chapterID = 请求里嵌套的章节（解嵌套）");
        Assertions.assertFalse(finish.getBytesList(2).isEmpty(), "1929 f2 goods 非空");

        // ---- 1530 货架（已先开过拍卖页，本场掉落应可见）
        send(session, MsgIds.C2S_AUCTION_INFO, 15, new byte[0]);
        Pb.Fields shelf = Pb.read(pick(drain(ch), MsgIds.S2C_AUCTION_INFO).body);
        Assertions.assertFalse(shelf.getBytesList(1).isEmpty(), "1927 货架含本场掉落");

        // ---- 1533 会长重置本章 → 1930 + 1923 + attri，扣 UnionBoss.txt 刷新消耗晶石
        int crystalBeforeReset = u.crystal;
        send(session, MsgIds.C2S_REST_BOSS, 16, Pb.write(o -> Pb.int32Always(o, 1, 1)));
        List<GamePacket> rest = drain(ch);
        Assertions.assertNotNull(pick(rest, MsgIds.S2C_REST_BOSS), "1533 → 1930");
        Assertions.assertNotNull(pick(rest, MsgIds.S2C_UNION_BOSS_INFO), "1533 → 1923");
        Assertions.assertEquals(crystalBeforeReset - row.refreshCrystal, u.crystal,
                "1533 扣刷新晶石（UnionBoss.txt 刷新消耗列）");
        Assertions.assertTrue(u.damageList(1).isEmpty(), "重置清第 1 章伤害榜");
        Assertions.assertTrue(u.bossHpOf(1, 0) > 0, "重置回满血");

        // ---- 再查 1526 → 1923：第 1 章仍在列表里，但伤害榜已清空（可再打一次）
        send(session, MsgIds.C2S_UNION_BOSS_INFO, 17, new byte[0]);
        Pb.Fields info2 = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_BOSS_INFO).body);
        Pb.Fields chapter1 = null;
        for (byte[] b : info2.getBytesList(3)) {
            Pb.Fields e = Pb.read(b);
            if (e.getInt(1, -1) == 1) {
                chapter1 = e;
                break;
            }
        }
        Assertions.assertNotNull(chapter1, "1923 仍下发第 1 章（重置后可再打）");
        Assertions.assertTrue(chapter1.getBytesList(4).isEmpty(), "重置后第 1 章 hurtRankList 清空");

        // ---- 1525 成员信息 → 1922
        send(session, MsgIds.C2S_UNION_MEMBER_INFO, 18, new byte[0]);
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_UNION_MEMBER_INFO_RET), "1525 → 1922");
    }

    /**
     * 【回归】跨日后没先开拍卖页（1530/1531）就击杀 Boss，本场掉落**必须留住**。
     *
     * <p>原缺陷：击杀分支直接 {@code seedAuction(u, row)}，没有先调 {@code ensureAuction(u)}
     * （只有 1530 {@code UnionService.java:1250} 与 1531 {@code UnionService.java:1277} 会调）；
     * 而 {@code ensureAuction}（{@code UnionService.java:3582-3591}）在
     * {@code !day.equals(u.auctionDay)} 时 {@code u.auctionLots.clear()}。新公会
     * {@code auctionDay} 初值就是 {@code ""}，跨日后也是昨天的日期，所以「当天第一次 1530」
     * 必然把刚掉的货当「过期货架」清一次 ⇒ 击杀奖励凭空消失。
     * 已修：击杀分支先 {@code ensureAuction(u)}（先结算昨天货架）再 {@code seedAuction(u, row)}。
     */
    @Test
    public void auctionShelfKeepsKillLootOnFirstOpen() {
        resetWorld();
        PlayerRecord owner = fresh(MEMBER, "成员");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "E2E货会", "direct");
        u.crystal = 100000;
        u.buildings.put(Integer.valueOf(6), Integer.valueOf(5));
        u.auctionDay = ""; // 新公会 / 跨日后未刷新的状态
        world.saveUnions();
        drain(ch);

        // 1528 先挑战拿 bossPending 凭证，1529 才能结算（C#1：伪造伤害包直接结算的漏洞已堵）
        send(session, MsgIds.C2S_UNION_BOSS_FIGHT, 3, Pb.write(o -> Pb.int32Always(o, 1, 1)));
        drain(ch);
        send(session, MsgIds.C2S_UNION_BOSS_RESULT, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 999999999);
        }));
        List<GamePacket> kill = drain(ch);
        Assertions.assertTrue(hasIn(kill, MsgIds.S2C_AUCTION_INFO), "击杀瞬间推的 1927 里是有货的");
        Assertions.assertFalse(u.auctionLots.isEmpty(), "seedAuction 已把掉落写进货架");

        send(session, MsgIds.C2S_AUCTION_INFO, 2, new byte[0]);
        Pb.Fields shelf = Pb.read(pick(drain(ch), MsgIds.S2C_AUCTION_INFO).body);
        Assertions.assertFalse(shelf.getBytesList(1).isEmpty(),
                "当天第一次 1530 不能吞掉刚掉的货");
        Assertions.assertFalse(u.auctionLots.isEmpty(), "内存货架同样保留本场掉落");
    }

    // ================================================================== 链路 2：公会战 PvP

    /**
     * 公会战 PvP 全流程（真实路由）：
     * 1549 → 1550 → 1551 → 1552 → 1553 → 1554/1555/1556 → 1557/1558 →
     * 1560/1561 → 1564 → 1566 → 1567 → 1562 → 1568 → 结算邮件。
     *
     * <p>用**据点 0**打：{@code canAttackPoint} 的前置链来自 {@code UnionPvPDefPointsInfo.txt}
     * 第 3 列，据点 0 无前置，任何时候都可攻占，不受「必须先打下上一个据点」影响。
     */
    @Test
    public void unionPvpFullFlow() {
        resetWorld();
        // 报名窗口 = 19:00–21:00 之外（UnionPvPTime.txt：19:00 开战、持续 3600s、结束后 3600s 才开放）
        pinClock(4 * 3600);

        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chB = newChannel();
        GameSession sessionA = bind(ownerA, chA);
        GameSession sessionB = bind(ownerB, chB);
        WorldStore.UnionRecord uA = createUnion(sessionA, chA, "E2E甲会", "direct");
        WorldStore.UnionRecord uB = createUnion(sessionB, chB, "E2E乙会", "direct");
        uA.crystal = 5000;
        uB.crystal = 5000;
        world.saveUnions();
        drain(chA);
        drain(chB);

        // 真服流程：先在「我的队伍」面板保存防守预设 1（C2S 202 → formationsByType[24]），
        // 1950 的候选列表里才会有这两支队（用户 m20090 #1：没布防的成员不算候选）。
        for (PlayerRecord p : new PlayerRecord[] {ownerA, ownerB}) {
            List<String> preset = new ArrayList<>();
            preset.add(p.findHeroByIndex(p.mainHeroIndex).id);
            p.setFormationSlots(24, preset);
            store.save(p);
        }

        List<Integer> points = unionCfg.pvpPointIds();
        Assertions.assertFalse(points.isEmpty(), "UnionPvPDefPointsInfo.txt 必须有据点");

        // ---- 1549 报名：扣 500 公会晶石、钉住对手、回纯空包 1946
        int crystalBeforeEnroll = uA.crystal;
        send(sessionA, MsgIds.C2S_UNION_PVP_ENROLL, 1, new byte[0]);
        List<GamePacket> enroll = drain(chA);
        GamePacket enrollRet = pick(enroll, MsgIds.S2C_UNION_PVP_ENROLL_RET);
        Assertions.assertNotNull(enrollRet, "1549 → 1946");
        Assertions.assertEquals(0, enrollRet.body.length, "1946 是纯空包（客户端不反序列化）");
        Assertions.assertTrue(uA.pvpEnrolled, "报名成功");
        Assertions.assertEquals(crystalBeforeEnroll - unionCfg.pvpEnrollCrystal(), uA.crystal,
                "报名扣 UnionPvP.txt 报名消耗的公会晶石");
        Assertions.assertEquals(uB.id, uA.pvpRivalId, "报名即钉住当天对手");

        // ---- 1550 查询报名 → 1947
        send(sessionA, MsgIds.C2S_UNION_PVP_IS_ENROLL, 2, new byte[0]);
        Pb.Fields isEnroll = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_IS_ENROLL_RET).body);
        Assertions.assertTrue(isEnroll.getBool(1), "1947 f1 IsEnRolled");

        // ---- 1551 查据点阵容（此时空）→ 1948
        send(sessionA, MsgIds.C2S_UNION_PVP_DEF_FORMATION, 3, Pb.write(o -> Pb.int32Always(o, 1, 0)));
        Pb.Fields def0 = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_DEF_FORMATION_RET).body);
        Assertions.assertEquals(0, def0.getInt(1, -1), "1948 f1 DefPointIndex");
        Assertions.assertTrue(def0.getBytesList(2).isEmpty(), "尚未布防，阵容列表为空");

        // ---- 1552 布防（据点 0 / 首格索引 0 / formationType 24 = 防守预设 1）→ 纯空包 1949
        send(sessionA, MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 4, Pb.write(o -> {
            Pb.int32Always(o, 1, 0);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, ownerA.playerId);
            Pb.int32Always(o, 4, 24);
        }));
        GamePacket updA = pick(drain(chA), MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET);
        Assertions.assertNotNull(updA, "1552 → 1949");
        Assertions.assertEquals(0, updA.body.length, "1949 是纯空包");
        Assertions.assertEquals(1, uA.pvpFormationsOf(0).size(), "据点 0 有 1 支防守队伍");

        // 乙会也在据点 0 布防（formationIndex=1），供 1564 取到真实 targetTeam
        send(sessionB, MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 5, Pb.write(o -> {
            Pb.int32Always(o, 1, 0);
            Pb.int32Always(o, 2, 1);
            Pb.int32Always(o, 3, ownerB.playerId);
            Pb.int32Always(o, 4, 24);
        }));
        Assertions.assertEquals(0, pick(drain(chB), MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET).body.length,
                "1949 空包（乙会）");
        Assertions.assertEquals(1, uB.pvpFormationsOf(0).size(), "乙会据点 0 有 1 支队伍");

        // ---- 1553 全公会阵容 → 1950
        send(sessionA, MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 6, new byte[0]);
        Pb.Fields allDef = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body);
        Assertions.assertFalse(allDef.getBytesList(1).isEmpty(), "1950 f1 至少一行");
        // 1950 下发的是「候选队伍」：已上阵的行 DefPointsIndex = 据点号，未上阵的行 = -1。
        // 所以不能假定第 0 行就是刚布防的那支，必须按 f7 找。
        int placedRow = -1;
        for (byte[] row : allDef.getBytesList(1)) {
            if (Pb.read(row).getInt(7, -1) == 0) {
                placedRow = 1;
                break;
            }
        }
        Assertions.assertEquals(1, placedRow, "1950 必须含一行 f7 DefPointsIndex = 0（刚布防的据点 0）");

        // ---- 1554/1555/1556 三张榜 → 1951/1952/1953
        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_POWER_RANK, 7, new byte[0]);
        Pb.Fields powerRank = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_FIGHT_POWER_RANK_RET).body);
        Assertions.assertTrue(powerRank.getInt(1, 0) >= 1, "1951 f1 myUnionRank");
        Assertions.assertTrue(powerRank.getInt(2, 0) > 0, "1951 f2 myUnionValue");
        Assertions.assertFalse(powerRank.getBytesList(3).isEmpty(), "1951 f3 榜单非空");

        send(sessionA, MsgIds.C2S_UNION_PVP_GROW_VALUE_RANK, 8, new byte[0]);
        Assertions.assertNotNull(pick(drain(chA), MsgIds.S2C_UNION_PVP_GROW_VALUE_RANK_RET), "1555 → 1952");

        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_RANK, 9, new byte[0]);
        Pb.Fields fightRank = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET).body);
        // 此刻还没走 1567 上报战绩，所以本会战绩榜必然无名次（f1 myUnionRank 合法为 0），
        // 且没有任何公会有战绩 ⇒ f4 榜单合法为空（pvpFightRankList 只在 items 非空时写 f4）。
        // 战绩名次留到 1567 之后再查一次 1556（见下方「1567 之后再查一次 1556」）。
        Assertions.assertTrue(fightRank.fieldKeys().contains(Integer.valueOf(1)), "1953 f1 myUnionRank 字段存在");
        Assertions.assertEquals(0, fightRank.getInt(2, -1), "1953 f2 mywinTimes（尚未上报战绩）");
        Assertions.assertTrue(fightRank.getBytesList(4).isEmpty(), "1953 f4 榜单此刻合法为空");

        // ---- 1557/1558 公会简报 → 1954/1955
        send(sessionA, MsgIds.C2S_UNION_PVP_BRIEF_FIGHT_POWER, 10, Pb.write(o -> Pb.stringAlways(o, 1, uB.id)));
        Pb.Fields brief = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_BRIEF_FIGHT_POWER_RET).body);
        Assertions.assertFalse(Pb.read(brief.getBytes(1)).getString(1).isEmpty(), "1954 f1 rankItem.guid 非空");
        Assertions.assertEquals("乙会长", brief.getString(2), "1954 f2 owner");

        send(sessionA, MsgIds.C2S_UNION_PVP_BRIEF_GROW_VALUE, 11, Pb.write(o -> Pb.stringAlways(o, 1, uB.id)));
        Assertions.assertNotNull(pick(drain(chA), MsgIds.S2C_UNION_PVP_BRIEF_GROW_VALUE_RET), "1558 → 1955");

        // ---- 1560/1561 据点简报 → 1957/1958
        send(sessionA, MsgIds.C2S_UNION_PVP_MY_DEF_POINT_BRIEF, 12, new byte[0]);
        Pb.Fields myBrief = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_MY_DEF_POINT_BRIEF_RET).body);
        Assertions.assertEquals(points.size(), myBrief.getBytesList(1).size(), "1957 每据点一行");
        Assertions.assertEquals(0, Pb.read(myBrief.getBytesList(1).get(0)).getInt(1, -1), "1957 行 f1 PointIndex");

        send(sessionA, MsgIds.C2S_UNION_PVP_TARGET_DEF_POINT_BRIEF, 13, new byte[0]);
        Assertions.assertNotNull(pick(drain(chA), MsgIds.S2C_UNION_PVP_TARGET_DEF_POINT_BRIEF_RET), "1561 → 1958");

        // ---- 1564 开战：1961 的 myTeam / targetTeam **都必须非空**
        drain(chB);
        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 14, Pb.write(o -> {
            Pb.int32Always(o, 1, 0);
            Pb.int32Always(o, 2, 1);
        }));
        List<GamePacket> fightOpen = drain(chA);
        Pb.Fields team = Pb.read(pick(fightOpen, MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION).body);
        Assertions.assertFalse(team.getBytes(1).length == 0, "1961 f1 myTeam 必须非空（APK Client\\ᝁ.cs:6404-6406）");
        Assertions.assertFalse(team.getBytes(2).length == 0, "1961 f2 targetTeam 必须非空");
        Assertions.assertEquals(ownerB.playerId, Pb.read(team.getBytes(2)).getInt(1, -1),
                "1961 f2 targetTeam 是乙会的防守队伍");
        Assertions.assertEquals(1, uB.pvpAttackerCnt, "被攻方计数 +1");
        Assertions.assertTrue(hasIn(drain(chB), MsgIds.S2C_UNION_PVP_POINT_ATTACKERS), "1963 推给被攻方在线成员");

        // ---- 1566 阵亡武将血量 → 1964（两个 repeated 等长）
        send(sessionA, MsgIds.C2S_UNION_PVP_WJ_HP, 15, new byte[0]);
        Pb.Fields dead = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_WJ_HP_RET).body);
        Assertions.assertFalse(dead.getInts(1).isEmpty(), "1964 f1 WJIndex 非空");
        Assertions.assertEquals(dead.getInts(1).size(), dead.getInts(2).size(), "1964 f1/f2 必须等长");

        // ---- 1567 上报结果（胜）→ 1913 + 1965，攻占据点 0
        int brotherBefore = ownerA.guild.brotherCoin;
        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_RESULT, 16, Pb.write(o -> Pb.boolAlways(o, 1, true)));
        List<GamePacket> result = drain(chA);
        Pb.Fields push = Pb.read(pick(result, MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH).body);
        Assertions.assertTrue(push.getBool(1), "1965 f1 IsWin");
        Assertions.assertEquals(1, push.getInt(4, -1),
                "1965 f4 SelfAttackedDefPoints 我方攻陷 = 1（Code.txt 100760「我方攻陷： 」）");
        Assertions.assertEquals(0, push.getInt(5, -1),
                "1965 f5 TargetAttackedDefPoints 敌方攻陷 = 0（Code.txt 100761「敌方攻陷： 」）");
        Assertions.assertEquals(1, uA.pvpWinTimes, "胜场 +1");
        Assertions.assertEquals(0, uA.pvpFailTimes, "败场不变");
        Assertions.assertTrue(uA.pvpCapturedPoints.contains(Integer.valueOf(0)), "据点 0 被攻占");
        Assertions.assertEquals(1, uA.pvpRecords.size(), "战报 +1");
        Assertions.assertEquals(brotherBefore + unionCfg.pvpUnitBrother(), ownerA.guild.brotherCoin,
                "遭遇战单场兄弟币（整场奖励不在这里发）");

        // ---- 1567 之后再查一次 1556：本会已有 1 个胜场 ⇒ 名次上榜、f4 榜单非空
        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_RANK, 19, new byte[0]);
        Pb.Fields rankAfter = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET).body);
        Assertions.assertTrue(rankAfter.getInt(1, 0) >= 1, "1953 f1 myUnionRank 上榜");
        Assertions.assertEquals(1, rankAfter.getInt(2, -1), "1953 f2 mywinTimes = 1");
        Assertions.assertFalse(rankAfter.getBytesList(4).isEmpty(), "1953 f4 榜单非空（已有战绩）");
        Pb.Fields rankItem = Pb.read(rankAfter.getBytesList(4).get(0));
        Assertions.assertEquals(uA.id, rankItem.getString(1), "1953 行 f1 guid = 本会");
        Assertions.assertEquals(1, rankItem.getInt(6, -1), "1953 行 f6 winTimes");

        // ---- 1562 剩余阵容 → 1959
        send(sessionA, MsgIds.C2S_UNION_PVP_LEFT_DEF_FORMATION, 17, new byte[0]);
        Pb.Fields left = Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_LEFT_DEF_FORMATION_RET).body);
        Assertions.assertEquals(points.size(), left.getBytesList(3).size(), "1959 f3 每据点一行");
        Assertions.assertEquals(1, left.getInt(1, -1), "1959 f1 selfAttackedDefPoints 我方攻陷 = 1");
        Assertions.assertEquals(0, left.getInt(2, -1), "1959 f2 targetAttackedDefPoints 敌方攻陷 = 0");
        // 1959 f3 IsBeAttacked 是「我方该据点已被敌方攻陷」（GongHuiZhanJuDianBoard.cs:240-255 切 LabelAllDead）。
        // 本场是甲会攻下乙会据点 0，甲会自己的据点一个都没失守 ⇒ 全部 false。
        // 改前该字段错读 mine.pvpCapturedPoints（甲会已攻陷 1 个）⇒ 甲会据点 0 会变成 true，这里能锁住。
        for (byte[] row : left.getBytesList(3)) {
            Pb.Fields one = Pb.read(row);
            Assertions.assertFalse(one.getBool(3),
                    "1959 f3 据点 " + one.getInt(1, 0) + "：甲会自己没失守，不能显示「全员阵亡」");
        }

        // ---- 1568 拉战报 → 1965（整场胜负按据点数判定：1 > 0 = 胜）
        send(sessionA, MsgIds.C2S_UNION_PVP_FIGHT_RECORD_LIST, 18, new byte[0]);
        Assertions.assertTrue(Pb.read(pick(drain(chA), MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH).body).getBool(1),
                "1568 → 1965 IsWin");

        // ---- 整场结算：settlePvpBattles() 被真实时钟（pvpWindowOver 用 GameTime.localTime）门控，
        // 端到端只能直调包级 settlePvpBattle（D6FlowSimTest 同做法）。
        // ⚠ 必须同时钉 pvpEnrollDay：报名（本用例上文 :322）是按真实时钟算的
        // targetPvpBattleDay(secOfDay())，而 UnionPvPTime.txt 的战斗日只有周一/三/六（第 1/3/6 行带 #）。
        // 非战斗日跑这个用例时，报名落的是「下一场」（例如周二报名 → 周三），
        // settlePvpBattle 的 :3356 守卫（battleDay != pvpEnrollDay 直接 return）就会拦住结算，
        // 导致本断言拿到 0。测试原本只钉了 pvpDay，因此在非战斗日会假失败（与产品逻辑无关）。
        String day = PlayerDumpService.now().substring(0, 10);
        uA.pvpDay = day;
        uA.pvpEnrollDay = day;
        uA.pvpEnrolled = true;
        uA.pvpRivalId = uB.id;
        uB.pvpDay = day;
        uB.pvpEnrollDay = day;
        uB.pvpEnrolled = true;
        world.saveUnions();
        ownerA.mails.clear();
        ownerB.mails.clear();
        store.save(ownerA);
        store.save(ownerB);

        int growthBeforeA = uA.growth;
        int crystalBeforeA = uA.crystal;
        union.settlePvpBattle(uA, day, "e2e");
        Assertions.assertEquals(growthBeforeA + unionCfg.pvpWinGrow(), uA.growth, "胜方公会成长");
        Assertions.assertEquals(crystalBeforeA + unionCfg.pvpWinCrystal(), uA.crystal, "胜方公会晶石");
        Assertions.assertEquals(1, ownerA.mails.size(), "结算给每个成员一封邮件");
        PlayerRecord.Mail winMail = ownerA.mails.get(0);
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_POINT_WIN, winMail.mailType,
                "mailType 12 = 据点胜（我方 1 : 0）");
        Assertions.assertEquals("E2E乙会", winMail.paras.get(0), "邮件参数 = 对手公会名");
        Assertions.assertEquals(unionCfg.pvpWinBrother(), winMail.brotherCoin, "胜方兄弟币");

        int growthBeforeB = uB.growth;
        union.settlePvpBattle(uB, day, "e2e");
        Assertions.assertEquals(growthBeforeB + unionCfg.pvpLoseGrow(), uB.growth, "败方公会成长");
        Assertions.assertEquals(1, ownerB.mails.size(), "败方也发邮件");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_POINT_LOSE, ownerB.mails.get(0).mailType,
                "mailType 13 = 据点负");
        Assertions.assertEquals(unionCfg.pvpLoseBrother(), ownerB.mails.get(0).brotherCoin, "败方兄弟币");

        // ---- 幂等：同一天再结算一次不得重复发奖
        union.settlePvpBattle(uA, day, "e2e-again");
        Assertions.assertEquals(1, ownerA.mails.size(), "pvpSettledDay 去重，不重复发邮");

        // ---- mailType 15「对方公会解散」：pvpRivalId 非空但对端已不存在
        ownerA.mails.clear();
        store.save(ownerA);
        uA.pvpRivalId = "u-ghost"; // 非空 ⇒ 判为「对手解散」而不是「轮空」
        uA.pvpEnrolled = true;
        // 注意：rivalUnion(u) 不是按 pvpRivalId 查表的，而是从公会列表里挑对手，
        // 所以必须把 uB 真正移出世界，才能走到「对手不存在」这条分支。
        world.unions().remove(uB);
        world.saveUnions();
        union.settlePvpBattle(uA, "2099-01-01", "e2e-dismiss");
        Assertions.assertEquals(1, ownerA.mails.size(), "对手解散仍要发结算邮");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_RIVAL_DISMISS,
                ownerA.mails.get(0).mailType, "mailType 15 = 对方公会解散");

        // ---- mailType 16「轮空」：同样没有对手，但 pvpRivalId 为空
        ownerA.mails.clear();
        store.save(ownerA);
        uA.pvpRivalId = "";
        uA.pvpEnrolled = true;
        world.saveUnions();
        union.settlePvpBattle(uA, "2099-01-02", "e2e-bye");
        Assertions.assertEquals(1, ownerA.mails.size(), "轮空仍要发结算邮");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_BYE,
                ownerA.mails.get(0).mailType, "mailType 16 = 轮空");
    }

    // ================================================================== 链路 3：押镖（马厩）

    /**
     * 押镖全流程（真实路由）：1541 → 1542 → 1543(发镖) → 1544(劫镖) → 1545(掠夺结算) →
     * 1546(掠夺榜) → 1547(刷新) → 1548(领奖，先验相位门控再验领奖)。
     *
     * <p>时间窗：{@code UnionMaJiuTime.txt} 18:00 起 7200s 发镖 + 7200s 劫镖
     * ⇒ 发镖 [18:00,20:00)、劫镖 [20:00,22:00)、其余相位 3。故用 18:30 / 20:30 / 23:00。
     */
    @Test
    public void escortCartFullFlow() {
        resetWorld();
        pinClock(18 * 3600 + 1800); // 18:30 发镖窗口（phase 1）

        PlayerRecord ownerA = fresh(OWNER, "镖会长");
        PlayerRecord raider = fresh(RIVAL, "劫镖侠");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chB = newChannel();
        GameSession sessionA = bind(ownerA, chA);
        GameSession sessionB = bind(raider, chB);
        WorldStore.UnionRecord uA = createUnion(sessionA, chA, "E2E镖会", "direct");
        WorldStore.UnionRecord uB = createUnion(sessionB, chB, "E2E劫会", "direct");
        uA.crystal = 1000;
        uB.crystal = 1000;
        // 马厩要求议事厅 2 级（tables\Union.txt 本轮新落地的解锁闸门）
        uA.buildings.put(Integer.valueOf(1), Integer.valueOf(2));
        uB.buildings.put(Integer.valueOf(1), Integer.valueOf(2));
        world.saveUnions();
        drain(chA);
        drain(chB);

        // 目的地 1「迷茫沼泽」要马厩 1 级 + 阵容总战力 40000（UnionMaJiuTarget.txt）
        PlayerRecord.Hero main = ownerA.findHeroByIndex(ownerA.mainHeroIndex);
        Assertions.assertNotNull(main, "必须有主将");
        main.fightPower = 50000;
        store.save(ownerA);
        // 先把当日额度算清楚（ensureDaily 会把 escortRaidLeft 按 VIP 档位重设；
        // 发镖额度不再是计数器，而是「未领奖的在途镖车数 < VipCfg 第 27 列 FaBiaoCount」）
        progress.ensureDaily(ownerA);
        progress.ensureDaily(raider);
        store.save(ownerA);
        store.save(raider);
        drain(chA);
        drain(chB);

        // ---- 1541 押镖面板 → 1936
        send(sessionA, MsgIds.C2S_MAJIU_INFO, 1, new byte[0]);
        Pb.Fields panel = Pb.read(pick(drain(chA), MsgIds.S2C_MAJIU_INFO).body);
        Assertions.assertEquals(1, panel.getInt(1, -1), "1936 f1 phase = 1（发镖窗口）");
        Assertions.assertTrue(panel.getInt(2, 0) > 0 && panel.getInt(2, 0) <= 86400, "1936 f2 leftSec");
        Assertions.assertEquals(0, panel.getInt(3, -1), "1936 f3 resetTime 初始 0");
        Assertions.assertTrue(panel.getBytesList(7).isEmpty(), "还没发车，f7 myCartIds 为空");

        // ---- 1542 我的镖车 → 1937
        send(sessionA, MsgIds.C2S_MAJIU_MY_BIAO, 2, new byte[0]);
        Assertions.assertNotNull(pick(drain(chA), MsgIds.S2C_MAJIU_MY_BIAO), "1542 → 1937");

        // ---- 1543 发镖 → 1938（f1 不能空，客户端 MaJiuBasePaiQianUI.cs:123 直接取 [0]）+ 1936
        Assertions.assertTrue(ownerA.economy.escortCarts.isEmpty(), "发镖前无在途镖车");
        send(sessionA, MsgIds.C2S_SEND_CART, 3, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.bytesAlways(o, 2, Pb.write(f -> {
                Pb.int32Always(f, 1, PlayerRecord.FORMATION_MAJIU);
                Pb.stringAlways(f, 2, main.id);
            }));
        }));
        List<GamePacket> sent = drain(chA);
        GamePacket sendRet = pick(sent, MsgIds.S2C_SEND_CART);
        Assertions.assertNotNull(sendRet, "1543 → 1938");
        Assertions.assertFalse(Pb.read(sendRet.body).getBytesList(1).isEmpty(), "1938 f1 镖车列表非空");
        Assertions.assertTrue(hasIn(sent, MsgIds.S2C_MAJIU_INFO), "1543 跟推 1936");
        Assertions.assertEquals(1, ownerA.economy.escortCarts.size(), "在途镖车 1 辆");
        PlayerRecord.Economy.EscortCart cartA = ownerA.economy.escortCarts.get(0);
        Assertions.assertFalse(cartA.cartId.isEmpty(), "镖车已发（车 GUID 由 1938 下发）");
        Assertions.assertEquals(1, cartA.targetId, "目的地 = 1");
        Assertions.assertFalse(cartA.awarded, "未领奖");
        // 1938 只放刚发的那一辆车（客户端只读 [0]），其 f1 biaoCheGUID 必须就是这辆车的 id
        Pb.Fields sentRow = Pb.read(Pb.read(sendRet.body).getBytesList(1).get(0));
        Assertions.assertEquals(cartA.cartId, sentRow.getString(1), "1938 [0].f1 = 刚发车的 GUID");

        // ---- 切到劫镖窗口 20:30（phase 2）
        pinClock(20 * 3600 + 1800);

        // ---- 1544 劫镖（f2 = raidPlayerGUID，f3 = 目标镖车 GUID）→ 1939 ret=0 + f4 被劫方阵容
        int raidLeftBefore = raider.economy.escortRaidLeft;
        Assertions.assertTrue(raidLeftBefore > 0, "当日劫镖额度 > 0");
        send(sessionB, MsgIds.C2S_RAID_CART, 4, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, ownerA.playerId);
            Pb.stringAlways(o, 3, cartA.cartId);
        }));
        Pb.Fields raidRet = Pb.read(pick(drain(chB), MsgIds.S2C_RAID_CART).body);
        Assertions.assertEquals(0, raidRet.getInt(1, -1), "1939 f1 ret = 0（客户端只在 ret==0 进战斗）");
        Assertions.assertFalse(raidRet.getBytes(4).length == 0, "1939 f4 被劫方阵容非空");
        Assertions.assertEquals("劫镖侠", cartA.raiderName, "被劫方这辆车记下劫镖者");

        // ---- 1545 上报掠夺成功 → 1940 XDB / JinShi / jinbi
        int lineGold = unionCfg.majiuTarget(1).gold;
        int goldBeforeOwner = ownerA.gold;
        int goldBeforeRaider = raider.gold;
        int crystalBeforeRaid = uB.crystal;
        int growthBeforeRaid = uB.growth;
        send(sessionB, MsgIds.C2S_RAID_RESULT, 5, Pb.write(o -> Pb.boolAlways(o, 1, true)));
        Pb.Fields loot = Pb.read(pick(drain(chB), MsgIds.S2C_RAID_RESULT).body);
        int expectLoot = (int) Math.floor(lineGold * unionCfg.raidGainRatio());
        int expectCrystal = (int) Math.floor(expectLoot * unionCfg.raidXdbRatio());
        Assertions.assertEquals(expectLoot, loot.getInt(3, -1), "1940 f3 jinbi = 线路金币 × 掠夺比例");
        Assertions.assertTrue(loot.getInt(2, -1) > 0, "1940 f2 JinShi > 0（劫掠资金兑公会晶石）");
        Assertions.assertTrue(loot.getInt(1, -1) > 0, "1940 f1 XDB > 0（掠夺排行兄弟币）");
        Assertions.assertEquals(goldBeforeRaider + expectLoot, raider.gold, "劫镖方入金币");
        Assertions.assertEquals(goldBeforeOwner - (int) Math.floor(lineGold * unionCfg.beRaidLoseRatio()),
                ownerA.gold, "被劫方按被劫扣除比例扣金币");
        Assertions.assertEquals(1, cartA.beRaidCnt, "这辆车被劫次数 +1");
        Assertions.assertTrue(cartA.hasBeenRaid, "这辆车已被打劫成功（不可再劫）");
        Assertions.assertEquals(1, raider.economy.escortRaidSucTimes, "掠夺成功场次 +1");
        Assertions.assertEquals(raidLeftBefore - 1, raider.economy.escortRaidLeft, "劫镖额度 -1");
        Assertions.assertEquals(crystalBeforeRaid + expectCrystal, uB.crystal, "劫镖成功给劫方公会加晶石");
        Assertions.assertEquals(growthBeforeRaid + unionCfg.raidGrow(), uB.growth, "劫镖成功给劫方公会加成长");

        // ---- 1546 掠夺榜 → 1941
        send(sessionB, MsgIds.C2S_RAID_RANK, 6, new byte[0]);
        Pb.Fields rankList = Pb.read(pick(drain(chB), MsgIds.S2C_RAID_RANK).body);
        Assertions.assertFalse(rankList.getBytesList(1).isEmpty(), "1941 f1 榜单非空（有掠夺记录）");
        Pb.Fields rankRow = Pb.read(rankList.getBytesList(1).get(0));
        Assertions.assertEquals(raider.playerId, rankRow.getInt(1, -1), "1941 行 f1 playerGuid");
        Assertions.assertTrue(rankRow.getInt(7, 0) > 0, "1941 行 f7 raidJinbi > 0");

        // ---- 1547 刷新（回 1936 形状）→ 1942
        int crystalBeforeRefresh = uA.crystal;
        send(sessionA, MsgIds.C2S_REFRESH_RAID, 7, new byte[0]);
        Pb.Fields refreshed = Pb.read(pick(drain(chA), MsgIds.S2C_REFRESH_RAID).body);
        Assertions.assertEquals(union.majiuPhase(), refreshed.getInt(1, -1),
                "1942 是 1936 形状：f1 phase（此刻已钉在劫镖窗口 = 2）");
        Assertions.assertEquals(1, refreshed.getInt(3, -1), "1942 f3 resetTime = 已刷新次数 1");
        Assertions.assertEquals(1, ownerA.economy.majiuResetTimes, "刷新次数计 1（上限 2）");
        // UnionMaJiuBase.txt 各等级「刷新所需公会晶石」全为 0 ⇒ 消耗跟表 = 0，**免费刷新**
        // （用户 m19522 #2 拍板，撤销 m19006 #11 的 10/20/40 兜底：客户端面板按同一列显示 0，
        // 服务端实扣会造成显示不一致）。表里填上非 0 值后扣费由 UnionService.refreshRaidCost 跟表。
        Assertions.assertEquals(crystalBeforeRefresh, uA.crystal,
                "表值为 0 ⇒ 刷新免费（用户 m19522 #2）");

        // ---- 1548 相位门控：仍在劫镖窗口（phase 2）→ 只回拒绝包，不动档
        send(sessionA, MsgIds.C2S_YUNBIAO_AWARD, 8, new byte[0]);
        Pb.Fields early = Pb.read(pick(drain(chA), MsgIds.S2C_YUNBIAO_AWARD).body);
        Assertions.assertEquals(0, early.getInt(2, -1), "phase≠3 时不发金币");
        Assertions.assertEquals(0, early.getInt(3, -1), "phase≠3 时不发 XDB");
        Assertions.assertFalse(cartA.awarded, "phase≠3 时不置已领奖");

        // ---- 切到 phase 3（23:00）→ 1548 真正领奖 → 1943 + 1913 + 1936
        pinClock(23 * 3600);
        int goldBeforeAward = ownerA.gold;
        int brotherBeforeAward = ownerA.guild.brotherCoin;
        int growthBeforeAward = uA.growth;
        send(sessionA, MsgIds.C2S_YUNBIAO_AWARD, 9, new byte[0]);
        List<GamePacket> award = drain(chA);
        Pb.Fields awardRet = Pb.read(pick(award, MsgIds.S2C_YUNBIAO_AWARD).body);
        Assertions.assertNotNull(pick(award, MsgIds.S2C_UNION_PLAYER_RES), "1548 → 1913");
        Assertions.assertNotNull(pick(award, MsgIds.S2C_MAJIU_INFO), "1548 → 1936");
        Assertions.assertEquals(0, awardRet.getInt(1, 0),
                "1943 f1 raidRank：ownerA 从未劫镖 ⇒ 0（客户端 MaJiuGetAwardUI.cs:74-83 只在 ≠0 时留「掠夺红利」行）");
        Assertions.assertEquals(0, awardRet.getInt(2, -1),
                "1943 f2 raidJinBi 是**劫镖**红利，不是发镖金币（发镖收益走 f5 yunBiaoAward）");
        Assertions.assertEquals(0, awardRet.getInt(3, -1),
                "1943 f3 raidXdb 是**劫镖**兄弟币");
        Assertions.assertEquals(0, awardRet.getInt(4, 0),
                "1943 f4 raidJinShi = 我方劫镖晶石合计（本用例 ownerA 是被劫方，从未劫镖 ⇒ 0）");
        Assertions.assertEquals(goldBeforeAward + lineGold, ownerA.gold, "领奖入金币");
        Assertions.assertEquals(brotherBeforeAward + economy.escortSendXdb(), ownerA.guild.brotherCoin,
                "发镖参与奖入兄弟币");
        Assertions.assertEquals(growthBeforeAward + unionCfg.sendGrow(), uA.growth, "运镖成功给公会加成长");
        Assertions.assertTrue(cartA.awarded, "这辆车已领奖");
        Assertions.assertTrue(ownerA.economy.majiuAwarded, "活动已领奖标记");

        // 1943 f5 领奖明细：1 项，f1 targeID / f3 jinBi / f4 XDB / f5 jinShi
        Assertions.assertEquals(1, awardRet.getBytesList(5).size(), "1943 f5 一条领奖明细");
        Pb.Fields item = Pb.read(awardRet.getBytesList(5).get(0));
        Assertions.assertEquals(1, item.getInt(1, -1), "明细 f1 targeID");
        Assertions.assertEquals(1, item.getInt(2, -1), "明细 f2 beRaidCnt（本场被劫 1 次）");
        Assertions.assertEquals(lineGold, item.getInt(3, -1), "明细 f3 jinBi");
        Assertions.assertEquals(economy.escortSendXdb(), item.getInt(4, -1), "明细 f4 XDB");
        Assertions.assertEquals(0, item.getInt(5, -1), "明细 f5 jinShi 恒 0");
        // 被劫过但没守住 ⇒ 没有防守成功奖励
        Assertions.assertTrue(awardRet.getBytesList(6).isEmpty(), "1943 f6 无防守奖励明细");
    }

    // ================================================================== 脚手架

    private void resetWorld() {
        world.unions().clear();
        world.saveUnions();
        // 时钟锚点是单例服务上的字段，用例之间必须复位，否则上一个用例钉的窗口会污染本用例。
        union.clockSecOverride = -1;
    }

    /** 钉住「当日秒数」，用于验证押镖/公会战的时间窗。 */
    private void pinClock(int secOfDay) {
        union.clockSecOverride = secOfDay;
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        channels.add(ch);
        return ch;
    }

    private GameSession bind(PlayerRecord rec, EmbeddedChannel ch) {
        GameSession session = new GameSession(ch);
        session.setAccount(rec.account);
        session.setPlayer(rec);
        hub.bind(session);
        return session;
    }

    /** 走真实路由发一条 C2S。 */
    private void send(GameSession session, int msgId, int serial, byte[] body) {
        dispatcher.dispatch(session, new GamePacket(msgId, serial, body == null ? new byte[0] : body));
    }

    private WorldStore.UnionRecord createUnion(GameSession session, EmbeddedChannel ch, String name,
                                               String joinType) {
        send(session, MsgIds.C2S_CREATE_UNION, 0, Pb.write(o -> {
            Pb.stringAlways(o, 1, name);
            Pb.stringAlways(o, 2, "icon1");
        }));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_CREATE_UNION_RET);
        Assertions.assertNotNull(ret, "创建公会必须有 1901");
        Assertions.assertTrue(Pb.read(ret.body).getBool(1), "创建公会失败（名字须 ≤7 字且等级/钻石达标）");
        WorldStore.UnionRecord u = world.findUnionByName(name);
        Assertions.assertNotNull(u);
        u.joinType = joinType;
        world.saveUnions();
        return u;
    }

    /** 与 D6FlowSimTest.fresh 同一套跨用例复位（两个用例共用同一 data-dir，必须逐字段清）。 */
    private PlayerRecord fresh(String account, String roleName) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), 18, 1, roleName);
        }
        rec.ensureCollections();
        rec.roleName = roleName;
        rec.level = 40;
        rec.diamond = 5000;
        rec.gold = 1000000;
        PlayerRecord.Hero main = rec.findHeroByIndex(rec.mainHeroIndex);
        if (main != null) {
            main.fightPower = 1000;
        }
        rec.guild.id = "";
        rec.guild.name = "";
        rec.guild.job = "none";
        rec.guild.donateLeft = 10;
        rec.guild.contribution = 0;
        rec.guild.contributionGrowRemainder = 0;
        rec.guild.brotherCoin = 0;
        rec.guild.courageCoin = 0;
        rec.guild.quitUnionAt = 0L;
        rec.guild.bossPlayTimes.clear();
        rec.qkDeadWjs.clear();
        rec.economy.lastPvpPoint = -1;
        rec.economy.lastPvpFormation = -1;
        rec.economy.trainSlots.clear();
        rec.economy.escortCarts.clear();
        rec.economy.raidTargetCartId = "";
        rec.economy.escortRaidLeft = 3;
        rec.economy.raidTargetId = "";
        rec.economy.escortRaidSucTimes = 0;
        rec.economy.escortDefendGold = 0;
        rec.economy.escortDefendUnion = "";
        rec.economy.majiuDay = "";
        rec.economy.majiuResetTimes = 0;
        rec.economy.majiuAwarded = false;
        rec.economy.raidJinbiTotal = 0;
        rec.economy.raidXdbTotal = 0;
        rec.economy.raidJinShiTotal = 0;
        rec.economy.buildingProfitAt.clear();
        rec.economy.buildingProfitTotal.clear();
        store.save(rec);
        return rec;
    }

    /** 一次性读完 outbound（**重复调用会吞包**，所以每个 send 只 drain 一次）。 */
    private static List<GamePacket> drain(EmbeddedChannel ch) {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
    }

    private static boolean hasIn(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return true;
            }
        }
        return false;
    }

    private static GamePacket pick(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return p;
            }
        }
        return null;
    }
}
