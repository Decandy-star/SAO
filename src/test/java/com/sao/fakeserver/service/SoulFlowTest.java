package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
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
 * 器魂协议（C2S 3701 合成 / 3702 吞噬 → S2C 4301 / 4302）按 APK 口径的回归测试。
 *
 * <p>3702 改前的假服是无条件 {@code s.exp += 20}（既不读 f2 的材料列表、也不扣背包），
 * 而客户端 {@code QiHunSys.cs:643-655} 按 {@code Σ(数量 × GoodsList 第 15 字段)} 算本地预览、
 * 收包后弹「获得器魂经验 +（新 curExp − 旧 curExp）」（{@code ᝁ.cs:7802-7837}）——
 * 于是预览和结果永远对不上，且可以空手无限刷经验（器魂经验进战力）。
 *
 * <p>3701 改前更宽：直接 {@code composed = true}，不查 {@code EquipSoulList.txt} 的
 * 「合成所需物品ID/数量」（QH00x × 50）也不扣材料。
 *
 * <p>对照的 APK 来源：proto {@code pyfoot\tmp_msgdll\NetProto\CCMsgRequestAddEquipSoulExp.cs} /
 * {@code CCMsgRequestComposeEquipSoul.cs}、客户端 {@code decompiled\client-src\MobileGameDemo\QiHunSys.cs}
 * / {@code GoodsPropertyCfg.cs:28} / {@code PlayGameState.cs:8095}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-soul-devour",
        "sao.world-dir=target/test-data/world-soul-devour",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class SoulFlowTest {
    private static final String ACCOUNT = "test-soul-devour";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private SideService side;
    @Autowired
    private EconomyTables economy;
    @Autowired
    private CultivateTables cultivate;
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
     * 表键：第 15 字段「器魂吞噬经验值」= cols[14] 必须被解析出来。
     *
     * <p>表里 552 件道具只有 217 件有值（客户端吞噬列表只列 &gt;0 的，{@code QiHunSys.cs:422}），
     * 专用道具是初级/中级/高级器魂水晶 30/100/200。
     */
    @Test
    public void soulExpColumnIsParsed() {
        Assertions.assertEquals(30, economy.soulExp("GOODS247"), "初级器魂水晶 = 30");
        Assertions.assertEquals(100, economy.soulExp("GOODS248"), "中级器魂水晶 = 100");
        Assertions.assertEquals(200, economy.soulExp("GOODS249"), "高级器魂水晶 = 200");
        Assertions.assertEquals(6, economy.soulExp("GOODS118"), "GOODS118 雷肯 = 6");
        Assertions.assertEquals(0, economy.soulExp("TS105"), "红色时光石Ⅴ 该列为 0 ⇒ 不是器魂材料");
        Assertions.assertEquals(0, economy.soulExp("NOT_EXIST"), "查不到的道具按 0 处理");
    }

    /**
     * 表键：{@code EquipSoulList.txt} 第 7/8 字段「合成所需物品ID / 合成所需数量」= cols[6]/cols[7]。
     *
     * <p>启用的 21 行（首列输出符 {@code #}）全是 {@code QH00x × 50}；没打 {@code #} 的 9 行
     * （如 hero 20 鬼剑士太刀）客户端也不导出，服务端同样取不到 —— 这条同时锁住
     * 「按 {@code #} 过滤」的口径。
     */
    @Test
    public void soulComposeColumnsAreParsed() {
        CultivateTables.SoulCfg sword = cultivate.soulCfg(17);
        Assertions.assertNotNull(sword, "hero 17 圣剑 有启用行");
        Assertions.assertEquals("QH001", sword.composeGoods, "合成所需物品ID = QH001");
        Assertions.assertEquals(50, sword.composeCount, "合成所需数量 = 50");
        CultivateTables.SoulCfg asuna = cultivate.soulCfg(19);
        Assertions.assertEquals("QH003", asuna.composeGoods, "hero 19 闪烁之光 = QH003");
        Assertions.assertNull(cultivate.soulCfg(20),
                "hero 20 鬼剑士太刀 这行没有输出符 # ⇒ 与客户端一样不导出");
    }

    /**
     * 正路：按 f2 材料列表求和 → 扣背包 → 回 4302 的 curExp = Σ(数量 × 列值)。
     *
     * <p>不按阶段经验值截断（客户端自己按 {@code curExp - mExp} 结转溢出）。
     */
    @Test
    public void devourConsumesMaterialsAndAddsColumnExp() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.bag.put("GOODS247", Integer.valueOf(2));
        rec.bag.put("GOODS118", Integer.valueOf(3));
        store.save(rec);

        int wj = rec.mainHeroIndex;
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 1, devour(wj,
                new String[]{"GOODS247", "2"}, new String[]{"GOODS118", "1"})));

        List<GamePacket> out = drain(ch);
        GamePacket ret = pick(out, MsgIds.S2C_SOUL_EXP);
        Assertions.assertNotNull(ret, "合法吞噬必须回 4302");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(wj, f.getInt(1, 0), "4302 f1 wjIndex");
        Pb.Fields soul = Pb.read(f.getBytes(2));
        Assertions.assertTrue(soul.getBool(1), "f2.1 hasComposed");
        Assertions.assertEquals(0, soul.getInt(2, 0), "f2.2 jieduan = 0");
        Assertions.assertEquals(2 * 30 + 1 * 6, soul.getInt(3, 0),
                "f2.3 curExp 必须 = 2×30 + 1×6 = 66（改前恒为 20）");

        Assertions.assertEquals(66, rec.souls.get(Integer.valueOf(wj)).exp, "经验必须落档");
        Assertions.assertEquals(0, rec.bag.getOrDefault("GOODS247", Integer.valueOf(0)).intValue(),
                "初级器魂水晶 2 → 0（consumeGoods 扣空即移出背包）");
        Assertions.assertEquals(2, rec.bag.get("GOODS118").intValue(), "雷肯 3 → 2");
        // 背包变动要推 301，否则客户端背包格还显示被吃掉的材料。
        Assertions.assertNotNull(pick(out, MsgIds.S2C_UPDATE_GOODS), "扣材料必须推 301");
    }

    /**
     * 溢出不当错：一次吃 2 颗高级器魂水晶 = 400 点，远超阶段 0 的 30 点上限，也必须原样记账。
     *
     * <p>客户端只在「加点前」检查 {@code ᜁ() < mExp}（{@code QiHunSys.cs:494}），
     * 超出的部分由它自己按 {@code curExp - mExp} 结转（{@code :841}）—— 服务端截断就会对不上。
     */
    @Test
    public void devourDoesNotTruncateAtStageRequirement() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.bag.put("GOODS249", Integer.valueOf(2));
        store.save(rec);

        int wj = rec.mainHeroIndex;
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 1, devour(wj,
                new String[]{"GOODS249", "2"})));

        GamePacket ret = pick(drain(ch), MsgIds.S2C_SOUL_EXP);
        Assertions.assertNotNull(ret, "必须回 4302");
        Assertions.assertEquals(400, Pb.read(Pb.read(ret.body).getBytes(2)).getInt(3, 0),
                "curExp = 400，不得被阶段上限 30 截断");
    }

    /**
     * 非法包一律不回 4302（客户端没有失败通道，假成功会让本地预览当成已生效）：
     * 材料不足 / 非器魂材料 / 空列表 / 同件材料拆多条合计超库存。
     */
    @Test
    public void illegalDevourIsSilentAndChangesNothing() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.bag.put("GOODS247", Integer.valueOf(1));
        store.save(rec);

        int wj = rec.mainHeroIndex;
        // ① 库存不足（有 1 颗，要 2 颗）
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 1, devour(wj,
                new String[]{"GOODS247", "2"})));
        // ② 非器魂材料（TS105 该列为 0）
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 2, devour(wj,
                new String[]{"TS105", "1"})));
        // ③ 空材料列表
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 3, devour(wj)));
        // ④ 同件材料拆两条、各自不超库存、合计超（1 + 1 > 1）
        side.onSoulExp(session, new GamePacket(MsgIds.C2S_SOUL_EXP, 4, devour(wj,
                new String[]{"GOODS247", "1"}, new String[]{"GOODS247", "1"})));

        List<GamePacket> out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_EXP), "四种非法包都不得回 4302");
        Assertions.assertFalse(rec.souls.containsKey(Integer.valueOf(wj)), "不得凭空建器魂档");
        Assertions.assertEquals(1, rec.bag.get("GOODS247").intValue(), "材料不得被扣");
    }

    /**
     * 合成闸门：3701 只带 f1 {@code wjIndex}，材料要求全在服务端表里（QH00x × 50）。
     *
     * <p>49 个碎片不够、50 个才合成；材料不足时必须静默 —— 客户端点了合成**即使不够也会发包**
     * （{@code QiHunSys.cs:234-242} 只弹 100647 提示、没有 return），而 4301 会让它弹
     * 100363「合成成功」（{@code PlayGameState.cs:8095-8115}）。
     */
    @Test
    public void composeRequiresFiftySoulFragments() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        // ① 一件材料都没有
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 1, compose(17)));
        List<GamePacket> out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_COMPOSE), "空背包不得回 4301");

        // ② 差一个（49/50）
        rec.bag.put("QH001", Integer.valueOf(49));
        store.save(rec);
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 2, compose(17)));
        out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_COMPOSE), "49 个碎片不得合成");
        Assertions.assertFalse(composed(rec, 17), "不得点亮器魂");
        Assertions.assertEquals(49, rec.bag.get("QH001").intValue(), "材料不得被扣");

        // ③ 刚好够（50/50）
        rec.bag.put("QH001", Integer.valueOf(50));
        store.save(rec);
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 3, compose(17)));
        out = drain(ch);
        GamePacket ret = pick(out, MsgIds.S2C_SOUL_COMPOSE);
        Assertions.assertNotNull(ret, "50 个碎片必须回 4301");
        Assertions.assertEquals(17, Pb.read(ret.body).getInt(1, 0), "4301 f1 wjIndex");
        Assertions.assertTrue(Pb.read(Pb.read(ret.body).getBytes(2)).getBool(1), "4301 f2.1 hasComposed");
        Assertions.assertTrue(composed(rec, 17), "器魂必须点亮");
        Assertions.assertEquals(0, rec.bag.getOrDefault("QH001", Integer.valueOf(0)).intValue(),
                "50 个碎片必须被扣掉（扣空即移出背包）");
        Assertions.assertNotNull(pick(out, MsgIds.S2C_UPDATE_GOODS), "扣材料必须推 301");
    }

    /**
     * 已合成时按幂等处理：回现状、不重复扣材料（客户端合成按钮由 {@code hasComposed} 隐藏，
     * 重复包只可能来自重放）；没有启用器魂行的武将（如 hero 20）也必须静默。
     */
    @Test
    public void composeIsIdempotentAndRejectsHeroesWithoutEnabledSoul() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.bag.put("QH001", Integer.valueOf(100));
        store.save(rec);
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 1, compose(17)));
        drain(ch);
        Assertions.assertEquals(50, rec.bag.get("QH001").intValue(), "第一次扣 50，剩 50");

        // 再来一次：仍然回 4301（幂等），但不得再扣
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 2, compose(17)));
        List<GamePacket> out = drain(ch);
        Assertions.assertNotNull(pick(out, MsgIds.S2C_SOUL_COMPOSE), "重复合成仍回 4301 现状");
        Assertions.assertEquals(50, rec.bag.get("QH001").intValue(), "重复合成不得再扣材料");

        // hero 20 那行没有输出符 #（客户端也不导出）⇒ 静默
        side.onSoulCompose(session, new GamePacket(MsgIds.C2S_SOUL_COMPOSE, 3, compose(20)));
        out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_COMPOSE), "无启用器魂行不得回 4301");
        Assertions.assertFalse(composed(rec, 20), "不得凭空点亮");
    }

    /**
     * 进阶闸门（3703）：当前阶经验不够不得进阶；够了之后**溢出要结转**（改前是 `exp = 0`）。
     *
     * <p>客户端 {@code QiHunSys.cs:708} 用 {@code curExp >= mExp} 决定突破按钮可用，
     * {@code :841} 预览下一阶时传的是 {@code curExp - mExp} —— 所以真服口径是扣掉当前阶所需、余下留给下一阶。
     */
    @Test
    public void jinJieRequiresStageExpAndCarriesOverflow() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        int wj = rec.mainHeroIndex;

        PlayerRecord.Soul s = new PlayerRecord.Soul();
        s.composed = true;
        s.stage = 0;
        s.exp = 29;
        rec.souls.put(Integer.valueOf(wj), s);
        store.save(rec);

        // ① 29/30：差一点，静默（客户端没有失败通道，不能假成功）
        side.onSoulJinJie(session, new GamePacket(MsgIds.C2S_SOUL_JINJIE, 1, jinJie(wj)));
        List<GamePacket> out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_JINJIE), "经验不足不得回 4303");
        Assertions.assertEquals(0, s.stage, "阶段不得前进");
        Assertions.assertEquals(29, s.exp, "经验不得被吞");

        // ② 35/30：进阶成功，溢出 5 点结转
        s.exp = 35;
        store.save(rec);
        side.onSoulJinJie(session, new GamePacket(MsgIds.C2S_SOUL_JINJIE, 2, jinJie(wj)));
        out = drain(ch);
        GamePacket ret = pick(out, MsgIds.S2C_SOUL_JINJIE);
        Assertions.assertNotNull(ret, "经验够了必须回 4303");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(wj, f.getInt(1, 0), "4303 f1 wjIndex");
        Pb.Fields soul = Pb.read(f.getBytes(2));
        Assertions.assertEquals(1, soul.getInt(2, 0), "f2.2 jieduan = 1");
        Assertions.assertEquals(5, soul.getInt(3, 0),
                "f2.3 curExp = 35 - 30 = 5（改前恒为 0，溢出被丢掉）");
        Assertions.assertEquals(1, s.stage, "阶段必须落档");
        Assertions.assertEquals(5, s.exp, "溢出必须落档");
    }

    /**
     * 进阶闸门（3703）另两条：末阶（{@code EquipSoulJinJie.txt} 阶段 9 之后没有行）必须停；
     * 没合成的器魂（客户端只有合成后的面板能进突破）也必须静默。
     *
     * <p>改前无任何校验 ⇒ {@code stage++} 可以无限刷，而 {@code CultivateTables.equipSoulAdd}
     * 按 stage 逐阶累乘分段/进阶系数，等于白送战力。
     */
    @Test
    public void jinJieStopsAtLastStageAndNeedsComposed() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        int wj = rec.mainHeroIndex;

        PlayerRecord.Soul s = new PlayerRecord.Soul();
        s.composed = true;
        s.stage = 9;
        s.exp = 99999;
        rec.souls.put(Integer.valueOf(wj), s);
        store.save(rec);

        // ① 末阶：没有 stage 10 这一行 ⇒ 静默（客户端此时突破节点已 SetActive(false)）
        side.onSoulJinJie(session, new GamePacket(MsgIds.C2S_SOUL_JINJIE, 1, jinJie(wj)));
        List<GamePacket> out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_JINJIE), "末阶不得回 4303");
        Assertions.assertEquals(9, s.stage, "末阶不得继续前进");

        // ② 没合成（hero 17 根本没建档）：不得凭空建档、不得点亮
        side.onSoulJinJie(session, new GamePacket(MsgIds.C2S_SOUL_JINJIE, 2, jinJie(17)));
        out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SOUL_JINJIE), "未合成的器魂不得进阶");
        Assertions.assertFalse(rec.souls.containsKey(Integer.valueOf(17)), "不得凭空建器魂档");
    }

    // ------------------------------------------------------------ 时光石（2901 / 3004）

    /**
     * 表键：{@code TimeStoneCompose.txt}「等级/消耗金币」两列 + GoodsList col12 第三段卸下钻石。
     *
     * <p>7 级那行首列没有输出符 {@code #}（客户端 {@code TimeStoneComposeCfgMgr.cs:22} 同口径），
     * 所以输入上限是 6、输出上限是 7。
     */
    @Test
    public void timeStoneColumnsAreParsed() {
        Assertions.assertEquals(1000, economy.timeStoneComposeGold(1), "1 级合成 1000 金");
        Assertions.assertEquals(1000000, economy.timeStoneComposeGold(6), "6 级合成 1000000 金");
        Assertions.assertEquals(-1, economy.timeStoneComposeGold(7), "7 级不可再合成");
        Assertions.assertEquals(6, economy.timeStoneComposeMaxInputLevel(), "可合成输入上限 = 6");
        Assertions.assertEquals(0, economy.timeStoneRemoveCost("TS104"), "Ⅳ 卸下免费");
        Assertions.assertEquals(10, economy.timeStoneRemoveCost("TS105"), "Ⅴ 卸下 10 钻");
        Assertions.assertEquals(100, economy.timeStoneRemoveCost("TS107"), "Ⅶ 卸下 100 钻");
        Assertions.assertEquals(0, economy.timeStoneRemoveCost("GOODS104"), "非时光石没有卸下费");
    }

    /**
     * C2S 3004：客户端只保证「槽位填满 + 金币够」（{@code BaoShiHeChengUI.cs:1117-1124}），
     * 配方 / 等级上限 / 扣料全在服务端。
     *
     * <p>改前是「消耗 f1 列出的每个 ori（返回值丢弃）+ 原样回吐 f1[0]」——
     * 发 {@code ["EQ0103"]} 就能凭空拿到一件装备实例（{@code addGoods} 对装备 ori 走 {@code grantEquip}）。
     */
    @Test
    public void composeTimeStonesRequiresRecipeAndGold() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.gold = 2000000;
        rec.bag.put("TS101", Integer.valueOf(3));
        store.save(rec);

        // ① 单颗（客户端 UI 至少两颗）⇒ 静默
        side.onTimeStoneCompose(session, new GamePacket(MsgIds.C2S_TIME_STONE_COMPOSE, 1, composeTimeStone("TS101")));
        assertComposeRejected(drain(ch), "一颗不得合成");

        // ② 混搭两颗 ⇒ 静默
        side.onTimeStoneCompose(session, new GamePacket(MsgIds.C2S_TIME_STONE_COMPOSE, 2,
                composeTimeStone("TS101", "TS102")));
        assertComposeRejected(drain(ch), "不同石不得合成");

        // ③ 拿装备 ori 冒充材料 ⇒ 静默，背包/金币都不动（改前这里白送一件装备）
        side.onTimeStoneCompose(session, new GamePacket(MsgIds.C2S_TIME_STONE_COMPOSE, 3,
                composeTimeStone("EQ0103", "EQ0103")));
        assertComposeRejected(drain(ch), "非时光石不得合成");
        Assertions.assertEquals(3, bagOf(rec, "TS101"), "背包不得被改");
        Assertions.assertEquals(2000000, rec.gold, "金币不得被扣");
        Assertions.assertEquals(0, bagOf(rec, "EQ0103"), "不得凭空发装备");

        // ④ 正路：两颗 TS101 → 一颗 TS102 + 扣 1000 金
        side.onTimeStoneCompose(session, new GamePacket(MsgIds.C2S_TIME_STONE_COMPOSE, 4,
                composeTimeStone("TS101", "TS101")));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_TIME_STONE_COMPOSE);
        Assertions.assertNotNull(ret, "正路必须回 3404");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertTrue(f.getBool(1), "Ret = true");
        Assertions.assertEquals("TS102", f.getString(2), "输出 = 同色下一级");
        Assertions.assertEquals(1, bagOf(rec, "TS102"), "得到一颗 TS102");
        Assertions.assertEquals(1, bagOf(rec, "TS101"), "扣掉两颗");
        Assertions.assertEquals(2000000 - 1000, rec.gold, "扣 1 级合成费 1000 金");

        // ⑤ 金币不够 ⇒ 静默、不扣料
        rec.gold = 0;
        rec.bag.put("TS106", Integer.valueOf(2));
        store.save(rec);
        side.onTimeStoneCompose(session, new GamePacket(MsgIds.C2S_TIME_STONE_COMPOSE, 5,
                composeTimeStone("TS106", "TS106")));
        assertComposeRejected(drain(ch), "金币不够不得合成");
        Assertions.assertEquals(2, bagOf(rec, "TS106"), "材料不得被扣");    }

    /**
     * C2S 2901：改前空孔位会用 f3 白送任意 ori（含装备），且完全不收钻石。
     * 客户端 {@code RemoveTimeRock.cs:67-76} 是按 GoodsList col12 第三段扣完 {@code mRemoveCost} 才发的。
     */
    @Test
    public void removingTimeStoneChargesDiamondAndIgnoresEmptyHole() {
        PlayerRecord rec = fresh();
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        PlayerRecord.Hero wj = rec.heroes.get(0);
        Assertions.assertNotNull(wj, "新号必须有武将");
        wj.ensureTimeStones();
        wj.setTimeStone(1, "TS105", 1);
        rec.diamond = 100;
        rec.bag.clear();
        store.save(rec);

        // ① 卸 Ⅴ（10 钻）：石头回背包、扣 10 钻，f3 里的装备 ori 一律忽略
        side.onTimeStoneOff(session, new GamePacket(MsgIds.C2S_TIME_STONE_OFF, 1, timeStoneOff(wj.id, 1, "EQ0103")));
        drain(ch);
        Assertions.assertEquals(1, bagOf(rec, "TS105"), "卸下的石头回背包");
        Assertions.assertEquals(0, bagOf(rec, "EQ0103"), "f3 不得被当成石头白送");
        Assertions.assertEquals(90, rec.diamond, "Ⅴ 卸下扣 10 钻");
        Assertions.assertEquals("", wj.timeStone(1), "孔位已空");

        // ② 空孔位：什么都不发生（改前会白送 EQ0103）
        side.onTimeStoneOff(session, new GamePacket(MsgIds.C2S_TIME_STONE_OFF, 2, timeStoneOff(wj.id, 1, "EQ0103")));
        drain(ch);
        Assertions.assertEquals(0, bagOf(rec, "EQ0103"), "空孔位不得发东西");
        Assertions.assertEquals(90, rec.diamond, "空孔位不得扣钻");
        Assertions.assertEquals(1, bagOf(rec, "TS105"), "背包不得再变");
    }

    // ------------------------------------------------------------------ 脚手架

    /** f1 wjIndex（3701 {@code CCMsgRequestComposeEquipSoul} 只有这一个字段）。 */
    private static byte[] compose(int wjIndex) {
        return Pb.write(o -> Pb.int32Always(o, 1, wjIndex));
    }

    /** f1 wjIndex（3703 {@code CCMsgRequestJinJieEquipSoul} 同样只有这一个字段）。 */
    private static byte[] jinJie(int wjIndex) {
        return Pb.write(o -> Pb.int32Always(o, 1, wjIndex));
    }

    private static boolean composed(PlayerRecord rec, int wjIndex) {
        PlayerRecord.Soul s = rec.souls.get(Integer.valueOf(wjIndex));
        return s != null && s.composed;
    }

    /** f1 wjIndex + f2 repeated {@code CMsgGoods{f1 oriName, f2 count}}。 */
    private static byte[] devour(int wjIndex, String[]... goods) {
        return Pb.write(o -> {
            Pb.int32Always(o, 1, wjIndex);
            for (String[] g : goods) {
                Pb.bytesAlways(o, 2, Pb.write(x -> {
                    Pb.stringAlways(x, 1, g[0]);
                    Pb.int32Always(x, 2, Integer.parseInt(g[1]));
                }));
            }
        });
    }

    /** f1 guid + f2 loc + f3 stoneId（2901 {@code CCMsgDismantling_TimeStone}）。 */
    private static byte[] timeStoneOff(String guid, int loc, String stoneId) {
        return Pb.write(o -> {
            Pb.stringAlways(o, 1, guid);
            Pb.int32Always(o, 2, loc);
            Pb.stringAlways(o, 3, stoneId);
        });
    }

    /** f1 repeated {@code TimeStoneOriName}（3004 {@code CCMsgRequestComposeTimeStone} 只有这一个字段）。 */
    private static byte[] composeTimeStone(String... oris) {
        return Pb.write(o -> {
            for (String ori : oris) {
                Pb.stringAlways(o, 1, ori);
            }
        });
    }

    private static int bagOf(PlayerRecord rec, String ori) {
        Integer n = rec.bag.get(ori);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 3404 失败形态：{@code Ret = false}。
     * 客户端 {@code BaoShiHeChengUI.cs:100 if (ccmsgRequestComposeTimeStoneRet.Ret)} 只在为真时走成功分支，
     * 所以拒绝必须回包（不能像装备域其它包那样静默）但 Ret 必须为 false。
     */
    private static void assertComposeRejected(List<GamePacket> out, String why) {
        GamePacket p = pick(out, MsgIds.S2C_TIME_STONE_COMPOSE);
        Assertions.assertNotNull(p, why + "：必须回 3404");
        Assertions.assertFalse(Pb.read(p.body).getBool(1), why + "：Ret 必须为 false");
    }

    private PlayerRecord fresh() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 18, 1, "器魂测试");
        }
        rec.ensureCollections();
        rec.level = 40;
        rec.bag.clear();
        rec.souls.clear();
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
