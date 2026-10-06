package com.sao.fakeserver.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.handler.MessageDispatcher;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.VipGiftCfg;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * VIP 礼包（看板 type5「VIP特权礼包」）按 APK 真实序列走一遍：C2S 2309 查档位 → S2C 2617、
 * C2S 2310 查已购 → S2C 2609、C2S 2311 购买 → S2C 2610（成功）。
 *
 * <p>协议依据（{@code pyfoot\tmp_msgdll\NetProto\CCMsgQueryVIPGiftItem.cs}）：
 * 1 mID / 2 mVipLevel / 3 mOriName / 4 mCount / 5 mNeedRMB；客户端
 * {@code ActivityPropertyMgr.UpdateVIPGiftCfg}（:313-332）把 2617 的 items 填进 {@code mVipBuy}，
 * {@code ActivityMainUI.RefreshVIPBuyed}（:2787-2789）存 2609 的 {@code curBoughtVIPgifts} 串并做
 * {@code Contains("|id|")} 判断；2311 的响应 2610 {@code buyID} 客户端**没有注册处理器**，
 * 买完真正生效的刷新是 2609，所以成功/拒绝两条路都必须重推 2609。
 *
 * <p>VIP 等级口径：{@code VipManager.ReCalVIPLevel()} 用 {@code Attribute.mCurBuyZuanShi}
 * （= 假服 {@code rec.economy.chargedDiamond}，attri 10）按 {@code VipCfg.txt}「累计钻石数量」现算，
 * 服务端同口径 {@link EconomyTables#vipLevel(int)}（L1=60 / L2=300 / … / L15=200000）。
 * 「钻石余额」在假服就是 {@code rec.diamond}（登录 detail field7），故购买扣它。
 *
 * <p>档位表由测试自行换成临时 json（{@code VipGiftCfg.reload(Path)}），**每个用例 finally 还原**
 * 仓库里的 {@code tables\vip-gift.json}；测试数据落在 {@code target/test-data/players-vip-gift-flow}，
 * 跨次运行会残留，所以背包一律断言「本次增量」，每例都从重置过的存档开始。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-vip-gift-flow",
        "sao.world-dir=target/test-data/world-vip-gift-flow",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class VipGiftFlowSimTest {
    /** 礼包流程主账号。 */
    private static final String ACCOUNT = "test-vip-gift-flow";
    /** 查已购的第二个账号（空串已购串）。 */
    private static final String OTHER = "test-vip-gift-other";

    /** 仓库里的真实档位表（默认 enabled=true / items=[]）：每个临时改动都还原到它。 */
    private static final String SHIPPED = "vip-gift.json";

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
    @Autowired
    private VipGiftCfg cfg;
    @Autowired
    private SaoProperties props;

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
     * 默认空表：发 2309（空包）也必须回 2617，且 items（field1）一次都不出现。
     *
     * <p>客户端靠这个回包清空 {@code mVipBuy}（{@code ActivityPropertyMgr.UpdateVIPGiftCfg}），
     * 不回包面板会保留上一次的档位；空表时写 field1 反而会让 read 出 length-0 元素。
     */
    @Test
    public void vipGiftConfigQueryReturnsEmptyTableByDefault() {
        cfg.reload(shippedCfg());
        Assertions.assertTrue(cfg.enabled(), "tables\\vip-gift.json 应 enabled=true（本测试前提）");
        Assertions.assertEquals(0, cfg.items().size(), "tables\\vip-gift.json 默认 items 为空（本测试前提）");
        PlayerRecord rec = freshPlayer(ACCOUNT, 100, 0);
        GameSession s = bind(rec);

        dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_CONFIG_QUERY, 21, new byte[0]));

        List<GamePacket> out = drain(s.channel());
        GamePacket ret = firstOf(out, MsgIds.S2C_VIP_GIFT_CONFIG_RET);
        Assertions.assertNotNull(ret, "空表也必须回 2617（客户端靠回包清空 mVipBuy）");
        Assertions.assertEquals(21, ret.serial, "2617 必须带请求 serial");
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(0, f.getBytesList(1).size(), "默认空表 items 个数必须为 0");
        Assertions.assertFalse(f.fieldKeys().contains(Integer.valueOf(1)),
                "items 为空时 field1 一次都不能出现（field1 = repeated CCMsgQueryVIPGiftItem）");
    }

    /**
     * 配了档位：2617 的每条 f1..f5 必须与 json 完全一致，且五个字段都**写出**（含 0/空值）。
     *
     * <p>字段布局见 {@code CCMsgQueryVIPGiftItem}：1 mID / 2 mVipLevel / 3 mOriName / 4 mCount /
     * 5 mNeedRMB。服务端用 {@code Pb.int32Always}/{@code stringAlways} 写：真服 protobuf-net
     * 对 message 字段不省略默认值，省略会让客户端读出 null（{@code VipBuyItem.OnItemPressed}
     * 拿 mOriName 去 GetPropertyCfg 会走空指针分支）。
     */
    @Test
    public void vipGiftConfigQueryReturnsConfiguredRows() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-two.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":7,\"vipLevel\":3,\"ori\":\"GOODS113\",\"count\":2,\"needRmb\":60},"
                + "{\"id\":9,\"vipLevel\":5,\"ori\":\"EQ0044\",\"count\":1,\"needRmb\":198}]}");
        try {
            cfg.reload(tmp);
            Assertions.assertTrue(cfg.enabled(), "解析成功后 enabled 必须为 true");
            Assertions.assertEquals(2, cfg.items().size(), "两档 json 必须解析出 2 条");
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 0);
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_CONFIG_QUERY, 22, new byte[0]));

            List<GamePacket> out = drain(s.channel());
            GamePacket ret = firstOf(out, MsgIds.S2C_VIP_GIFT_CONFIG_RET);
            Assertions.assertNotNull(ret, "2309 必须回 2617");
            List<byte[]> items = Pb.read(ret.body).getBytesList(1);
            Assertions.assertEquals(2, items.size(), "2617 field1 必须正好 2 条档位");

            assertItem(items.get(0), 7, 3, "GOODS113", 2, 60);
            assertItem(items.get(1), 9, 5, "EQ0044", 1, 198);
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /**
     * 2309/2310 的「已购」串：2609 f1 = {@code PlayerRecord.Economy.vipBuyedGifts} 原样下发，
     * 空串也要写出（客户端 {@code RefreshVIPBuyed} 直接存串并 {@code Contains}，null 会空指针）。
     */
    @Test
    public void vipGiftBoughtQueryReturnsStoredString() {
        PlayerRecord rec = freshPlayer(ACCOUNT, 100, 0);
        GameSession s = bind(rec);

        rec.economy.vipBuyedGifts = "|7||9|";
        dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BOUGHT_QUERY, 23, new byte[0]));
        GamePacket ret = firstOf(drain(s.channel()), MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
        Assertions.assertNotNull(ret, "2310 必须回 2609");
        Assertions.assertEquals("|7||9|", Pb.read(ret.body).getString(1),
                "2609 f1 必须原样下发已购串");

        PlayerRecord blank = freshPlayer(OTHER, 100, 0);
        GameSession sb = bind(blank);
        dispatcher.dispatch(sb, packet(MsgIds.C2S_VIP_GIFT_BOUGHT_QUERY, 24, new byte[0]));
        GamePacket ret2 = firstOf(drain(sb.channel()), MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
        Assertions.assertNotNull(ret2, "空已购串也要回 2609");
        Pb.Fields f2 = Pb.read(ret2.body);
        Assertions.assertTrue(f2.fieldKeys().contains(Integer.valueOf(1)),
                "2609 f1 空串也必须写出（stringAlways，客户端直接存串）");
        Assertions.assertEquals("", f2.getString(1), "没买过时已购串是空串");
    }

    /**
     * 购买成功：2610 f1=id + 重推 2609（含 "|id|"）+ 扣钻 + 发道具 + 推 106 钻石 + 落档。
     *
     * <p>客户端依据：{@code VipBuyItem.OnBuyClicked}（:133-165）先查 {@code mCurRMB < mNeedRMB}
     * 与 {@code GetVIPLevel() < mVipLevel}，通过才发 2311；本服务成功时既回 2610（对齐真服协议）
     * 也重推 2609（2610 客户端没注册处理器，真正刷面板的是 2609）。
     */
    @Test
    public void vipGiftBuySuccessDeductsDiamondGrantsGoodsAndMarksBought() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-goods.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":1,\"vipLevel\":1,\"ori\":\"GOODS3\",\"count\":5,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            Assertions.assertEquals(1, economy.vipLevel(60),
                    "VipCfg：累计 60 钻 = VIP1（档位 vipLevel=1 才可买，本测试前提）");
            int bagBefore = rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue();
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 25, buyBody(1)));

            List<GamePacket> out = drain(s.channel());
            GamePacket buy = firstOf(out, MsgIds.S2C_VIP_GIFT_BUY_RET);
            Assertions.assertNotNull(buy, "购买成功必须回 2610");
            Assertions.assertEquals(1, Pb.read(buy.body).getInt(1, -1), "2610 f1 = buyID = 档位 id");
            GamePacket bought = firstOf(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
            Assertions.assertNotNull(bought, "购买成功后必须重推 2609（2610 客户端没有处理器）");
            Assertions.assertTrue(Pb.read(bought.body).getString(1).contains("|1|"),
                    "2609 f1 必须含 \"|1|\"（客户端 Contains 判已购）");
            Assertions.assertEquals(70, rec.diamond, "钻石必须正好扣 needRmb=30（100-30）");
            Assertions.assertEquals(5,
                    rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue() - bagBefore,
                    "GOODS3 必须按 count=5 进背包（断言增量：target/test-data 跨次运行会残留）");
            Assertions.assertEquals("|1|", rec.economy.vipBuyedGifts, "已购串必须追加 \"|1|\"");

            Assertions.assertEquals(70, attriValue(out, 4),
                    "必须推 106（attri type4=钻石）新值 70");

            PlayerRecord disk = reloadFromDisk(ACCOUNT);
            Assertions.assertEquals(70, disk.diamond, "落档的钻石必须是 70");
            Assertions.assertEquals("|1|", disk.economy.vipBuyedGifts, "落档的已购串必须是 \"|1|\"");
            Assertions.assertEquals(5,
                    disk.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue() - bagBefore,
                    "落档的背包必须含本次 5 个 GOODS3");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /** 档位不存在（id=99）：不回 2610，只重推 2609，钻石与已购串都不动。 */
    @Test
    public void vipGiftBuyDeniesUnknownItem() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-unknown.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":1,\"vipLevel\":1,\"ori\":\"GOODS3\",\"count\":5,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            int bagBefore = rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue();
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 26, buyBody(99)));

            List<GamePacket> out = drain(s.channel());
            Assertions.assertFalse(has(out, MsgIds.S2C_VIP_GIFT_BUY_RET),
                    "档位不存在不能回 2610（客户端没有错误码可用，回不存在的 buyID 只会让协议失真）");
            GamePacket bought = firstOf(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
            Assertions.assertNotNull(bought, "拒绝必须重推 2609 让面板回到真实状态");
            Assertions.assertEquals("", Pb.read(bought.body).getString(1), "被拒后已购串仍是空串");
            Assertions.assertEquals(100, rec.diamond, "被拒后不能扣钻");
            Assertions.assertEquals("", rec.economy.vipBuyedGifts, "被拒后已购串不能变");
            Assertions.assertEquals(bagBefore, rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue(),
                    "被拒后不能发道具");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /** 已购过（已购串含 "|1|"）：不回 2610，只重推 2609，钻石与背包都不动。 */
    @Test
    public void vipGiftBuyDeniesAlreadyBought() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-bought.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":1,\"vipLevel\":1,\"ori\":\"GOODS3\",\"count\":5,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            rec.economy.vipBuyedGifts = "|1|";
            store.save(rec);
            int bagBefore = rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue();
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 27, buyBody(1)));

            List<GamePacket> out = drain(s.channel());
            Assertions.assertFalse(has(out, MsgIds.S2C_VIP_GIFT_BUY_RET), "重复购买不能回 2610");
            GamePacket bought = firstOf(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
            Assertions.assertNotNull(bought, "重复购买必须重推 2609");
            Assertions.assertTrue(Pb.read(bought.body).getString(1).contains("|1|"), "2609 必须仍含 \"|1|\"");
            Assertions.assertEquals(100, rec.diamond, "重复购买不能扣钻");
            Assertions.assertEquals(bagBefore, rec.bag.getOrDefault("GOODS3", Integer.valueOf(0)).intValue(),
                    "重复购买不能再发道具");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /** VIP 不足（档位要 VIP15、玩家 chargedDiamond=0 → VIP0）：不回 2610，只重推 2609，钻石不动。 */
    @Test
    public void vipGiftBuyDeniesLowVip() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-lowvip.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":3,\"vipLevel\":15,\"ori\":\"GOODS3\",\"count\":1,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 0);
            Assertions.assertEquals(0, economy.vipLevel(0), "累计 0 钻 = VIP0（本测试前提）");
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 28, buyBody(3)));

            List<GamePacket> out = drain(s.channel());
            Assertions.assertFalse(has(out, MsgIds.S2C_VIP_GIFT_BUY_RET), "VIP 不足不能回 2610");
            Assertions.assertTrue(has(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET), "VIP 不足必须重推 2609");
            Assertions.assertEquals(100, rec.diamond, "VIP 不足不能扣钻");
            Assertions.assertEquals("", rec.economy.vipBuyedGifts, "VIP 不足不能写已购串");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /**
     * 钻石不足（needRmb=1000 > diamond=100）：不回 2610，只重推 2609 + 106 钻石刷新，钻石仍 100。
     *
     * <p>推 106 是给客户端复位用的：{@code VipBuyItem.OnBuyClicked} 本地只拦「余额不够」弹充值框，
     * 服务端这道是防绕过；回 2609 + 钻石新值能让面板回到真实状态（钻石没变，但仍要刷新）。
     */
    @Test
    public void vipGiftBuyDeniesNotEnoughDiamond() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-nodiamond.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":4,\"vipLevel\":1,\"ori\":\"GOODS3\",\"count\":1,\"needRmb\":1000}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 29, buyBody(4)));

            List<GamePacket> out = drain(s.channel());
            Assertions.assertFalse(has(out, MsgIds.S2C_VIP_GIFT_BUY_RET), "钻石不足不能回 2610");
            Assertions.assertTrue(has(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET), "钻石不足必须重推 2609");
            Assertions.assertEquals(100, rec.diamond, "钻石不足不能扣钻，必须仍 100");
            Assertions.assertEquals(100, attriValue(out, 4),
                    "钻石不足必须推 106（attri type4）当前钻石 100（刷新面板）");
            Assertions.assertEquals("", rec.economy.vipBuyedGifts, "钻石不足不能写已购串");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /**
     * 装备礼包：ori=EQ0044 必须发**装备实例**（S2C 1406）而不是背包道具，已购串含 "|2|"。
     *
     * <p>{@code ProgressService.grantReward} 按 {@code cultivate.equip(ori)} 分流；装备进
     * {@code rec.equipments} 并逐件推 1406（客户端靠它入包，进 bag 会导致登录/结算 UI 异常）。
     */
    @Test
    public void vipGiftBuyGrantsEquipmentInstance() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-equip.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":2,\"vipLevel\":1,\"ori\":\"EQ0044\",\"count\":1,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            Assertions.assertEquals(1, economy.vipLevel(60),
                    "VipCfg：累计 60 钻 = VIP1（档位 vipLevel=1 才可买，本测试前提）");
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            int eqBefore = rec.equipments.size();
            int oriBefore = countEquip(rec, "EQ0044");
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 30, buyBody(2)));

            List<GamePacket> out = drain(s.channel());
            GamePacket buy = firstOf(out, MsgIds.S2C_VIP_GIFT_BUY_RET);
            Assertions.assertNotNull(buy, "装备礼包购买成功必须回 2610");
            Assertions.assertEquals(2, Pb.read(buy.body).getInt(1, -1), "2610 f1 = buyID = 2");
            Assertions.assertTrue(has(out, MsgIds.S2C_ADD_EQUIP), "装备必须推 1406 入包");
            Assertions.assertEquals(eqBefore + 1, rec.equipments.size(), "装备实例数量必须 +1");
            PlayerRecord.Equipment eq = rec.equipments.get(rec.equipments.size() - 1);
            Assertions.assertEquals("EQ0044", eq.ori, "新装备实例的 ori 必须是档位的 EQ0044");
            Assertions.assertEquals(oriBefore + 1, countEquip(rec, "EQ0044"), "EQ0044 实例数必须 +1");
            Assertions.assertFalse(rec.bag.containsKey("EQ0044"),
                    "装备不能进背包（必须走 grantEquip 实例列表）");
            GamePacket bought = firstOf(out, MsgIds.S2C_VIP_GIFT_BOUGHT_RET);
            Assertions.assertNotNull(bought, "购买成功必须重推 2609");
            Assertions.assertTrue(Pb.read(bought.body).getString(1).contains("|2|"),
                    "2609 f1 必须含 \"|2|\"");
            Assertions.assertEquals(70, rec.diamond, "钻石必须正好扣 needRmb=30（100-30）");
            Assertions.assertEquals(70, attriValue(out, 4), "必须推 106（attri type4）新值 70");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /**
     * 买 VIP 礼包必须计入「当日消耗钻石」并推 EAttriType=18。
     *
     * <p>客户端：当日消耗返利面板按 {@code mCurDayCostZuanShi} 判定，运行时只认 attri 18
     * （{@code MainPlayer.cs:940-942} 同一个 switch；登录 detail 只给一次初值）。原来
     * {@code VipGiftService.onBuy} 扣钻后不记账 → 买礼包花的钻不计入当日消耗。
     *
     * <p>断言增量（存档在 target/test-data 下跨次运行会残留，{@link #freshPlayer} 不重置该字段）：
     * 成功购买后 {@code curDayCostZuanShi += needRmb}、推 attri18 = 原值 + needRmb 并落档；
     * 被拒（重复购买）既不记账也不推 18。
     */
    @Test
    public void vipGiftBuyAddsTodayCostAndPushesAttri18() throws IOException {
        Path tmp = writeCfg("vip-gift-flow-todaycost.json", "{\"enabled\":true,\"items\":["
                + "{\"id\":1,\"vipLevel\":1,\"ori\":\"GOODS3\",\"count\":5,\"needRmb\":30}]}");
        try {
            cfg.reload(tmp);
            PlayerRecord rec = freshPlayer(ACCOUNT, 100, 60);
            int costBefore = rec.economy.curDayCostZuanShi;
            GameSession s = bind(rec);

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 31, buyBody(1)));
            List<GamePacket> out = drain(s.channel());

            Assertions.assertNotNull(firstOf(out, MsgIds.S2C_VIP_GIFT_BUY_RET), "购买成功必须回 2610");
            Assertions.assertEquals(costBefore + 30, rec.economy.curDayCostZuanShi,
                    "买礼包扣的 needRmb=30 必须计入当日消耗钻石");
            Assertions.assertEquals(costBefore + 30, attriValue(out, 18),
                    "必须推 attri18（当日消耗钻石）新值 = 原值 + 30");
            Assertions.assertEquals(70, attriValue(out, 4), "同一批还要推钻石新值 70（100-30）");
            PlayerRecord disk = reloadFromDisk(ACCOUNT);
            Assertions.assertEquals(costBefore + 30, disk.economy.curDayCostZuanShi,
                    "当日消耗必须落档（addTodayCost 里 store.save）");

            dispatcher.dispatch(s, packet(MsgIds.C2S_VIP_GIFT_BUY, 32, buyBody(1)));
            List<GamePacket> denied = drain(s.channel());
            Assertions.assertFalse(has(denied, MsgIds.S2C_VIP_GIFT_BUY_RET), "已购过必须被拒（不回 2610）");
            Assertions.assertEquals(costBefore + 30, rec.economy.curDayCostZuanShi, "被拒不能重复记账");
            Assertions.assertEquals(Integer.MIN_VALUE, attriValue(denied, 18),
                    "被拒不推 attri18（拒绝分支只重推 2609）");
        } finally {
            cfg.reload(shippedCfg());
        }
    }

    /* ---------------- 工具 ---------------- */

    /** 仓库里的 vip-gift.json（还原目标）。 */
    private Path shippedCfg() {
        return Paths.get(props.getTablesDir()).resolve(SHIPPED);
    }

    /** 写一份临时档位 json 到 target/test-data 下（与其他测试同根，mvn clean 即清）。 */
    private static Path writeCfg(String name, String json) throws IOException {
        Path tmp = Paths.get("target/test-data").resolve(name);
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        return tmp;
    }

    /** 取存档并重置成「全新账号」：钻石/累计充值/已购串/装备全部重设。 */
    private PlayerRecord freshPlayer(String account, int diamond, int chargedDiamond) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), 1, 1, account);
        }
        rec.ensureCollections();
        rec.diamond = diamond;
        rec.economy.chargedDiamond = chargedDiamond;
        rec.economy.vipBuyedGifts = "";
        rec.equipments.clear();
        store.save(rec);
        return rec;
    }

    /** 从磁盘重新读该账号的存档 json（证明 store.save 真的落盘，而不只是内存同一实例）。 */
    private PlayerRecord reloadFromDisk(String account) throws IOException {
        Path file = Paths.get(props.getDataDir()).toAbsolutePath().resolve(account + ".json");
        Assertions.assertTrue(Files.isRegularFile(file), "存档必须落盘：" + file);
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(file.toFile(), PlayerRecord.class);
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

    private static int countEquip(PlayerRecord rec, String ori) {
        int n = 0;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (ori.equals(eq.ori)) {
                n++;
            }
        }
        return n;
    }

    /** 断言 2617 的一条档位：f1..f5 与 json 完全一致且**五个字段都写出**。 */
    private static void assertItem(byte[] item, int id, int vipLevel, String ori, int count, int needRmb) {
        Pb.Fields f = Pb.read(item);
        Assertions.assertEquals(id, f.getInt(1, -1), "2617 f1 = mID");
        Assertions.assertEquals(vipLevel, f.getInt(2, -1), "2617 f2 = mVipLevel");
        Assertions.assertEquals(ori, f.getString(3), "2617 f3 = mOriName");
        Assertions.assertEquals(count, f.getInt(4, -1), "2617 f4 = mCount");
        Assertions.assertEquals(needRmb, f.getInt(5, -1), "2617 f5 = mNeedRMB");
        for (int i = 1; i <= 5; i++) {
            Assertions.assertTrue(f.fieldKeys().contains(Integer.valueOf(i)),
                    "2617 f" + i + " 必须写出（真服不省略 message 字段默认值，客户端会读 null）");
        }
    }

    private static GamePacket packet(int msgId, int serial, byte[] body) {
        return new GamePacket(msgId, serial, body);
    }

    /** C2S 2311 包体：{@code CCMsgBuyVIPGift.vipGiftID} = field 1 int32。 */
    private static byte[] buyBody(int id) {
        return Pb.write(o -> Pb.int32(o, 1, id));
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
