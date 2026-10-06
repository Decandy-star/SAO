package com.sao.fakeserver.service;

import com.sao.fakeserver.fight.CombatAttrCalculator;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
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
 * 装备域 C2S 闸门按 APK 口径的回归测试（用户 m23207「检查装备这块的所有协议」）。
 *
 * <p>覆盖四处「服务端比客户端宽」的缺漏：
 * <ul>
 *   <li>1102 穿装备不校验职业（客户端可穿戴谓词要求武器/配件职业匹配）；</li>
 *   <li>1118 飞跃不要求「已开淬炼 + 四部位全开」（{@code EquipmentDetail.EquipReadyFeiYue}）；</li>
 *   <li>1113 一键强化零成果时一个包都不回（客户端 {@code EquipOneKeyLevelUpRet} 停在等待态）；</li>
 *   <li>1605 天赋锁只存档不推 4001（客户端没注册 1605 的 S2C）。</li>
 * </ul>
 * 外加装备卖价必须读 {@code EquipmentList.txt} 的金币价格列（客户端显示的就是它）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-equip-gate",
        "sao.world-dir=target/test-data/world-equip-gate",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class EquipFlowTest {
    private static final String ACCOUNT = "test-equip-gate";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private CultivateService cultivateService;
    @Autowired
    private SideService side;
    @Autowired
    private DungeonService dungeon;
    @Autowired
    private CultivateTables tables;
    @Autowired
    private SessionHub hub;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (EmbeddedChannel c : channels) {
            c.finishAndReleaseAll();
        }
        channels.clear();
    }

    /**
     * C2S 1102：客户端可穿戴谓词（{@code WuJiangContainerSystem.cs:1797}）要求武器/配件
     * {@code equipJobType == 武将 mWeaponJobAttribute/mPeiJianJobAttribute}，
     * 1104 自动穿戴走同一谓词；1102 改前只查装备/武将存在 ⇒ 任何职业的武器都能塞给任何武将。
     */
    @Test
    public void putOnRequiresMatchingJob() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        PlayerRecord.Hero wj = rec.findHeroByIndex(18);
        Assertions.assertNotNull(wj, "新号主将 = hero 18");

        PlayerRecord.Equipment bad = equip(rec, "EQ0013");
        PlayerRecord.Equipment good = equip(rec, "EQ0012");
        store.save(rec);

        cultivateService.onPutOn(session, new GamePacket(MsgIds.C2S_EQUIP_PUT_ON, 1, putOn(bad.id, wj.id)));
        drain(ch);
        Assertions.assertEquals("", bad.owner, "武器职不符不得穿上");

        cultivateService.onPutOn(session, new GamePacket(MsgIds.C2S_EQUIP_PUT_ON, 2, putOn(good.id, wj.id)));
        drain(ch);
        Assertions.assertEquals(wj.id, good.owner, "武器职相符可以穿");
    }

    /**
     * C2S 1118：客户端 {@code EquipmentDetail.cs:124-142 EquipReadyFeiYue}（{@code EquipmentUI.cs:2408-2409} 调它）
     * 要求「已开淬炼 + 四部位全开 + 星级 ∈ [5,10)」；服务端改前只卡星级与金币/材料。
     */
    @Test
    public void feiYueRequiresOpenCuiLianAndAllParts() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        PlayerRecord.Equipment eq = equip(rec, "EQ0012");
        eq.stars = CultivateTables.MAX_EQUIP_STAR;
        CultivateTables.CuiLianCost cost = tables.feiYue(eq.stars);
        Assertions.assertNotNull(cost, "5 星飞跃应有配置行");
        rec.gold = cost.gold + 100000;
        for (CultivateTables.Mat m : cost.mats) {
            rec.bag.put(m.ori, Integer.valueOf(m.count));
        }
        for (int i = 0; i <= cost.equipCost; i++) {
            equip(rec, "EQ0012");
        }
        store.save(rec);

        // ① 没开淬炼 ⇒ 星级不动（改前会直接飞跃）
        cultivateService.onFeiYue(session, new GamePacket(MsgIds.C2S_EQUIP_FEIYUE, 1, guidBody(eq.id)));
        Assertions.assertNull(pick(drain(ch), MsgIds.S2C_EQUIP_FEIYUE_RET), "没开淬炼不得飞跃");
        Assertions.assertEquals(CultivateTables.MAX_EQUIP_STAR, eq.stars, "星级不得变");

        // ② 开了淬炼但四部位没全开 ⇒ 星级不动
        eq.openCuiLian = true;
        store.save(rec);
        cultivateService.onFeiYue(session, new GamePacket(MsgIds.C2S_EQUIP_FEIYUE, 2, guidBody(eq.id)));
        Assertions.assertNull(pick(drain(ch), MsgIds.S2C_EQUIP_FEIYUE_RET), "四部位没全开不得飞跃");
        Assertions.assertEquals(CultivateTables.MAX_EQUIP_STAR, eq.stars, "星级不得变");

        // ③ 四部位全开 ⇒ 放行
        eq.cuiLianParts = new boolean[]{true, true, true, true};
        store.save(rec);
        cultivateService.onFeiYue(session, new GamePacket(MsgIds.C2S_EQUIP_FEIYUE, 3, guidBody(eq.id)));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_EQUIP_FEIYUE_RET), "全开应当能飞跃");
        Assertions.assertEquals(CultivateTables.MAX_EQUIP_STAR + 1, eq.stars, "星级 +1");
    }

    /**
     * C2S 1113：客户端 {@code EquipOneKeyLevelUpRet.cs:118} 收到 1410 才结束等待态，
     * 改前零成果（没可强化的装备 / 金币不够）直接 return ⇒ 界面卡在等待。
     */
    @Test
    public void oneKeyLevelUpAlwaysReplies() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        PlayerRecord.Hero wj = rec.findHeroByIndex(18);
        rec.gold = 0;
        store.save(rec);

        cultivateService.onOneKeyLevelUp(session,
                new GamePacket(MsgIds.C2S_EQUIP_ONE_KEY_LEVEL_UP, 1, guidBody(wj.id)));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_EQUIP_ONE_KEY_LEVEL_UP_RET),
                "零成果也要回 1410");
    }

    /**
     * C2S 1605：客户端 {@code ᝁ.cs} 没注册 1605 的 S2C，锁状态靠 4001 回填
     * （{@code BuddiesSystem.cs:2284-2303} 发 1605 后等 4001 刷新勾选）。
     */
    @Test
    public void jiBanLockPushesRefresh() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        side.onJiBanLock(session, new GamePacket(MsgIds.C2S_JIBAN_LOCK, 1, jiBanLock(0, 1, 0)));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_JIBAN_REFRESH), "1605 后必须推 4001");
    }

    /**
     * C2S 401 卖装备：客户端 {@code BagUISystem.cs:812/915} 显示 {@code EquipmentList.txt} 的金币价格，
     * 改前服务端走 {@code EconomyTables.sellGold}（只查 GoodsList，没有 EQ 键）⇒ 显示 300 实得 10 金。
     */
    @Test
    public void sellEquipmentPaysTableGoldPrice() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        PlayerRecord.Equipment eq = equip(rec, "EQ0012");
        int price = tables.equip("EQ0012").goldPrice;
        Assertions.assertTrue(price > 10, "表里金币价格应高于兜底 10，实际 " + price);
        rec.gold = 1000;
        store.save(rec);

        dungeon.onSellGoods(session, new GamePacket(MsgIds.C2S_SELL_GOODS, 1, sellBody(eq.id, "EQ0012")));
        drain(ch);
        Assertions.assertEquals(1000 + price, rec.gold, "卖装备按 EquipmentList 金币价格");
        Assertions.assertNull(rec.findEquip(eq.id), "装备已从背包移除");
    }

    /**
     * C2S 1112 分解：APK 查表是精确 (品质,星级) 匹配、无 fallback（{@code EquipDecomposeData.cs:100-106}），
     * 且只有白板件能进分解页签（{@code EquipmentDecomposeUI.cs:137/146}）。
     * 改前服务端对 5 星金装回落 0 星行 ⇒ 改包可白拿 11250 万能碎片；且养成过的件会被直接删掉。
     */
    @Test
    public void decomposeOnlyAcceptsBlankEquipment() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        int wnspBefore = rec.wannengFragments;
        PlayerRecord.Equipment blank = equip(rec, "EQ0012");
        blank.stars = 0;
        PlayerRecord.Equipment starred = equip(rec, "EQ0012");
        starred.stars = 3;
        PlayerRecord.Equipment leveled = equip(rec, "EQ0012");
        leveled.stars = 0;
        leveled.level = 5;
        store.save(rec);

        cultivateService.onDecompose(session, new GamePacket(MsgIds.C2S_EQUIP_DECOMPOSE, 1,
                decomposeBody(blank.id, starred.id, leveled.id)));
        drain(ch);
        Assertions.assertNull(rec.findEquip(blank.id), "白板件可分解");
        Assertions.assertNotNull(rec.findEquip(starred.id), "升过星的件不得被分解（客户端根本不让进页签）");
        Assertions.assertNotNull(rec.findEquip(leveled.id), "强化过的件不得被分解");
        Assertions.assertEquals(wnspBefore + 30, rec.wannengFragments, "只有白板绿装按 (2,0) 行给 30 万能碎片");
        Assertions.assertNull(tables.decomposeEquip(5, 5), "无 (5,5) 行 ⇒ 查不到，不再回落 0 星行");
        Assertions.assertEquals(11250, tables.decomposeEquip(5, 0).wnsp, "(5,0) 行仍在");
    }

    /**
     * C2S 4901 投喂精炼经验：客户端要求装备淬炼等级 ≥ 目标精炼行的「所需淬炼等级」
     * （{@code EquipmentUI.cs:1069/:1138} 用 {@code >=} 放行、{@code :1026/:1038} 用 {@code <} 置灰）。
     * 表内该列全 0，此处临时改成 5 以验证闸门生效。
     */
    @Test
    public void jingLianFeedHonoursRefineLevelLimit() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        PlayerRecord.Equipment eq = equip(rec, "EQ0101");
        eq.stars = 0;
        eq.jingLianLevel = 0;
        eq.jingLianSubLevel1 = 0;
        int quality = tables.equip("EQ0101").quality;
        CultivateTables.JingLianRefineRow row = tables.refineRow(quality, 1);
        Assertions.assertNotNull(row, "品质 " + quality + " 精炼次数 1 行存在");
        Assertions.assertEquals(0, row.leapLevelLimit, "表内该列当前全 0");
        rec.bag.clear();
        rec.bag.put("JLBS01", Integer.valueOf(10));
        store.save(rec);

        row.leapLevelLimit = 5;
        try {
            cultivateService.onJingLianExp(session, new GamePacket(MsgIds.C2S_JINGLIAN_EXP, 1,
                    jingLianBody(eq.id, "JLBS01", 1, 0)));
            List<GamePacket> out = drain(ch);
            Assertions.assertNull(pick(out, MsgIds.S2C_JINGLIAN_EXP_RET), "淬炼等级 0 < 所需 5 ⇒ 不投喂");
            Assertions.assertEquals(0, eq.jingLianExp, "经验未变");
            Assertions.assertEquals(10, rec.bag.get("JLBS01").intValue(), "材料未扣");

            eq.jingLianLevel = 5;
            store.save(rec);
            cultivateService.onJingLianExp(session, new GamePacket(MsgIds.C2S_JINGLIAN_EXP, 1,
                    jingLianBody(eq.id, "JLBS01", 1, 0)));
            out = drain(ch);
            Assertions.assertNotNull(pick(out, MsgIds.S2C_JINGLIAN_EXP_RET), "达到所需等级后放行");
            Assertions.assertEquals(5, eq.jingLianExp, "JLBS01 经验 5");
            Assertions.assertEquals(9, rec.bag.get("JLBS01").intValue(), "扣 1 个材料");
        } finally {
            row.leapLevelLimit = 0;
        }
    }

    /**
     * 武器配缘（{@code EquipmentList.txt} col11 配缘角色index）命中该武将 ⇒ 名将技换无双技
     * （APK {@code WuJiangInfo.cs:771/787-794} + {@code WuJiang.cs:351-354}）；配件/翅膀配缘不换。
     */
    @Test
    public void yuanFenWeaponSwapsUltimateToWuShuang() {
        PlayerRecord rec = fresh();
        PlayerRecord.Hero wj = rec.findHeroByIndex(18);
        CultivateTables.HeroCfg hero = tables.heroByIndex(18);
        Assertions.assertTrue(hero.skillWuShuang > 0, "hero18 有无双技");
        PlayerRecord.Equipment weapon = equip(rec, "EQ0012");
        weapon.stars = 0;
        weapon.owner = wj.id;
        PlayerRecord.Equipment wing = equip(rec, "EQ0101");
        wing.stars = 0;
        wing.owner = wj.id;
        CultivateTables.EquipCfg weaponCfg = tables.equip("EQ0012");
        CultivateTables.EquipCfg wingCfg = tables.equip("EQ0101");
        Assertions.assertEquals(1, weaponCfg.type, "EQ0012 是武器");
        Assertions.assertNotEquals(1, wingCfg.type, "EQ0101 不是武器");
        Assertions.assertEquals(hero.skillMingJiang,
                CombatAttrCalculator.build(tables, rec, wj).skillMingJiang, "无配缘时用名将技");
        weaponCfg.yuanFenHeroIndex.add(Integer.valueOf(18));
        try {
            Assertions.assertEquals(hero.skillWuShuang,
                    CombatAttrCalculator.build(tables, rec, wj).skillMingJiang, "武器配缘 → 无双技");
        } finally {
            weaponCfg.yuanFenHeroIndex.remove(Integer.valueOf(18));
        }
        wingCfg.yuanFenHeroIndex.add(Integer.valueOf(18));
        try {
            Assertions.assertEquals(hero.skillMingJiang,
                    CombatAttrCalculator.build(tables, rec, wj).skillMingJiang, "非武器配缘不换技");
        } finally {
            wingCfg.yuanFenHeroIndex.remove(Integer.valueOf(18));
        }
    }

    // ------------------------------------------------------------------ 脚手架

    /** f1 repeated 装备 GUID（1112 {@code CCMsgDeComposeEquipmentOrTuZi}）。 */
    private static byte[] decomposeBody(String... guids) {
        return Pb.write(o -> {
            for (String g : guids) {
                Pb.stringAlways(o, 1, g);
            }
        });
    }

    /** f1 GUID + f2 精炼石 ori + f3 数量 + f4 子级（4901 {@code CCMsgEquipAddJingLianExp}）。 */
    private static byte[] jingLianBody(String guid, String stoneOri, int num, int type) {
        return Pb.write(o -> {
            Pb.stringAlways(o, 1, guid);
            Pb.stringAlways(o, 2, stoneOri);
            Pb.int32Always(o, 3, num);
            Pb.int32Always(o, 4, type);
        });
    }
    /** f1 装备 GUID + f2 武将 GUID（1102 {@code CCMsgPutOnEquipment}）。 */
    private static byte[] putOn(String eqGuid, String wjGuid) {
        return Pb.write(o -> {
            Pb.stringAlways(o, 1, eqGuid);
            Pb.stringAlways(o, 2, wjGuid);
        });
    }

    /** 只有 f1 GUID 的请求体（1106/1108/1113/1114/1116/1117/1118/5001 都是这个形状）。 */
    private static byte[] guidBody(String guid) {
        return Pb.write(o -> Pb.stringAlways(o, 1, guid));
    }

    /** f1 repeated {@code CMsgSellGoods{f2 oriName, f3 guid, f4 count}}（401）。 */
    private static byte[] sellBody(String eqGuid, String ori) {
        return Pb.write(o -> Pb.bytesAlways(o, 1, Pb.write(x -> {
            Pb.stringAlways(x, 2, ori);
            Pb.stringAlways(x, 3, eqGuid);
            Pb.int32Always(x, 4, 1);
        })));
    }

    /** f1 index + f2 repeated 锁状态（1605 {@code CMsgJiBanSlotLockState}）。 */
    private static byte[] jiBanLock(int index, int... states) {
        return Pb.write(o -> {
            Pb.int32Always(o, 1, index);
            for (int s : states) {
                Pb.int32Always(o, 2, s);
            }
        });
    }

    private PlayerRecord.Equipment equip(PlayerRecord rec, String ori) {
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = "eq-" + ori + "-" + rec.equipments.size();
        eq.ori = ori;
        eq.level = 1;
        eq.stars = 1;
        rec.equipments.add(eq);
        return eq;
    }

    private PlayerRecord fresh() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 18, 1, "装备测试");
        }
        rec.ensureCollections();
        rec.level = 40;
        rec.gold = 100000;
        rec.diamond = 5000;
        rec.bag.clear();
        rec.equipments.clear();
        rec.heroes.clear();
        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = 18;
        wj.id = PlayerDumpService.guidOf(ACCOUNT, 18);
        rec.heroes.add(wj);
        store.save(rec);
        return rec;
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

    private static List<GamePacket> drain(EmbeddedChannel ch) {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
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
