package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.ActExtCfg;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.LtsjCfg;
import com.sao.fakeserver.table.VipMailCfg;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D5（活动 / 充值特权 / 限时神将）按 APK 真实序列走一遍的回归测试。
 *
 * <p>钉的是「客户端按 APK 的调用序列发过来时，服务端回的包/改的档是不是客户端期待的那个」，
 * 每条断言注释都写清 APK 侧对应代码位置；改 D5 行为时这个测试必须一起动。
 *
 * <p>对照的 APK 源码：{@code F:\workspace\daojian\SAO\decompiled\client-src\}
 * （充值 {@code MobileGameDemo\VIPUISystem.cs} / {@code PlayGameState.cs}、
 * 活动 {@code MobileGameDemo\ActivityMainUI.cs} / {@code ActivityStatusInfo.cs} / {@code MagicBox.cs}、
 * 限时神将 {@code MobileGameDemo\XianShiShenJiang.cs}）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // 相对路径按 surefire fork 的 CWD 解析（实测 = 模块根/target/classes），落在 target 下，mvn clean 即清。
        "sao.data-dir=target/test-data/players-d5-flow",
        "sao.world-dir=target/test-data/world-d5-flow",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class D5FlowSimTest {
    private static final String ACCOUNT = "test-d5-flow";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private PayService pay;
    @Autowired
    private ActivityService activity;
    @Autowired
    private LtsjService ltsj;
    @Autowired
    private DungeonService dungeon;
    @Autowired
    private CultivateTables cultivate;
    @Autowired
    private EconomyTables economy;
    @Autowired
    private ActExtCfg actExt;
    @Autowired
    private SessionHub hub;
    @Autowired
    private MailService mail;
    @Autowired
    private VipMailCfg vipMail;
    @Autowired
    private SaoProperties props;
    @Autowired
    private GlobalServerScheduler scheduler;
    @Autowired
    private ProgressService progress;
    @Autowired
    private LtsjCfg ltsjCfg;
    @Autowired
    private LoginService login;

    private EmbeddedChannel channel;

    @AfterEach
    public void cleanup() {
        if (channel != null) {
            channel.finishAndReleaseAll();
            channel = null;
        }
    }

    /**
     * 充值 3001：赠送钻是「限次另赠 vs 常规赠送」二选一，不是相加。
     *
     * <p>APK：{@code PlayGameState.cs:6851} {@code num2 = payCfg.ZuanShiCount +
     * ((GetExtAwardTimeLeft() > 0U) ? ZuanShiExtAward : ZuanShiAward)}；
     * {@code VIPChongZhiItem.cs:59-71} 的档位文案同样二选一。相加会让 30/198/648 元档
     * 首购多发 15/200/1500 钻，且与充值弹窗显示的数字不符。
     */
    @Test
    public void sdkPayExtAwardIsExclusiveNotAdditive() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        EconomyTables.PayRow row = economy.pay(9);
        Assertions.assertNotNull(row, "UserPayGoods.txt 必须有 9 号档（30 元）");
        Assertions.assertEquals(1, row.extTimes, "9 号档限次次数必须是 1，否则本测试前提不成立");
        Assertions.assertTrue(row.extAward > 0 && row.award > 0,
                "9 号档必须同时有常规赠送(15)与限次另赠(300)，才能验证二选一");

        int before = rec.diamond;
        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 1, Pb.write(o -> Pb.int32(o, 1, 9))));
        int first = rec.diamond - before;
        Assertions.assertEquals(row.diamond + row.extAward, first,
                "首购必须用限次另赠（300+300=600），不能与常规赠送相加（615）");
        Assertions.assertEquals(row.diamond, rec.economy.chargedDiamond,
                "VIP 累计只计常规钻石（VipCfg 的「累计钻石数量」逐档对齐档位常规钻）");
        drain();

        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 2, Pb.write(o -> Pb.int32(o, 1, 9))));
        int second = rec.diamond - before - first;
        Assertions.assertEquals(row.diamond + row.award, second,
                "限次用完后改发常规赠送（300+15=315）");
    }

    /**
     * 充值 2701：VIP 等级礼包任何分支都必须回 3101。
     *
     * <p>APK：{@code VIPUISystem.cs:431 WaitSever()} 把按钮置「等待服务器」，
     * 唯一复位入口是 3101 触发的 {@code EN_REFRESH_VIP_TEQUAN_UI}（{@code VIPUISystem.cs:319-321}）；
     * 新号 VIP0 打开礼包页必发 level=1（{@code VIPUISystem.cs:123-135}），
     * 原来「等级不合法 / 已领过 / 该等级无奖励」三处直接 return 不回包 → 按钮永久卡住。
     */
    @Test
    public void vipLevelAwardAlwaysReplies3101() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.economy.chargedDiamond = 0;
        store.save(rec);

        pay.onVipLevelAward(session, new GamePacket(MsgIds.C2S_VIP_LEVEL_AWARD, 3, Pb.write(o -> Pb.int32(o, 1, 1))));
        Assertions.assertTrue(has(drain(), MsgIds.S2C_VIP_LEVEL_AWARD_RET),
                "VIP0 请求 1 级礼包（拒绝分支）也必须回 3101");

        rec.economy.chargedDiamond = 60;
        store.save(rec);
        pay.onVipLevelAward(session, new GamePacket(MsgIds.C2S_VIP_LEVEL_AWARD, 4, Pb.write(o -> Pb.int32(o, 1, 1))));
        Assertions.assertTrue(has(drain(), MsgIds.S2C_VIP_LEVEL_AWARD_RET), "领取分支要回 3101");
        pay.onVipLevelAward(session, new GamePacket(MsgIds.C2S_VIP_LEVEL_AWARD, 5, Pb.write(o -> Pb.int32(o, 1, 1))));
        Assertions.assertTrue(has(drain(), MsgIds.S2C_VIP_LEVEL_AWARD_RET), "已领分支也要回 3101");
    }

    /**
     * 特权卡日返：3501 由玩家点领结算，买卡当天与 0 点都不自动结清。
     *
     * <p>APK：{@code TaskSystem.cs:799-853} 点击先弹本地确认框（奖励数字取
     * {@code TeQuanCardCfg.GetCardCfg(2U).PerDayFanZuan}），确认才发 3501（空 body）；
     * 结算后必须回 3901，{@code PlayGameState.cs:7322-7338} 用它刷状态位并发
     * {@code EN_REFRESH_TEQUANFULI} 复位按钮。原实现在 0 点用群发邮件提前置位
     * todayTeQuan/todayZhiZun → 按钮整天显示已领取、3501 永远发不出去。
     */
    @Test
    public void teQuanDailyIsClaimedByPlayerNotMidnight() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        EconomyTables.TeQuanRow card = economy.teQuan(2);
        Assertions.assertNotNull(card, "TeQuanCard.txt 必须有 type2 月卡");
        Assertions.assertTrue(card.dailyDiamond > 0, "月卡每日返钻必须 > 0");

        // 买月卡（1001，type2）：买卡当天不能自动结算日返
        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 6, Pb.write(o -> Pb.int32(o, 1, 1001))));
        drain();
        Assertions.assertFalse(rec.cards.todayTeQuan,
                "买卡当天不能自动结算日返，否则客户端「领取」按钮整天显示已领取");

        int before = rec.diamond;
        pay.onTeQuanDaily(session, new GamePacket(MsgIds.C2S_TEQUAN_DAILY, 7, new byte[0]), false);
        List<GamePacket> out = drain();
        Assertions.assertEquals(card.dailyDiamond, rec.diamond - before,
                "日返钻石 = TeQuanCard.txt 的每日返钻");
        Assertions.assertTrue(rec.cards.todayTeQuan, "领完必须置位，客户端靠 3901 复位按钮");
        Assertions.assertTrue(has(out, MsgIds.S2C_TEQUAN_INFO), "3501 必须回 3901");

        pay.onTeQuanDaily(session, new GamePacket(MsgIds.C2S_TEQUAN_DAILY, 8, new byte[0]), false);
        drain();
        Assertions.assertEquals(card.dailyDiamond, rec.diamond - before, "同一天只能领一次");
    }

    /**
     * 首充：2618 首档首槽必须是翅膀装备，且领奖时发的是装备实例（1406）。
     *
     * <p>APK：{@code ActivityMainUI.cs:1773-1778} 取 {@code m1stChongZhiAward[0].oriName[0]}
     * 直接 {@code EquipmentData.GetEquipmentData(...).chiBangPrefabName}，null 或
     * {@code equipType != 4} 只打一条 LogException 后照旧解引用 → 首充页整个打不开。
     * 槽位渲染 {@code ActivityMainUI.cs:1815-1877} 对装备 ori 走装备图标，
     * {@code :1865-1868} 只有 equipType==4 才绑翅膀预览点击。
     */
    @Test
    public void firstChargeFirstSlotIsWingAndAwardsEquipInstance() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        activity.onFirstChongZhiQuery(session, new GamePacket(MsgIds.C2S_FIRST_CHONGZHI_QUERY, 9, new byte[0]));
        GamePacket q = firstOf(channel, MsgIds.S2C_FIRST_CHONGZHI_QUERY_RET);
        Assertions.assertNotNull(q, "2316 必须回 2618");
        List<byte[]> tiers = Pb.read(q.body).getBytesList(1);
        Assertions.assertFalse(tiers.isEmpty(), "act-first-charge.json 至少要有一档");
        List<String> firstSlotOris = Pb.read(tiers.get(0)).getStrings(4);
        Assertions.assertFalse(firstSlotOris.isEmpty(), "首档必须有奖励项（field4 oriName）");
        String firstOri = firstSlotOris.get(0);
        CultivateTables.EquipCfg wing = cultivate.equip(firstOri);
        Assertions.assertNotNull(wing, "首档首槽必须是装备原始名，不能是道具（客户端会 NRE）：" + firstOri);
        Assertions.assertEquals(4, wing.type, "首档首槽装备必须是 equipType=4 的翅膀：" + firstOri);

        // 首充条件：买过 ID<=7 的首充档（客户端 GetCur1stChongZhiRMB 只看 ID ≤ msMax1stID=7）
        rec.economy.payBuyCounts.put(Integer.valueOf(8), Integer.valueOf(1));
        store.save(rec);
        int eqBefore = rec.equipments.size();
        activity.onFirstChongZhiAward(session, new GamePacket(MsgIds.C2S_FIRST_CHONGZHI_AWARD, 10, new byte[0]));
        List<GamePacket> out = drain();
        Assertions.assertTrue(has(out, MsgIds.S2C_FIRST_CHONGZHI_AWARD_RET), "2317 必须回 2615（body 空）");
        Assertions.assertTrue(rec.equipments.size() > eqBefore, "首槽翅膀必须发装备实例");
        boolean wingGranted = false;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (firstOri.equals(eq.ori)) {
                wingGranted = true;
            }
        }
        Assertions.assertTrue(wingGranted, "装备实例的 ori 必须是首槽那件翅膀");
        Assertions.assertTrue(has(out, MsgIds.S2C_ADD_EQUIP), "装备必须推 1406 入包");
        Assertions.assertTrue(rec.economy.hasGet1stChongZhiAward, "领过要落档，不能重复领");
    }

    /**
     * 魔盒 4902 的 CostRMB 是「下一抽的钻石价」，不是本抽实付。
     *
     * <p>APK：{@code MagicBox.cs:111 mTakeCardCostRMB = info.CostRMB}，
     * {@code :1050 if (!mGoodsEnough && mCurRMB < mTakeCardCostRMB)} 用它做余额校验；
     * 原来传本抽实付（魔瓶支付时为 0）→ 校验恒不触发，叠加服务端钻石不足静默 return，
     * 表现为「点下一张牌没任何反应」。
     */
    @Test
    public void magicBoxGivePrizeCostRmbIsNextDiamondCost() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        ActExtCfg.MagicFile cfg = actExt.magic();
        Assertions.assertNotNull(cfg, "tables/magicbox.json 必须存在");
        Assertions.assertTrue(cfg.diamondCost > 0, "魔盒钻石价必须 > 0");
        // 魔瓶清零 → 走钻石兜底分支（客户端在魔瓶不足时用 CostRMB 做校验）
        rec.bag.remove(cfg.goodsOri);
        rec.diamond = Math.max(rec.diamond, cfg.diamondCost * 4);
        store.save(rec);

        activity.onMagicBoxQuery(session, new GamePacket(MsgIds.C2S_MAGIC_BOX_INFO, 11, new byte[0]));
        drain();
        activity.onMagicBoxXiPai(session, new GamePacket(MsgIds.C2S_MAGIC_BOX_XIPAI, 12, new byte[0]));
        drain();
        // 翻第 0 张牌：客户端 Pos=[DefaultValue(0)] 会被 protobuf-net 省略，
        // 实际报文里没有 field1（NetProto\CCMsgRequestMagicBoxGivePrize.cs:15-16），
        // 所以这里也必须发空 body —— 服务端用 -1 兜底就会把这一路径全判非法。
        activity.onMagicBoxGivePrize(session, new GamePacket(MsgIds.C2S_MAGIC_BOX_GIVE_PRIZE, 13, new byte[0]));
        GamePacket ret = firstOf(channel, MsgIds.S2C_MAGIC_BOX_GIVE_PRIZE_RET);
        Assertions.assertNotNull(ret, "4404 必须回 4902");
        Pb.Fields body = Pb.read(ret.body);
        Assertions.assertEquals(cfg.diamondCost, body.getInt(3, -1),
                "4902 field3 CostRMB 必须是下一抽的钻石价（客户端 MagicBox.cs:1050 用它校验余额）");
        // field2 是翻牌位。客户端 CCMsgMagicBoxGivePrizeInfo.Pos 同样是 [DefaultValue(0)]
        // （NetProto\CCMsgMagicBoxGivePrizeInfo.cs:38-40），所以 pos=0 时服务端省略该字段，
        // 客户端反序列化仍得 0 → MagicBox.cs:114 mCardList[info.Pos] 指向第一张，等价。
        Assertions.assertEquals(0, body.getInt(2, 0),
                "field2 省略时客户端按 DefaultValue(0) 解出 0 = 第一张牌（与请求侧同一个约定）");
        // 开奖后补发 4901（服务端权威状态回灌）：客户端 4902 处理器不置 prize[].state，
        // 只有 4901 能让本地模型跟上（ActivityPropertyMgr.cs:832-844 → EN_REFRESH_MAGICBOX
        // 在 ActivityMainUI.cs:426 只刷兑换状态，不重建卡牌，不会打断翻牌动画）。
        GamePacket info = firstOf(channel, MsgIds.S2C_MAGIC_BOX_UPDATE);
        Assertions.assertNotNull(info, "4404 之后必须补一份 4901 回灌权威状态");
    }

    /**
     * 魔盒 4901 {@code prize[].quality} 是**降序档位**（1=最高档），不是物品真实品质。
     *
     * <p>APK 原始程序集（从 base.apk 现抠的 client_asm.dll）：{@code MagicBox.cs:353}
     * {@code SetActive(ccmsgMagicBoxPrize.quality <= 2)} 挂在高品质特效节点上、{@code :548}
     * {@code if (1 == ccmsgMagicBoxPrize.quality)} 才播品质粒子；同一个特效节点在
     * {@code BagUISystem.cs:2345} / {@code LunPanChouJiangUI.cs:199} / {@code BuyTreasureSystem.cs:1087}
     * 一律按真实品质 {@code >= 4} 打开 → 该字段 1=金（粒子+特效）、2=紫（特效）、≥3 无特效。
     * 修前服务端直接把真实品质 3/4/5 上线，两处判定都不成立 → 三档全无特效（用户 m05971 的观察）。
     */
    @Test
    public void magicBoxPrizeQualityIsDescendingGradeNotItemQuality() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        ActExtCfg.MagicFile cfg = actExt.magic();
        Assertions.assertNotNull(cfg, "tables/magicbox.json 必须存在");
        rec.bag.remove(cfg.goodsOri);
        store.save(rec);

        activity.onMagicBoxQuery(session, new GamePacket(MsgIds.C2S_MAGIC_BOX_INFO, 21, new byte[0]));
        GamePacket info = firstOf(channel, MsgIds.S2C_MAGIC_BOX_UPDATE);
        Assertions.assertNotNull(info, "4401 必须回 4901");
        List<byte[]> prizes = Pb.read(info.body).getBytesList(6);
        Assertions.assertEquals(9, prizes.size(), "9 格 = 金1 + 紫7 + 蓝1");

        int gold = 0;
        int purple = 0;
        int blue = 0;
        for (byte[] pb : prizes) {
            int q = Pb.read(pb).getInt(1, -1);
            Assertions.assertTrue(q >= 1 && q <= 5, "档位必须落在 1..5，实际 " + q);
            if (q == 1) {
                gold++;
            } else if (q == 2) {
                purple++;
            } else if (q == 3) {
                blue++;
            }
        }
        // grids: gold quality5 / purple quality4 / blue quality3 → 档位 1/2/3
        Assertions.assertEquals(1, gold, "金格（真实品质 5）上线档位必须是 1（== 1 才播粒子）");
        Assertions.assertEquals(7, purple, "紫格（真实品质 4）上线档位必须是 2（<= 2 开高品质特效）");
        Assertions.assertEquals(1, blue, "蓝格（真实品质 3）上线档位必须是 3（无特效）");
    }

    /**
     * 限时神将 3304 领宝箱：真服发的是**宝箱本体**（GoodsList BX191-194「一定获得…」），
     * 内容物在背包用 C2S 3101 开箱才产出。
     *
     * <p>为什么不能直接发内容物：客户端只用 GoodsList 解析 3702 的 PrizeGoodsName
     * （{@code WuPingTooltip.cs:106-107} 拿 propertyCfg 才填图标/名称、{@code XianShiShenJiang.cs:547-562}
     * 拿得到才冒字），而 EQ0044/EQ0078/TS105 都不在 GoodsList 里（装备在 EquipmentList，
     * 该管理器不加载）→ 悬浮 tooltip 空白 + 领奖无提示。BX191-194 的说明列正是
     * 「一定获得史诗勋章x3」这类描述，说明格子本来就该显示箱子、内容在开箱时结算。
     */
    @Test
    public void ltsjBoxPrizeGrantsBoxThenOpenYieldsFixedContents() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        ltsj.onQuery(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 14, new byte[0]));
        drain();
        PlayerRecord.Ltsj l = rec.ltsj;
        Assertions.assertNotNull(l, "3302 后必须有 ltsj 存档");
        Assertions.assertEquals("BX191", l.boxOri.get(0), "第一格应是 BX191 宝箱本体");
        Assertions.assertNotNull(economy.goods("BX191"),
                "3702 的 PrizeGoodsName 必须是 GoodsList 能解析的 ori，否则客户端 tooltip 空白（WuPingTooltip.cs:107）");

        l.boxState.set(0, Integer.valueOf(1));
        store.save(rec);

        ltsj.onGetPrize(session, new GamePacket(MsgIds.C2S_LTSJ_GET_PRIZE, 15, Pb.write(o -> Pb.int32(o, 1, 0))));
        List<GamePacket> out = drain();
        Assertions.assertEquals(1, rec.bag.getOrDefault("BX191", 0).intValue(), "领奖发的是箱子本体");
        Assertions.assertEquals(0, rec.equipments.size(), "领奖不发内容物（内容在开箱时结算）");
        Assertions.assertEquals(2, l.boxState.get(0).intValue(), "领完必须标 state=2（客户端靠 3705 关箱）");
        Assertions.assertTrue(has(out, MsgIds.S2C_LTSJ_BOX_STATE), "领完要推 3705");

        // 背包开箱 C2S 3101：BX191「一定获得史诗勋章x3」→ 3 个 EQ0044 实例 + 3 次 1406
        dungeon.onOpenBaoXiang(session, new GamePacket(MsgIds.C2S_OPEN_BAOXIANG, 17,
                Pb.write(o -> {
                    Pb.bool(o, 1, false);
                    Pb.string(o, 2, "BX191");
                    Pb.int32(o, 3, 1);
                })));
        List<GamePacket> opened = drain();
        Assertions.assertTrue(has(opened, MsgIds.S2C_OPEN_BAOXIANG), "开箱必须回 3501");
        Assertions.assertEquals(0, rec.bag.getOrDefault("BX191", 0).intValue(), "开箱消耗箱子");
        int eq0044 = 0;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if ("EQ0044".equals(eq.ori)) {
                eq0044++;
                Assertions.assertEquals(1, eq.stars, "stars=0 时装备至少 1 星（客户端星级显示为 1..mStart）");
            }
        }
        Assertions.assertEquals(3, eq0044, "BX191 一定获得史诗勋章x3");
        int equipPushes = 0;
        for (GamePacket p : opened) {
            if (p.msgId == MsgIds.S2C_ADD_EQUIP) {
                equipPushes++;
            }
        }
        Assertions.assertEquals(3, equipPushes, "每个实例都要推 1406（3501 的装备 award 依赖它先到）");

        // 重复领（state 已 2）：服务端拒绝，但必须回一份 3702 —— 客户端没有查询包
        // （XianShiShenJiang.cs 全量只有 3304 发送点），拒绝不回包时面板会停在「可领」动画上，
        // 表现为点了没反应；回 3702 才能让 Refresh() 收掉可领态。
        ltsj.onGetPrize(session, new GamePacket(MsgIds.C2S_LTSJ_GET_PRIZE, 16, Pb.write(o -> Pb.int32(o, 1, 0))));
        List<GamePacket> again = drain();
        Assertions.assertTrue(has(again, MsgIds.S2C_LTSJ_INFO), "拒绝领奖也必须回 3702（客户端无查询包）");
    }

    /**
     * 十连开混合箱：道具数量必须是「单次 × count」，不能是 count²。
     *
     * <p>曾经的坑：{@code BoxCurrency.mul(count)} 把 {@code alsoItemCount} 也乘了 count，
     * 而 {@code DungeonService.onOpenBaoXiang} 又是按 count 次循环、每次发 alsoItemCount 件
     * ⇒ 十连发的是 base×100。BX109（结衣碎片×2 + 英魄 200）十连会变成 200 片碎片。
     * 货币走 mul（一次性入账，正确），道具走循环（必须用单次量），两者语义不同。
     */
    @Test
    public void mixedBoxTenDrawItemCountIsLinearNotSquared() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.bag.clear();
        rec.yingPo = 0;
        rec.bag.put("BX109", Integer.valueOf(10));
        store.save(rec);

        dungeon.onOpenBaoXiang(session, new GamePacket(MsgIds.C2S_OPEN_BAOXIANG, 31,
                Pb.write(o -> {
                    Pb.bool(o, 1, false);
                    Pb.string(o, 2, "BX109");
                    Pb.int32(o, 3, 10);
                })));
        drain();

        Assertions.assertEquals(0, rec.bag.getOrDefault("BX109", 0).intValue(), "十连消耗 10 个箱");
        Assertions.assertEquals(20, rec.bag.getOrDefault("SP039", 0).intValue(), "结衣碎片必须是 2×10（不是 2×10×10）");
        Assertions.assertEquals(2000, rec.yingPo, "英魄是货币，按十连总量 200×10 一次性入账");
    }

    /**
     * 百层塔层 31+ 的材料箱（BX240 精炼催化剂 / BX241 精纯结晶）必须真的开出实物 ——
     * 它们的说明列自带区间（4~5 / 1~2），假服取区间下限。
     */
    @Test
    public void towerMaterialBoxesYieldRegisteredGoods() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.bag.clear();
        rec.bag.put("BX240", Integer.valueOf(1));
        rec.bag.put("BX241", Integer.valueOf(2));
        store.save(rec);

        dungeon.onOpenBaoXiang(session, new GamePacket(MsgIds.C2S_OPEN_BAOXIANG, 32,
                Pb.write(o -> {
                    Pb.bool(o, 1, true);
                    Pb.string(o, 2, "BX240");
                    Pb.int32(o, 3, 1);
                })));
        drain();
        Assertions.assertEquals(4, rec.bag.getOrDefault("JLFY02", 0).intValue(), "BX240 = 精炼催化剂 ×4（说明 4~5）");

        dungeon.onOpenBaoXiang(session, new GamePacket(MsgIds.C2S_OPEN_BAOXIANG, 33,
                Pb.write(o -> {
                    Pb.bool(o, 1, false);
                    Pb.string(o, 2, "BX241");
                    Pb.int32(o, 3, 2);
                })));
        drain();
        Assertions.assertEquals(2, rec.bag.getOrDefault("JLFY03", 0).intValue(), "BX241 = 精纯结晶 ×1 ×2 箱");
    }

    /**
     * 活动看板 2601：field5 curDayVpAwardType 即使为空串也必须写出。
     *
     * <p>APK：{@code ActivityStatusInfo.cs:531 info.curDayVpAwardType.Split(new char[]{'_'}, ...)}
     * 直接 Split，没有判空；字段被省略时反序列化可能是 null → 打开活动看板 NRE。
     * type1（体力）在 activities.json 里 enabled=true，未领过体力时该串就是空。
     */
    @Test
    public void activityStatusAlwaysWritesVpAwardType() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.activity.gainedVpTypes.clear();
        store.save(rec);

        activity.onQuery(session, new GamePacket(MsgIds.C2S_ACTIVITY_QUERY, 16, new byte[0]));
        GamePacket st = firstOf(channel, MsgIds.S2C_ACTIVITY_STATUS);
        Assertions.assertNotNull(st, "2318 必须回 2601");
        Pb.Fields body = Pb.read(st.body);
        Assertions.assertTrue(body.fieldKeys().contains(Integer.valueOf(5)),
                "2601 field5 必须写出（空串也要写 stringAlways，客户端直接 .Split）");
        Assertions.assertEquals("", body.getString(5), "没领过体力时 field5 应是空串");
    }

    /** 首充状态不按月重置：旧月份 key 不能让 payBuyCounts 归零（用户 2026-10-04 拍板）。 */
    @Test
    public void firstChargeStateIsNotResetMonthly() {
        PlayerRecord rec = freshPlayer();
        rec.economy.payBuyCounts.put(Integer.valueOf(9), Integer.valueOf(1));
        rec.economy.payExtMonthKey = "2020-01";
        store.save(rec);

        pay.ensurePayExtMonth(rec);

        Assertions.assertEquals(1,
                rec.economy.payBuyCounts.getOrDefault(Integer.valueOf(9), Integer.valueOf(0)).intValue(),
                "跨月不清 payBuyCounts（清了客户端 MainPlayer.cs:2540 会把「已首充」判回未充值、双倍数字复活）");
        Pb.Fields info = Pb.read(dump.userPayInfo(rec));
        boolean has1st = false;
        for (byte[] item : info.getBytesList(1)) {
            if (Pb.read(item).getInt(1, 0) <= 7) {
                has1st = true;
            }
        }
        Assertions.assertTrue(has1st,
                "userPayInfo 必须仍带 ID ≤ 7 的档位（UserPayCfg.GetCur1stChongZhiRMB() 靠它判已首充）");
    }

    /**
     * vip 邮件协议：mailType 8（每日礼包）/ 9（提升奖励）的 {0} 占位符在**正文**，等级必须写进 Paras。
     *
     * <p>APK：{@code Sys_MailConfig.txt:13-14} 第 8/9 行正文带 {0}；
     * {@code SysMailCfg.cs:50-84} 用 TitleParm 替换标题、Paras 替换正文（写错列表客户端正文会留「{0}」）；
     * {@code ESysMailTypeID.cs:24-27} EMTID_VIP_AWARD_EVERY_DAY=8 / EMTID_VIP_AWARD_ADD=9。
     *
     * <p>内容 APK 里没有表（只有文案），来自 {@code tables\vip-mail.json}。这里临时换一份启用版配置，
     * 验证「配置 → 邮件 → 客户端读的字段」整条链路，结束后恢复仓库里的真实配置。
     */
    @Test
    public void vipMailTypesCarryLevelInParas() throws Exception {
        Assertions.assertEquals(1, economy.vipLevel(60), "VipCfg：累计 60 钻 = VIP1，本测试前提");
        Path tmp = Paths.get("target/test-data/vip-mail-test.json");
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, ("{\"enabled\":true,\"levels\":{\"1\":{"
                + "\"daily\":{\"diamond\":120,\"items\":[{\"ori\":\"GOODS113\",\"count\":1}]},"
                + "\"add\":{\"diamond\":60}}}}}").getBytes(StandardCharsets.UTF_8));
        try {
            vipMail.reload(tmp);
            PlayerRecord rec = freshPlayer();
            // target/test-data 是跨次运行的持久存档：上一次跑留下的 vip 邮件会污染计数，
            // 这里按「本次新增」判定（grantKey 去重仍由 mailGrantKeys 保证，freshPlayer 已清）。
            rec.mails.clear();
            rec.economy.chargedDiamond = 60;

            mail.sendVipLevelAdd(rec, 1);
            mail.sendVipLevelAdd(rec, 1);
            Assertions.assertEquals(1, countMails(rec, MailService.MAIL_TYPE_VIP_ADD),
                    "提升奖励每级只发一封（mailGrantKeys=vip-add/1）");

            mail.grantDue(rec);
            PlayerRecord.Mail daily = firstMail(rec, MailService.MAIL_TYPE_VIP_DAILY);
            Assertions.assertNotNull(daily, "配了 daily 就该发 mailType 8");
            Assertions.assertEquals(1, countMails(rec, MailService.MAIL_TYPE_VIP_DAILY), "每日一封");
            Assertions.assertEquals(LocalDate.now() + "/vip-daily", daily.grantKey);

            // 客户端读的字段：外层 sysMail 在 field 2，里面 mailType=8、Paras[0]=等级、附件按 CSysMail 字段
            Pb.Fields inner = Pb.read(Pb.read(dump.oneMail(daily)).getBytes(2));
            Assertions.assertEquals(8, inner.getInt(2, 0), "mailType 必须是 8（Sys_MailConfig 第8行）");
            Assertions.assertEquals(Collections.singletonList("1"), inner.getStrings(12),
                    "vip 等级必须放 Paras（正文占位符），放 TitleParm 正文会留 {0}");
            Assertions.assertTrue(inner.getStrings(13).isEmpty(), "标题无占位符 → TitleParm 空");
            Assertions.assertEquals(120, inner.getInt(5, 0), "RMB/钻石附件 = 配置的 120");
        } finally {
            vipMail.reload(Paths.get(props.getTablesDir()).resolve("vip-mail.json"));
        }
    }

    /**
     * vip每日礼包（mailType 8）每天 0 点必须发到号上，包括跨夜在线的号。
     *
     * <p>APK：{@code Sys_MailConfig} 第 8 行「vip每日礼包」正文「你的是vip{0}级玩家，
     * 每日可领取如下vip专属奖励：」= 每天一封。原实现只有登录（{@code LoginService.java:137/:175}）、
     * 开邮箱（{@code MailService.onRequest}）与发邮钟点闸门（{@code daily-activity.json} 配的
     * 21:00 / 21:35 / 23:30，周一 0 点）会扫号 → 跨夜在线的号当天拿不到这封，
     * 要等玩家点开邮箱或重登。0 点补扫后在线号收 {@code S2C_MAIL_NOTIFY}（点邮箱图标）。
     */
    @Test
    public void vipDailyMailIsGrantedByMidnightSweep() {
        PlayerRecord rec = freshPlayer();
        bind(rec);
        rec.mails.clear();
        rec.economy.chargedDiamond = 60; // VipCfg：累计 60 钻 = VIP1
        store.save(rec);

        scheduler.dailyMidnightJobs();

        PlayerRecord.Mail daily = firstMail(rec, MailService.MAIL_TYPE_VIP_DAILY);
        Assertions.assertNotNull(daily, "0 点补发必须给 VIP1 的号发 mailType 8");
        Assertions.assertEquals(1, countMails(rec, MailService.MAIL_TYPE_VIP_DAILY), "每天一封");
        Assertions.assertEquals(LocalDate.now() + "/vip-daily", daily.grantKey);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_MAIL_NOTIFY), "在线号要收到新邮件通知");

        scheduler.dailyMidnightJobs();
        Assertions.assertEquals(1, countMails(rec, MailService.MAIL_TYPE_VIP_DAILY),
                "同一天再扫一遍不能重复发（mailGrantKeys=日期/vip-daily）");
    }

    /**
     * 在线号跨日：attri 17/18（今日累充 / 今日耗钻）与 3901（特权卡当日领取状态）必须重推。
     *
     * <p>APK：客户端这几个字段只由登录 dump（{@code MainPlayer.cs:319-320/:326-333}）或推送
     * （{@code MainPlayer.cs:943-950} 的 S2C 106、{@code PlayGameState.cs:7322-7338} 的 S2C 3901）写入，
     * **没有本地跨日重置**；而壕送大礼 / 消耗返利的面板数值与「可领」判定、大厅红点读 attri 17/18
     * （{@code ActivityMainUI.cs:2409/:2490/:2606/:2687}、{@code ActivityStatusInfo.cs:85/:103}），
     * 日常面板里月卡/至尊卡「每日返」按钮读 3901（{@code TaskSystem.cs:666-730}）。
     * 服端跨日已清 0 / 清标记，不推则在线跨夜的号仍显示昨天的数、按钮停在「已领取」，
     * 点 2313/2315 被服端按 0 静默拒、3501/3502 根本发不出去。客户端收到 attri 17/18 会立刻重查
     * 2312/2314（{@code ActivityMainUI.cs:123-126/434/437}），收到 3901 发 {@code EN_REFRESH_TEQUANFULI}。
     */
    @Test
    public void onlineCrossDayPushesTodayChargeAndCostReset() {
        PlayerRecord rec = freshPlayer();
        rec.economy.curDayChongZhiRmb = 98;
        rec.economy.curDayCostZuanShi = 300;
        rec.economy.dailyKey = LocalDate.now().minusDays(1).toString();
        store.save(rec);
        bind(rec);
        drain();

        progress.resetDailyChargeToOnline();

        Assertions.assertEquals(0, rec.economy.curDayChongZhiRmb);
        Assertions.assertEquals(0, rec.economy.curDayCostZuanShi);
        Assertions.assertEquals(LocalDate.now().toString(), rec.economy.dailyKey, "跨日懒结算要落到今天");
        List<GamePacket> out = drain();
        Assertions.assertEquals(0, attriValue(out, 17), "attri 17（今日累充）要推归零值");
        Assertions.assertEquals(0, attriValue(out, 18), "attri 18（今日耗钻）要推归零值");
        Assertions.assertTrue(has(out, MsgIds.S2C_TEQUAN_INFO),
                "3901 要重推：客户端 mTodayHasAwardTeQuanCard 没有本地跨日重置（TaskSystem.cs:719-730）");

        progress.resetDailyChargeToOnline();
        Assertions.assertFalse(has(drain(), MsgIds.S2C_ATTRI_UPDATE),
                "同一天再跑一遍不能重复推（ensureDaily 返回 false）");
    }

    /**
     * 跨档充值：mailType 9「vip提升奖励」按**跨过的每一级**各发一封（用户 2026-10-05 拍板 B）。
     *
     * <p>APK 没有「提升奖励」表，只能按正文推：{@code Sys_MailConfig} 第 9 行
     * 「你现在是vip{0}级玩家，提升vip带来的每日奖励补充如下：」= 每级一份补充。
     * 一次买齐跨多级时逐级补发，逐级防重键 {@code vip-add/{level}} 互不冲突
     * （{@code MailService.sendVipLevelAdd}），Paras 写该级等级。
     *
     * <p>14 号档（1998 元，常规钻 20000）= 0→VIP9，应得 9 封；再买一档 20000→40000 = VIP10，
     * 只跨 1 级 → 再多 1 封（单档行为与逐级循环前一致）。
     */
    @Test
    public void vipLevelAddMailIsSentPerCrossedLevel() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.mails.clear();
        rec.economy.chargedDiamond = 0;
        store.save(rec);
        EconomyTables.PayRow row = economy.pay(14);
        Assertions.assertNotNull(row, "UserPayGoods.txt 必须有 14 号档（1998 元）");
        Assertions.assertEquals(20000, row.diamond, "14 号档常规钻 = 20000（VipCfg L9），本测试前提");
        Assertions.assertEquals(9, economy.vipLevel(row.diamond), "20000 钻正好是 VIP9，本测试前提");

        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 7, Pb.write(o -> Pb.int32(o, 1, 14))));

        Assertions.assertEquals(9, countMails(rec, MailService.MAIL_TYPE_VIP_ADD),
                "0→VIP9 跨 9 级要发 9 封提升奖励（每级一封），只发最后一档会少发");
        Set<String> keys = new HashSet<>();
        for (PlayerRecord.Mail m : rec.mails) {
            if (m.mailType == MailService.MAIL_TYPE_VIP_ADD) {
                keys.add(m.grantKey);
            }
        }
        Assertions.assertEquals(9, keys.size(), "逐级防重键 vip-add/{level} 必须各不相同");
        Assertions.assertTrue(keys.contains("vip-add/1"), "跨过的每一级都要有一封");
        Assertions.assertTrue(keys.contains("vip-add/9"), "最高级那封要在");

        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 8, Pb.write(o -> Pb.int32(o, 1, 14))));
        Assertions.assertEquals(10, countMails(rec, MailService.MAIL_TYPE_VIP_ADD),
                "20000→40000 = VIP9→VIP10 只跨 1 级，只多 1 封");
    }

    /**
     * 仓库里真实的 {@code tables\vip-mail.json}：VIP 1..15 每级都要有内容，且 daily = 整行、add = 增量。
     *
     * <p>内容不是编的：daily 直接取 {@code VipCfg.txt} 第 35-46 列（客户端 VIP 面板
     * {@code VIPTeQuan/RewardGoods1-4} 用的同一份数据），add 是相对上一等级的逐项差值
     * （第 9 行文案「提升vip带来的每日奖励补充如下」）。
     */
    @Test
    public void shippedVipMailTableCoversEveryLevel() {
        Assertions.assertTrue(vipMail.enabled(), "tables\\vip-mail.json 应 enabled=true");
        for (int lv = 1; lv <= 15; lv++) {
            VipMailCfg.Level l = vipMail.level(lv);
            Assertions.assertNotNull(l, "VIP" + lv + " 没配内容");
            Assertions.assertFalse(l.daily.isEmpty(), "VIP" + lv + " daily 空");
            Assertions.assertFalse(l.add.isEmpty(), "VIP" + lv + " add 空");
        }
        // VIP1 = VipCfg 第 1 行：公会房契×1 + 稀有轻型护甲×1 + 乌冬面×5
        Assertions.assertEquals("GOODS113", vipMail.level(1).daily.items.get(0).ori);
        Assertions.assertEquals(1, vipMail.level(1).daily.items.get(0).count);
        // VIP15 = 公会房契×8 + 史诗之翼×1(3星) + ... + 至尊勋章×50
        VipMailCfg.Reward d15 = vipMail.level(15).daily;
        Assertions.assertEquals(4, d15.items.size());
        Assertions.assertEquals("EQ0076", d15.items.get(1).ori);
        Assertions.assertEquals(3, d15.items.get(1).stars, "VipCfg 星级原值 = 点亮星数（0 = 普通无星）");
        // 增量口径：VIP2 相对 VIP1 只多 1 个房契和一件装备，乌冬面数量没变 → 不该出现在 add 里
        for (VipMailCfg.Item it : vipMail.level(2).add.items) {
            Assertions.assertNotEquals("GOODS109", it.ori, "数量没变的不该出现在 add 里");
        }
        Assertions.assertEquals("EQ0024", vipMail.level(2).add.items.get(1).ori);
    }

    /**
     * 2618 首充档位：field 4/5/6/7 是四条**并行 repeated 列表**，必须等长。
     *
     * <p>APK：{@code ActivityPropertyMgr.cs:269-277} 以 {@code oriName.Count} 为循环上界，
     * 同一 num2 下标同时取 oriName/mStart/count/isShining 四列；protobuf-net 写 repeated 元素
     * 不省略默认值，而 {@code Pb.int32}/{@code Pb.bool} 会跳过 0/false → 用它们写就会短列，
     * 客户端越界抛 {@code ArgumentOutOfRangeException}（{@code PlayGameState.cs:7112-7117} 无 try/catch）
     * → 首充面板整块不刷新。这里用「第 1 档 stars=0、isShining=false」的原触发输入断言四列等长。
     */
    @Test
    public void firstChongZhiTiersKeepParallelListsAligned() {
        byte[] body = dump.firstChongZhiItem(0, 0, 6,
                Arrays.asList("EQ0024", "EQ0001", "EQ0002"),
                Arrays.asList(0, 0, 3),
                Arrays.asList(1, 2, 0),
                Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.TRUE));
        Pb.Fields f = Pb.read(body);
        Assertions.assertEquals(3, f.getStrings(4).size(), "field4 oriName 应有 3 项");
        Assertions.assertEquals(3, f.getInts(5).size(), "field5 mStart 必须与 oriName 等长（0 也要写）");
        Assertions.assertEquals(3, f.getInts(6).size(), "field6 count 必须与 oriName 等长（0 也要写）");
        Assertions.assertEquals(3, f.getInts(7).size(), "field7 isShining 必须与 oriName 等长（false 也要写）");
    }

    /**
     * 2317 被拒时必须回 2618 而不是 2615。
     *
     * <p>APK：{@code ActivityMainUI.cs:1949-1955} 点击时**先**把按钮置灰并写「领取中」再于
     * {@code :1956} 发 2317，唯一复位路径是重进 tab（2316→2618→{@code ActivityPropertyMgr.cs:280}）。
     * 且 {@code PlayGameState.cs:6882-6921 OnGet1stAwardSuc} 收到 2615 会**无条件**置
     * {@code mHasGet1stChongZhiAward=true} + 弹 100077「领取成功」+ 逐件冒字（2615 只有 items，
     * 没有 hasGet 标志）→ 拒绝时回 2615 等于假报成功，只能回 2618 刷新。
     */
    @Test
    public void firstChongZhiAwardDenyReplies2618Not2615() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.economy.hasGet1stChongZhiAward = true;
        store.save(rec);
        drain();

        activity.onFirstChongZhiAward(session,
                new GamePacket(MsgIds.C2S_FIRST_CHONGZHI_AWARD, 7, new byte[0]));
        List<GamePacket> out = drain();
        Assertions.assertFalse(has(out, MsgIds.S2C_FIRST_CHONGZHI_AWARD_RET),
                "拒绝不能回 2615：客户端会无条件弹「领取成功」");
        Assertions.assertTrue(has(out, MsgIds.S2C_FIRST_CHONGZHI_QUERY_RET),
                "拒绝必须回 2618 复位客户端已置灰的按钮");

        rec.economy.hasGet1stChongZhiAward = false;
        rec.economy.curDayChongZhiRmb = 0;
        store.save(rec);
        drain();
        activity.onFirstChongZhiAward(session,
                new GamePacket(MsgIds.C2S_FIRST_CHONGZHI_AWARD, 8, new byte[0]));
        out = drain();
        Assertions.assertFalse(has(out, MsgIds.S2C_FIRST_CHONGZHI_AWARD_RET), "未首充同样不能回 2615");
        Assertions.assertTrue(has(out, MsgIds.S2C_FIRST_CHONGZHI_QUERY_RET), "未首充也要回 2618");
    }

    /**
     * 十连在四字槽中途填满时**不能少发物品**。
     *
     * <p>原实现 {@code LtsjService} 的抽奖循环是「先 rollItem 再 rollWord，rollWord 返回 -1 就
     * break」→ 付满 3000 钻却只拿到 2 件。文字经验只影响槽位特效/宝箱解锁，不该中断整轮抽取。
     * 这里把四字都摆到 max-1，让十连的**第 5 次**起无槽可填，断言仍发满 10 件。
     */
    @Test
    public void ltsjTenDrawKeepsGrantingAfterWordsFillUp() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        // 先让 ensure 开一期（否则 dateKey 不匹配会被当成新期重置掉下面的摆位）
        ltsj.pushTo(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 9, new byte[0]));
        drain();
        int max = Math.max(1, ltsjCfg.cfg().wenZiMaxExp);
        rec.ltsj.wenZiExp = new ArrayList<>(Arrays.asList(max - 1, max - 1, max - 1, max - 1));
        rec.ltsj.boxState = new ArrayList<>(Arrays.asList(0, 0, 0, 0));
        rec.ltsj.wjState = 0;
        rec.diamond = 100000;
        store.save(rec);

        ltsj.onDrawTen(session, new GamePacket(MsgIds.C2S_LTSJ_DRAW_TEN, 10, new byte[0]));

        GamePacket prize = firstOf(channel, MsgIds.S2C_LTSJ_PRIZE);
        Assertions.assertNotNull(prize, "十连必须有 3703");
        Assertions.assertEquals(10, Pb.read(prize.body).getBytesList(6).size(),
                "四字填满只应停发文字经验，不能少发物品（原实现会 break 到 2 件）");
        GamePacket exp = firstOf(channel, MsgIds.S2C_LTSJ_WENZI_EXP);
        Assertions.assertNotNull(exp, "命中过文字槽就必须回 3704");
        Assertions.assertEquals(4, Pb.read(exp.body).getInts(1).size(), "只有 4 个槽，最多 4 次命中");
    }

    /**
     * 存量存档迁移：四格箱本体与配置不一致时按配置重写，不再等到 0 点才自愈。
     *
     * <p>#24 之前四格发的是「按 boxQuality 随机 roll 的普通道具」（真实存档
     * {@code data/players/admin.json} 为 {@code ZBSP46/ZBSP17/GOODS85/BX136}）。改配置后这类档
     * 当天既非跨日也非空期 → 3702 下发的还是旧道具名，长按浮名/领取冒字/实发产出全是旧的，
     * 也走不到 C2S 3101 开箱。迁移只重写 ori/num，保留 boxState/wenZiExp/wjState/freeLeft。
     */
    @Test
    public void ltsjStaleBoxArchiveIsMigratedToConfiguredBoxes() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        ltsj.pushTo(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 11, new byte[0]));
        drain();
        rec.ltsj.boxOri = new ArrayList<>(Arrays.asList("ZBSP46", "ZBSP17", "GOODS85", "BX136"));
        rec.ltsj.boxNum = new ArrayList<>(Arrays.asList(1, 2, 4, 9));
        rec.ltsj.boxState = new ArrayList<>(Arrays.asList(1, 0, 0, 0));
        rec.ltsj.wenZiExp = new ArrayList<>(Arrays.asList(50, 0, 0, 0));
        rec.ltsj.freeLeft = 1;
        store.save(rec);

        ltsj.pushTo(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 12, new byte[0]));

        Assertions.assertEquals(Arrays.asList("BX191", "BX192", "BX193", "BX194"), rec.ltsj.boxOri,
                "老档四格必须迁移成配置的箱本体");
        Assertions.assertEquals(Arrays.asList(1, 1, 1, 1), rec.ltsj.boxNum, "数量取配置（各 1）");
        Assertions.assertEquals(1, rec.ltsj.boxState.get(0), "迁移不能吞掉已解锁状态");
        Assertions.assertEquals(50, rec.ltsj.wenZiExp.get(0), "迁移不能重置文字经验");
        Assertions.assertEquals(1, rec.ltsj.freeLeft, "迁移不能重置免费次数");

        // 已迁移的档不该每次进大厅都重写（否则会覆盖后续手动改的配置态）
        rec.ltsj.boxOri = new ArrayList<>(Arrays.asList("BX191", "BX192", "BX193", "BX194"));
        rec.ltsj.boxNum = new ArrayList<>(Arrays.asList(1, 1, 1, 1));
        store.save(rec);
        ltsj.pushTo(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 13, new byte[0]));
        Assertions.assertEquals(Arrays.asList("BX191", "BX192", "BX193", "BX194"), rec.ltsj.boxOri);
    }

    /** 0 点调度对在线号重推 2601（活动看板态没有本地跨日重置，客户端只在开面板时发 2318）。 */
    @Test
    public void midnightSweepPushesActivityStatusToOnline() {
        PlayerRecord rec = freshPlayer();
        bind(rec);
        drain();
        activity.pushStatusToOnline();
        Assertions.assertTrue(has(drain(), MsgIds.S2C_ACTIVITY_STATUS), "0 点必须重推 2601");
    }

    /**
     * 心跳跨日分支要一并补推 3702 + 2601。
     *
     * <p>该分支原本只刷 2125/attri13-14/450（{@code LoginService.onHeartbeat}）。限时神将面板读静态
     * {@code XianShiShenJiang.mInfo}、活动看板读 {@code ActivityStatusInfo}，两者都没有本地跨日重置，
     * 且客户端没有 3302 查询包 → 只刷一半会让玩家面板一半是新一天、一半是昨天。
     */
    @Test
    public void heartbeatCrossDayAlsoPushesLtsjAndActivity() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.economy.dailyKey = LocalDate.now().minusDays(1).toString();
        store.save(rec);
        drain();

        login.onHeartbeat(session, new GamePacket(MsgIds.C2S_HEARTBEAT, 14, new byte[0]));
        List<GamePacket> out = drain();
        Assertions.assertTrue(has(out, MsgIds.S2C_LTSJ_INFO), "心跳跨日要补推 3702");
        Assertions.assertTrue(has(out, MsgIds.S2C_ACTIVITY_STATUS), "心跳跨日要补推 2601");
        Assertions.assertEquals(LocalDate.now().toString(), rec.economy.dailyKey);
    }

    /**
     * 重连（C2S 601）要补推 2601 + 3702。
     *
     * <p>APK：{@code NetworkManager.cs:464-491} 重连只发 601，不重走首次进区（102 路径才推）；
     * 限时神将面板读的是静态 {@code mInfo}，断线跨 0 点/换期后不重推会一直是旧数据。
     */
    @Test
    public void reconnectPushesActivityAndLtsj() {
        PlayerRecord rec = freshPlayer();
        store.save(rec);
        GameSession session = bind(rec);
        drain();

        login.onReconnect(session, new GamePacket(MsgIds.C2S_RECONNECT, 15,
                Pb.write(o -> Pb.string(o, 1, ACCOUNT))));
        List<GamePacket> out = drain();
        Assertions.assertTrue(has(out, MsgIds.S2C_RECONNECT_RET), "601 必须回 901");
        Assertions.assertTrue(has(out, MsgIds.S2C_ACTIVITY_STATUS), "重连要补推 2601");
        Assertions.assertTrue(has(out, MsgIds.S2C_LTSJ_INFO), "重连要补推 3702");
    }

    /**
     * 限时神将神将奖（pos=4）在**已拥有**该将时的折算碎片数必须走 WuJiangUpStar 折算表。
     *
     * <p>表依据：{@code tables\WuJiangUpStar.txt} 第 23-32 行「N星英雄折算碎片数」
     * = 8/15/30/50/80/120/170/230/300/350，下标是该将的 {@code composeStar}
     * （{@code CultivateTables.chaiJieFragForHero(:247)} → {@code chaiJieFragByStar(:238)}，
     * 与 {@code DrawService:227} 重复抽卡 / {@code DungeonService:923} 同一口径）。
     * 原先写死 150 —— 150 是「4星升5星所需」那一列，不属于任何折算档，已废。
     *
     * <p>这里走真实流程：3302 查询开一期 → 把该将塞进 rec.heroes（{@code LtsjService.owns}）+
     * wjState=1（四字全满可领）→ 3304 pos=4 领奖，断言 3702 的 field16/17
     * （WuJiangSuiPianCount / WuJiangSuiPianName）与实际入包数量，并确认没有发整卡。
     *
     * <p>{@code ltsj.json} 的 heroes 按自然日轮换，测试当天不一定是目标将，所以临时把当期
     * 英雄池改成目标将（{@code cfg().heroes} 是活对象），结束后恢复仓库配置。
     */
    @Test
    public void ltsjDuplicateHeroPrizeConvertsByStarTable() {
        int[][] cases = {{39, 15}, {40, 30}, {43, 50}, {47, 50}, {52, 50}};
        List<LtsjCfg.LtsjFile.HeroRow> shippedHeroes = ltsjCfg.cfg().heroes;
        try {
            for (int[] c : cases) {
                int heroIndex = c[0];
                Assertions.assertTrue(cultivate.isPlayableHero(heroIndex),
                        "英雄必须是可玩将（有合成碎片）：" + heroIndex);
                Assertions.assertEquals(c[1], cultivate.chaiJieFragForHero(heroIndex),
                        "WuJiangUpStar 折算表：heroIndex " + heroIndex + " 的 composeStar 折算碎片数");

                LtsjCfg.LtsjFile.HeroRow only = new LtsjCfg.LtsjFile.HeroRow();
                only.heroIndex = heroIndex;
                ltsjCfg.cfg().heroes = new ArrayList<>(Collections.singletonList(only));

                PlayerRecord rec = freshPlayer();
                GameSession session = bind(rec);
                // 先开一期（dateKey/heroIndex 落定），否则 3304 里的 ensure 会把 wjState 重置回 0
                ltsj.onQuery(session, new GamePacket(MsgIds.C2S_LTSJ_INFO, 20, new byte[0]));
                drain();

                rec.heroes.clear();
                PlayerRecord.Hero owned = new PlayerRecord.Hero();
                owned.heroIndex = heroIndex;
                owned.id = PlayerDumpService.guidOf(ACCOUNT, heroIndex);
                rec.heroes.add(owned);
                rec.ltsj.wjState = 1;
                store.save(rec);
                // target/test-data 里的背包会跨次运行残留，所以只断言「领取增量」
                Map<String, Integer> bagBefore = new HashMap<>(rec.bag);

                ltsj.onGetPrize(session, new GamePacket(MsgIds.C2S_LTSJ_GET_PRIZE, 30,
                        Pb.write(o -> Pb.int32(o, 1, 4))));
                List<GamePacket> out = drain();
                GamePacket info = null;
                for (GamePacket p : out) {
                    if (p.msgId == MsgIds.S2C_LTSJ_INFO) {
                        info = p;
                        break;
                    }
                }
                Assertions.assertNotNull(info, "领神将必须回 3702（heroIndex=" + heroIndex + "）");
                Pb.Fields f = Pb.read(info.body);
                Assertions.assertEquals(c[1], f.getInt(16, -1),
                        "3702 field16 WuJiangSuiPianCount 必须是折算表值，不能是写死的 150（heroIndex="
                                + heroIndex + "）");
                String fragOri = f.getString(17);
                Assertions.assertNotNull(fragOri,
                        "3702 field17 WuJiangSuiPianName 必须写出（heroIndex=" + heroIndex + "）");
                Assertions.assertFalse(fragOri.isEmpty(), "碎片 ori 不能为空（heroIndex=" + heroIndex + "）");
                Assertions.assertEquals(c[1],
                        rec.bag.getOrDefault(fragOri, Integer.valueOf(0)).intValue()
                                - bagBefore.getOrDefault(fragOri, Integer.valueOf(0)).intValue(),
                        "折算碎片必须作为领取增量进背包（heroIndex=" + heroIndex + "，之前已有 "
                                + bagBefore.getOrDefault(fragOri, Integer.valueOf(0)) + " 个）");
                Assertions.assertEquals(c[1], rec.ltsj.wjSuiPianCount, "落档的碎片数必须与下发的 3702 一致");
                Assertions.assertEquals(fragOri, rec.ltsj.wjSuiPianName, "落档的碎片 ori 必须与 3702 一致");
                Assertions.assertEquals(2, rec.ltsj.wjState, "领完必须标 wjState=2");
                Assertions.assertTrue(has(out, MsgIds.S2C_LTSJ_BOX_STATE), "领完要推 3705 复位格子");
                Assertions.assertFalse(has(out, MsgIds.S2C_ADD_WUJIANG),
                        "已拥有该将不能再发整卡（heroIndex=" + heroIndex + "）");
                Assertions.assertEquals(1, rec.heroes.size(),
                        "已拥有的将不该被重复加入英雄列表（heroIndex=" + heroIndex + "）");
            }
        } finally {
            ltsjCfg.cfg().heroes = shippedHeroes;
        }
    }

    /**
     * 限时神将抽奖物品池的排除口径（{@code LtsjCfg.buildGoodsPool}）。
     *
     * <p>与随机箱产品池 {@code EconomyTables.buildBoxPools}（:642-665）同口径：
     * 属性 {@code <=0 / 6 / 10（随机箱）/ 12（喇叭）/ 14（双倍充值卡）/ 15（自选箱）} 一律排除，
     * {@code BX/WSJL/HTSP/HTJB/FBSGS} 前缀的箱子排除，武将碎片（属性 2）只收
     * {@code 0 < attrP1 < 1000} 的可玩 index —— 否则会抽到「箱子里开箱子」和功能道具。
     *
     * <p>池是私有的（只经 {@code rollGoodsOri} 暴露），这里反射取全量逐项校验而不是抽样，
     * 并反证过滤非空转（确实存在「品质 3-5 但按规则该排除」的商品）。
     */
    @Test
    public void ltsjDrawPoolExcludesNonPlayableGoods() throws Exception {
        Field field = LtsjCfg.class.getDeclaredField("goodsPool");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, List<String>> pool = (Map<Integer, List<String>>) field.get(ltsjCfg);

        int total = 0;
        StringBuilder perQuality = new StringBuilder();
        for (int q = 3; q <= 5; q++) {
            List<String> list = pool.get(q);
            Assertions.assertNotNull(list, "品质 " + q + " 必须有池（可为空列表）");
            total += list.size();
            perQuality.append(" q").append(q).append('=').append(list.size());
            for (String ori : list) {
                EconomyTables.GoodsCfg g = economy.goods(ori);
                Assertions.assertNotNull(g, "池里的 ori 必须在 GoodsList 里：" + ori);
                Assertions.assertEquals(q, g.quality, "ori 必须落在所属品质池：" + ori);
                int a = g.attrType;
                Assertions.assertTrue(a > 0 && a != 6 && a != 10 && a != 12 && a != 14 && a != 15,
                        "功能道具/箱不能进抽奖池（attrType=" + a + "）：" + ori);
                Assertions.assertFalse(
                        ori.startsWith("BX") || ori.startsWith("WSJL") || ori.startsWith("HTSP")
                                || ori.startsWith("HTJB") || ori.startsWith("FBSGS"),
                        "箱类 ori 不能进抽奖池：" + ori);
                if (a == 2) {
                    Assertions.assertTrue(g.attrP1 > 0 && g.attrP1 < 1000,
                            "武将碎片只收可玩 index（0<attrP1<1000），attrP1=" + g.attrP1 + "：" + ori);
                }
            }
        }
        Assertions.assertTrue(total > 0,
                "抽奖池不能为空，否则 rollGoodsOri 每次都兜底 PY001");

        // 反证：确认过滤真的在干活（否则上面全是通过的空断言）
        int wouldBeExcluded = 0;
        for (EconomyTables.GoodsCfg g : economy.allGoods()) {
            if (g == null || g.ori == null || g.ori.isEmpty() || g.quality < 3 || g.quality > 5) {
                continue;
            }
            int a = g.attrType;
            boolean excluded = a <= 0 || a == 6 || a == 10 || a == 12 || a == 14 || a == 15
                    || g.ori.startsWith("BX") || g.ori.startsWith("WSJL") || g.ori.startsWith("HTSP")
                    || g.ori.startsWith("HTJB") || g.ori.startsWith("FBSGS")
                    || (a == 2 && (g.attrP1 <= 0 || g.attrP1 >= 1000)) || "0".equals(g.ori);
            if (excluded) {
                wouldBeExcluded++;
                Assertions.assertFalse(pool.get(g.quality).contains(g.ori),
                        "按规则该排除的商品出现在池里：" + g.ori + " attrType=" + a + " attrP1=" + g.attrP1);
            }
        }
        Assertions.assertTrue(wouldBeExcluded > 0,
                "GoodsList 里应存在品质 3-5 但按规则该排除的商品，否则这条测试是空转");
        System.out.println("[ltsj-pool] total=" + total + perQuality
                + " excludedByRules=" + wouldBeExcluded);
    }

    /**
     * 充值后必须推 EAttriType=16（EAT_RMB_BUY，累计充值 RMB），普通档与买卡两条路径都要推。
     *
     * <p>APK：{@code MainPlayer.cs:940-942 case 16: mCurBuyRMB = intValue}；登录只读 detail
     * field47 填一次初值，运行中不再回读 → 充值后不推 16，本次会话内 {@code mCurBuyRMB}
     * 一直停在上次登录的值（按累计充值判定的入口如三日充会一直看到旧值）。
     * 覆盖两处生产修复：{@code PayService.grant}（普通档）与 {@code PayService.grantCard}（月卡/至尊卡）。
     */
    @Test
    public void rmbChongZhiAttri16IsPushedByPayAndByCard() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        EconomyTables.PayRow normal = economy.pay(8);
        Assertions.assertNotNull(normal, "UserPayGoods.txt 必须有 8 号档（6 元 60 钻）");
        Assertions.assertEquals(6, normal.rmb, "8 号档充值金额必须是 6 元，本测试前提");

        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 20, Pb.write(o -> Pb.int32(o, 1, 8))));
        List<GamePacket> out = drain();
        Assertions.assertEquals(6, rec.economy.rmbChongZhi, "普通档充值后累计充值 RMB 必须是 6");
        Assertions.assertEquals(6, attriValue(out, 16), "普通档充值必须推 attri16=6");

        EconomyTables.PayRow month = economy.pay(1001);
        Assertions.assertNotNull(month, "UserPayGoods.txt 必须有 1001 月卡");
        Assertions.assertEquals(25, month.rmb, "月卡充值金额必须是 25 元，本测试前提");
        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 21, Pb.write(o -> Pb.int32(o, 1, 1001))));
        out = drain();
        Assertions.assertEquals(31, rec.economy.rmbChongZhi, "买月卡后累计充值 RMB 必须是 6+25=31");
        Assertions.assertEquals(31, attriValue(out, 16), "买月卡（grantCard type2）必须推 attri16=31");

        EconomyTables.PayRow zhiZun = economy.pay(1003);
        Assertions.assertNotNull(zhiZun, "UserPayGoods.txt 必须有 1003 至尊卡");
        Assertions.assertEquals(98, zhiZun.rmb, "至尊卡充值金额必须是 98 元，本测试前提");
        pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 22, Pb.write(o -> Pb.int32(o, 1, 1003))));
        out = drain();
        Assertions.assertEquals(129, rec.economy.rmbChongZhi, "买至尊卡后累计充值 RMB 必须是 31+98=129");
        Assertions.assertEquals(129, attriValue(out, 16), "买至尊卡（grantCard type4）也必须推 attri16=129");
    }

    /**
     * 特权卡物品列端到端：TeQuanCard.txt 0 基列 5/6 = 购买立即返物品/数量、列 8/9 = 每日返物品/数量。
     *
     * <p>APK 自带表两张卡这 4 列全是 0（真表下的断言见 {@code EconomyTablesTest}）→ 当前恒无物品；
     * 本用例把列 5/6/8/9 填成 GOODS3×2 / GOODS4×5 后整目录重载（{@code SaoProperties.setTablesDir}
     * + {@code EconomyTables.init} 是唯一重载入口，{@code teQuanByType} 是私有映射、没有单表 reload），
     * 验证：买卡按列 6 发立即返物品 + 3902 field3/field4 带上 ori/数量（{@code dump.teQuanAward}
     * 新签名），3501 每日领按列 9 发每日返物品。
     *
     * <p>改的是 target/test-data 下的**整目录副本**，仓库里的 {@code tables\TeQuanCard.txt} 一个字节
     * 都不动；finally 把 tablesDir 指回原目录并再 init 一次，同 JVM 其它用例看不到改过的表。
     */
    @Test
    public void teQuanCardGoodsColumnsGrantItemsOnBuyAndDaily() throws IOException {
        String originalDir = props.getTablesDir();
        Path src = Paths.get(originalDir);
        Assertions.assertTrue(Files.isRegularFile(src.resolve("TeQuanCard.txt")),
                "真表目录里必须有 TeQuanCard.txt：" + src);
        Path tmpDir = Paths.get("target/test-data/tables-tequan-live").toAbsolutePath();
        copyTablesDir(src, tmpDir);
        writeTeQuanGoodsColumns(tmpDir.resolve("TeQuanCard.txt"), 2, "GOODS3", 2, "GOODS4", 5);
        try {
            props.setTablesDir(tmpDir.toString());
            economy.init();
            EconomyTables.TeQuanRow card = economy.teQuan(2);
            Assertions.assertNotNull(card, "临时表里必须有 type2 月卡");
            Assertions.assertEquals("GOODS3", card.buyGoodsOri, "列 5 必须解析成立即返物品 GOODS3");
            Assertions.assertEquals(2, card.buyGoodsCount, "列 6 必须解析成数量 2");
            Assertions.assertEquals("GOODS4", card.dailyGoodsOri, "列 8 必须解析成每日返物品 GOODS4");
            Assertions.assertEquals(5, card.dailyGoodsCount, "列 9 必须解析成数量 5");

            PlayerRecord rec = freshPlayer();
            GameSession session = bind(rec);
            int bag3Before = rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue();
            int bag4Before = rec.bag.getOrDefault("GOODS4", Integer.valueOf(0)).intValue();

            pay.onSdkPayId(session, new GamePacket(MsgIds.C2S_SDK_PAY_ID, 30, Pb.write(o -> Pb.int32(o, 1, 1001))));
            List<GamePacket> out = drain();
            int bag3 = rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue() - bag3Before;
            Assertions.assertEquals(2, bag3,
                    "买月卡必须按列 6 数量 2 发立即返物品 GOODS3（断言增量：target/test-data 跨次运行会残留）");
            Assertions.assertTrue(has(out, MsgIds.S2C_UPDATE_GOODS), "立即返物品必须推 S2C 301 让客户端入包");
            GamePacket buyRet = null;
            for (GamePacket p : out) {
                if (p.msgId == MsgIds.S2C_TEQUAN_BUY) {
                    buyRet = p;
                }
            }
            Assertions.assertNotNull(buyRet, "买卡必须回 3902");
            Pb.Fields f = Pb.read(buyRet.body);
            Assertions.assertEquals(2, f.getInt(1, -1), "3902 field1 = 特权卡类型 2");
            Assertions.assertEquals("GOODS3", f.getString(3), "3902 field3 = 立即返物品 ori");
            Assertions.assertEquals(2, f.getInt(4, -1), "3902 field4 = 立即返物品数量");

            pay.onTeQuanDaily(session, new GamePacket(MsgIds.C2S_TEQUAN_DAILY, 31, new byte[0]), false);
            out = drain();
            int bag4 = rec.bag.getOrDefault("GOODS4", Integer.valueOf(0)).intValue() - bag4Before;
            Assertions.assertEquals(5, bag4, "每日领奖必须按列 9 数量 5 发每日返物品 GOODS4");
            Assertions.assertTrue(has(out, MsgIds.S2C_UPDATE_GOODS), "每日返物品也要推 S2C 301");
            Assertions.assertTrue(has(out, MsgIds.S2C_TEQUAN_INFO), "3501 必须回 3901");
        } finally {
            props.setTablesDir(originalDir);
            economy.init();
        }
    }

    /* ---------------- 工具 ---------------- */

    /** 把 tables/ 整目录复制到 dest（先清空 dest），供「改表再重载」用例用；不动仓库跟踪文件。 */
    private static void copyTablesDir(Path src, Path dest) throws IOException {
        if (Files.isDirectory(dest)) {
            Files.walk(dest).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(dest);
        List<Path> files = new ArrayList<>();
        Files.walk(src).filter(Files::isRegularFile).forEach(files::add);
        for (Path f : files) {
            Path target = dest.resolve(src.relativize(f).toString());
            Files.createDirectories(target.getParent());
            Files.copy(f, target);
        }
    }

    /** 改写 TeQuanCard.txt 里 type 那一行的 4 个物品列（0 基 5/6/8/9），其余列原样保留。 */
    private static void writeTeQuanGoodsColumns(Path file, int type,
            String buyOri, int buyCount, String dailyOri, int dailyCount) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean patched = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length < 10 || !String.valueOf(type).equals(cols[1])) {
                continue;
            }
            cols[5] = buyOri;
            cols[6] = String.valueOf(buyCount);
            cols[8] = dailyOri;
            cols[9] = String.valueOf(dailyCount);
            lines.set(i, String.join("\t", cols));
            patched = true;
        }
        Assertions.assertTrue(patched, "TeQuanCard.txt 必须有 type=" + type + " 那张卡");
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static int countMails(PlayerRecord rec, int mailType) {
        int n = 0;
        for (PlayerRecord.Mail m : rec.mails) {
            if (m.mailType == mailType) {
                n++;
            }
        }
        return n;
    }

    private static PlayerRecord.Mail firstMail(PlayerRecord rec, int mailType) {
        for (PlayerRecord.Mail m : rec.mails) {
            if (m.mailType == mailType) {
                return m;
            }
        }
        return null;
    }

    private PlayerRecord freshPlayer() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        // D5 相关的存档位全部清成「全新账号」：测试数据落在 target/ 下会跨次运行残留
        rec.economy.payBuyCounts.clear();
        rec.economy.firstPayIds.clear();
        rec.economy.payExtMonthKey = "";
        rec.economy.chargedDiamond = 0;
        rec.economy.rmbChongZhi = 0;
        rec.economy.curDayChongZhiRmb = 0;
        rec.economy.vipAwardGetInfo = "";
        rec.economy.mailGrantKeys.clear();
        rec.economy.hasGet1stChongZhiAward = false;
        rec.cards.teQuanEnd = "";
        rec.cards.zhiZun = false;
        rec.cards.todayTeQuan = false;
        rec.cards.todayZhiZun = false;
        rec.activity.gainedVpTypes.clear();
        rec.activity.magicBox.prize.clear();
        rec.activity.magicBox.record.clear();
        rec.activity.magicBox.dateKey = "";
        rec.activity.magicBox.countToday = 0;
        rec.activity.magicBox.xiPaiState = 0;
        rec.equipments.clear();
        rec.ltsj = new PlayerRecord.Ltsj();
        store.save(rec);
        return rec;
    }

    private GameSession bind(PlayerRecord rec) {
        channel = new EmbeddedChannel();
        GameSession session = new GameSession(channel);
        session.setAccount(ACCOUNT);
        session.setPlayer(rec);
        hub.bind(session);
        return session;
    }

    private List<GamePacket> drain() {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = channel.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
    }

    private static boolean has(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return true;
            }
        }
        return false;
    }

    private GamePacket firstOf(EmbeddedChannel ch, int msgId) {
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
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
