package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
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
 * 主线（普通/精英）全流程门槛回归测试 —— 2026-10「主线协议按真口径补齐」。
 *
 * <p>假服以前在 C2S 301/310/303/333 上几乎不校验，全部依赖客户端自觉（客户端确实都拦了，
 * 见各断言文案里的行号）。这里钉住服务端补齐后的口径，改动 {@code DungeonService} 副本域
 * 或 {@code GameTables}/{@code EconomyTables} 的表解析时必须一起看。</p>
 *
 * <p>表值（UTF-8，TAB）：{@code RegionList} col6/col7 普通 8/精英 16；{@code GlobalSetup_CH}
 * token66=3010（精英总开关）、token77-80 普通 10、token81-84 精英 3；{@code VipCfg}
 * col5 十连扫荡（VIP2 起 1）、col7 普通副本重置（VIP0=0/VIP1=1/VIP2=2）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-maingates",
        "sao.world-dir=target/test-data/world-maingates",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class MainFbGateTest {
    private static final String ACCOUNT = "test-maingates";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private DungeonService dungeon;
    @Autowired
    private ProgressService progress;
    @Autowired
    private SessionHub hub;

    private EmbeddedChannel channel;

    @AfterEach
    public void cleanup() {
        if (channel != null) {
            channel.finishAndReleaseAll();
            channel = null;
        }
    }

    /** 进本门槛：解锁（GuanKaOpen）+ 体力（RegionList col6/col7，不是配置里的 dungeon-vp-cost=6）。 */
    @Test
    public void enterRefusesLockedRegionAndInsufficientStamina() {
        PlayerRecord rec = freshPlayer();
        rec.progress.lastNormalStage = 1002;
        GameSession session = bind(rec);

        // 1-2 通关后客户端只开放到 1-3（ChapterGuanKaCommonInfo.GetPuTongLastOpenChapterGuanKaID）
        enter(session, 1005, 1);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_FB_INFO), "未解锁的关不能发 401");

        rec.stamina = 3;
        enter(session, 1003, 1);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_FB_INFO),
                "体力 3 < 8 不能发 401（客户端 NormalFBDescribeSystem.CanPlayFB 会先弹买体力）");

        rec.stamina = 20;
        enter(session, 1003, 1);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_FB_INFO), "解锁且体力够必须放行");
        Assertions.assertEquals(12, rec.stamina, "普通本扣 RegionList col6=8，不是 application.yml 的 6");
    }

    /** 精英门槛：总开关 = 普通进度 ≥ GlobalSetup_CH token66（3010），体力走 col7（16）。 */
    @Test
    public void eliteEnterNeedsNormalProgressAndHardEnergy() {
        PlayerRecord rec = freshPlayer();
        rec.stamina = 200;
        rec.progress.lastNormalStage = 3009;
        GameSession session = bind(rec);

        enter(session, 1003, 2);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_FB_INFO),
                "普通进度 3009 < 3010 时精英全锁（GetJinYingLastOpenChapterGuanKaID 返回 -1）");

        rec.progress.lastNormalStage = 3010;
        rec.stamina = 200;
        enter(session, 1003, 2);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_FB_INFO), "3-10 通关后精英 1-3 应放行");
        Assertions.assertEquals(184, rec.stamina, "精英本扣 RegionList col7=16（普通 8 的两倍）");
    }

    /** 扫荡门槛：三星 + 受限关 + 剩余次数 + 十连 VIP；次数由服务端按 min(剩余, 体力/单价) 自算。 */
    @Test
    public void sweepNeedsStarsAndTenXNeedsVip() {
        PlayerRecord rec = freshPlayer();
        rec.progress.lastNormalStage = 1003;
        rec.stamina = 200;
        rec.economy.chargedDiamond = 0;
        GameSession session = bind(rec);

        rec.progress.stageStars.put(PlayerRecord.stageKey(1003, 1), Integer.valueOf(2));
        sweep(session, 1003, 1, true);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_SAO_DANG_RET),
                "2 星不能扫荡（CheckSaoDang :754-755 Stars < 3，单次/十连同门）");

        rec.progress.stageStars.put(PlayerRecord.stageKey(1003, 1), Integer.valueOf(3));
        sweep(session, 1003, 1, true);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_SAO_DANG_RET), "三星+次数够的单次扫荡必须回 410");
        Assertions.assertEquals(192, rec.stamina, "单次扫荡扣 8 体力");
        Assertions.assertEquals(9, playLeft(rec, 1003, 1), "单次扫荡消耗 1 次");

        rec.stamina = 200;
        sweep(session, 1003, 1, false);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_SAO_DANG_RET),
                "VIP0 十连扫荡必须拒（VipCfg「开启十连扫荡」VIP2 起为 1）");
        Assertions.assertEquals(9, playLeft(rec, 1003, 1), "被拒的十连不能扣次数");

        rec.economy.chargedDiamond = 300;
        rec.stamina = 200;
        sweep(session, 1003, 1, false);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_SAO_DANG_RET), "VIP2 十连扫荡必须回 410");
        Assertions.assertEquals(0, playLeft(rec, 1003, 1),
                "剩余 9 次应一次扫完（times = min(9, 200/8=25) = 9）");
        Assertions.assertEquals(200 - 9 * 8, rec.stamina, "扫 N 次扣 N×单价");
    }

    /** 章节宝箱门槛：普通 10/20/30 星、精英 4/8/12 星（MainPlayer.GetChapterBaoXiangState）。 */
    @Test
    public void chapterChestNeedsChapterStars() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        chest(session, 1, 1);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_CHAPTER_CHEST), "0 星不能领普通第 1 箱（10 星）");

        for (int stage = 1; stage <= 10; stage++) {
            rec.progress.stageStars.put(PlayerRecord.stageKey(1000 + stage, 1), Integer.valueOf(1));
        }
        chest(session, 1, 1);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_CHAPTER_CHEST), "合计 10 星应能领普通第 1 箱");

        chest(session, 1, 2);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_CHAPTER_CHEST), "10 星不能领第 2 箱（20 星）");
    }

    /** 买次数门槛：VipCfg 普通/精英副本重置列（VIP0=0、VIP1=1、VIP2=2）+ 只在剩余 0 次时能买。 */
    @Test
    public void buyResetRespectsVipMaxCount() {
        PlayerRecord rec = freshPlayer();
        rec.progress.lastNormalStage = 1003;
        rec.diamond = 500;
        rec.economy.chargedDiamond = 0;
        GameSession session = bind(rec);
        int fbId = 1003 * 10 + 1;
        setPlayLeft(rec, 1003, 1, 0);

        buyTimes(session, fbId);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_UPDATE_FB_PLAY_TIME),
                "VIP0 当天不能买次数（VipCfg 普通副本重置 = 0）");
        Assertions.assertEquals(500, rec.diamond, "被拒的购买不能扣钻");

        rec.economy.chargedDiamond = 60;
        setPlayLeft(rec, 1003, 1, 0);
        buyTimes(session, fbId);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_UPDATE_FB_PLAY_TIME), "VIP1 当天第 1 次购买应放行");
        Assertions.assertEquals(1, buyTimes(rec, 1003, 1), "buyTimes 记 1 次");
        Assertions.assertEquals(10, playLeft(rec, 1003, 1), "买次数后剩余次数灌满该关上限 10");
        Assertions.assertTrue(rec.diamond < 500, "购买要扣钻");

        setPlayLeft(rec, 1003, 1, 0);
        buyTimes(session, fbId);
        Assertions.assertFalse(has(drain(), MsgIds.S2C_UPDATE_FB_PLAY_TIME),
                "超过 VipCfg 重置上限（VIP1=1）必须拒");
    }

    /* ---------------- 工具 ---------------- */

    private PlayerRecord freshPlayer() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        progress.ensureDaily(rec);
        rec.progress.stageStars.clear();
        rec.progress.stagePlayLimits.clear();
        rec.progress.lastNormalStage = 0;
        rec.progress.lastHardStage = 0;
        rec.pendingFbReward = null;
        rec.lastResourceRegionType = 0;
        rec.currentRegionId = 0;
        rec.bag.clear();
        rec.economy.chapterChests.clear();
        rec.stamina = 200;
        rec.diamond = 0;
        rec.economy.chargedDiamond = 0;
        // 高等级：扫荡/通关给的主玩家经验不能触发升级回体力（ProgressService.java:74
        // rec.stamina += tables.vpOnLevelUp），否则体力断言会被 +20 干扰。
        rec.level = 80;
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

    private void enter(GameSession session, int region, int diff) {
        dungeon.onEnterFb(session, new GamePacket(MsgIds.C2S_ENTER_FB, 11,
                Pb.write(o -> {
                    Pb.int32(o, 1, region);
                    Pb.int32(o, 2, diff);
                })));
    }

    private void sweep(GameSession session, int region, int diff, boolean once) {
        dungeon.onSaoDang(session, new GamePacket(MsgIds.C2S_SAO_DANG, 12,
                Pb.write(o -> {
                    Pb.int32(o, 1, region);
                    Pb.int32(o, 2, diff);
                    Pb.int32(o, 3, once ? 1 : 0);
                })));
    }

    private void chest(GameSession session, int chapter, int box) {
        dungeon.onChapterChest(session, new GamePacket(MsgIds.C2S_CHAPTER_CHEST, 13,
                Pb.write(o -> {
                    Pb.int32(o, 1, chapter);
                    Pb.int32(o, 2, box);
                })));
    }

    private void buyTimes(GameSession session, int fbId) {
        dungeon.onBuyFbTimes(session, new GamePacket(MsgIds.C2S_BUY_FB_TIME, 14,
                Pb.write(o -> Pb.int32(o, 1, fbId))));
    }

    private PlayerRecord.StagePlayLimit limit(PlayerRecord rec, int region, int diff) {
        return dump.ensureMainFbPlayLimit(rec, PlayerRecord.stageKey(region, diff), region, diff);
    }

    private void setPlayLeft(PlayerRecord rec, int region, int diff, int left) {
        limit(rec, region, diff).playLeft = left;
    }

    private int playLeft(PlayerRecord rec, int region, int diff) {
        return limit(rec, region, diff).playLeft;
    }

    private int buyTimes(PlayerRecord rec, int region, int diff) {
        return limit(rec, region, diff).buyTimes;
    }

    private List<GamePacket> drain() {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = channel.readOutbound()) != null && guard++ < 128) {
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
}
