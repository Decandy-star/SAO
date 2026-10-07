package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.GameTables;
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
 * 主线受限关「首次通关」这一段协议的回归测试（2026-10 报障修复）。
 *
 * <p>钉的是三件客户端行为，改动 {@code DungeonService} 副本域时必须一起看：
 * <ol>
 *   <li><b>401 的 dropGoods 不能含「首通必掉」</b> —— 客户端在未通关时会按 {@code RegionDropList}
 *       表的 CertainDropGoods1..3 / CertainDropYinPoCount / CertainDropWNSPCount 自己再加一遍
 *       （{@code NormalFBGoodsGrant.cs:174-268} 结算面板、{@code DropGoodsManager.cs:215-236} 局内掉落池），
 *       服务端也发同一份就会翻倍，玩家看到「apk 掉落 ≠ 背包实收」。</li>
 *   <li><b>S2C 1002 必须晚于 1001</b> —— 客户端收到 1001 才在 {@code BattleController.ShowVictoryUI}
 *       （{@code BattleController.cs:1390-1398}）里 {@code new FBStarInfo(...)} 建出该关条目，而
 *       {@code PlayGameState.cs:5773-5782} 对 {@code mFBStarList} 里没有的 fbID 直接 continue。
 *       先发 1002 ⇒ 首次通关那次更新被丢弃、条目随后建成默认 0 ⇒ 界面「挑战次数被置 0」。</li>
 *   <li><b>资源本/镜像扫荡不能只看 VipCfg 第 46 列</b> —— 该列现网整列 0；客户端按登录 detail
 *       field 18 requireType 选门（{@code TiaoZhanNanDu.cs:582-616}），服务端必须同口径，
 *       否则客户端放行、服务端静默只回 450。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-dungeon-firstclear",
        "sao.world-dir=target/test-data/world-dungeon-firstclear",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class DungeonFirstClearFlowTest {
    private static final String ACCOUNT = "test-dungeon-firstclear";
    /** 3-3 普通：章内第 3 关 ⇒ 受限关（PlayerDumpService.needMainFbPlayLimit）。 */
    private static final int REGION = 1003;
    private static final int DIFF = 1;

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private DungeonService dungeon;
    @Autowired
    private GameTables tables;
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

    @Test
    public void firstClearKeepsFirstDropOutOf401AndPushes1002After1001() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        // ---- 进本：C2S 301 {region_res_id, level} ----
        dungeon.onEnterFb(session, new GamePacket(MsgIds.C2S_ENTER_FB, 11,
                Pb.write(o -> {
                    Pb.int32(o, 1, REGION);
                    Pb.int32(o, 2, DIFF);
                })));
        List<GamePacket> enterPkts = drain();
        Assertions.assertTrue(has(enterPkts, MsgIds.S2C_FB_INFO),
                "进本必须回 401：客户端 Region.FbInfo 为空时结算面板不开、掉落池为空");
        Assertions.assertTrue(has(enterPkts, MsgIds.S2C_CHANGE_REGION_RET), "进本必须回 107 切场景");

        GameTables.DropRow row = tables.drop(REGION, DIFF);
        Assertions.assertNotNull(row, "tables/RegionDropList.txt 必须有 1003/1 行");
        Assertions.assertFalse(row.certain.isEmpty(),
                "本测试前提：3-3 普通有「首通必掉」（表里 8-13 列）；没有的话这条协议就没意义");

        List<String> wire401 = new ArrayList<>();
        for (byte[] one : Pb.read(bodyOf(enterPkts, MsgIds.S2C_FB_INFO)).getBytesList(7)) {
            wire401.add(Pb.read(one).getString(1));
        }
        for (GameTables.GoodsDrop c : row.certain) {
            Assertions.assertFalse(wire401.contains(c.ori),
                    "401 dropGoods 不能带首通必掉 " + c.ori
                            + "：客户端 NormalFBGoodsGrant.cs:174-268 会按本地表再加一遍 ⇒ 面板/掉落池翻倍");
        }

        // ---- 通关：C2S 302 {result=1, star=3} ----
        dungeon.onResultFb(session, new GamePacket(MsgIds.C2S_RESULT_FB, 12,
                Pb.write(o -> {
                    Pb.int32(o, 1, 1);
                    Pb.int32(o, 2, 3);
                })));
        List<GamePacket> pkts = drain();
        int idx1001 = indexOf(pkts, MsgIds.S2C_RESULT_FB_RET);
        int idx1002 = indexOf(pkts, MsgIds.S2C_UPDATE_FB_PLAY_TIME);
        Assertions.assertTrue(idx1001 >= 0, "结算必须回 1001（客户端等 EN_RESULTFB_RET_SUCCESS）");
        Assertions.assertTrue(idx1002 >= 0, "受限关通关必须推 1002 更新剩余次数");
        Assertions.assertTrue(idx1001 < idx1002,
                "1002 必须在 1001 之后：客户端在 1001 的 ShowVictoryUI 里才建 FBStarInfo 条目，"
                        + "先发会因 mFBStarList 无该 fbID 被 PlayGameState.cs:5773-5782 丢弃 ⇒ 显示 0 次");

        // ---- 首通必掉必须真进背包（只是不下发到 401） ----
        for (GameTables.GoodsDrop c : row.certain) {
            Assertions.assertTrue(rec.bag.getOrDefault(c.ori, 0) >= c.count,
                    "首通必掉 " + c.ori + "×" + c.count + " 必须真发进背包");
        }

        // ---- 次数：普通受限关上限 10，通关 1 次后剩 9 ----
        PlayerRecord.StagePlayLimit lim =
                rec.progress.stagePlayLimits.get(PlayerRecord.stageKey(REGION, DIFF));
        Assertions.assertNotNull(lim, "通关后必须落 stagePlayLimits");
        Assertions.assertEquals(dump.mainFbPlayMax(DIFF, REGION) - 1, lim.playLeft,
                "首次通关后剩余次数应是 9，不是 0");

        byte[] updBody = bodyOf(pkts, MsgIds.S2C_UPDATE_FB_PLAY_TIME);
        List<byte[]> infos = Pb.read(updBody).getBytesList(1);
        Assertions.assertEquals(1, infos.size(), "1002 一次一批，本关只发一条");
        Pb.Fields one = Pb.read(infos.get(0));
        Assertions.assertEquals(REGION * 10 + DIFF, one.getInt(1, 0),
                "1002 field1 fbID = 关卡ID×10+难度，客户端按它索引 mFBStarList");
        Assertions.assertEquals(9, one.getInt(2, 0), "1002 field2 剩余次数应为 9");
    }

    @Test
    public void detailSendsResourceFbSaoDangRequireType() {
        PlayerRecord rec = freshPlayer();
        Pb.Fields detail = Pb.read(Pb.read(dump.mainPlayer(rec)).getBytes(4));
        Assertions.assertEquals(PlayerDumpService.RESOURCE_FB_SAO_DANG_REQUIRE_TYPE,
                detail.getInt(18, -1),
                "detail 18 ResourceFBSaoDangRequireType 必须下发：缺 ⇒ 客户端按 0 只认 VipCfg 第 46 列"
                        + "（现网整列 0）⇒ 资源本/镜像扫荡永远提示「需要VIP0」");
        Assertions.assertEquals(PlayerDumpService.RESOURCE_FB_SAO_DANG_LEVEL_REQUIRE,
                detail.getInt(19, -1), "detail 19 ResourceFBSaoDangLevelRequire 也要在");
    }

    @Test
    public void mirrorSweepIsNotRefusedByAllZeroVipColumn() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        rec.level = Math.max(rec.level, 60);
        DungeonService.ensureAllResourceFbSlots(rec);
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(DungeonService.RT_MIRROR));
        fb.stars.put(Integer.valueOf(1), Integer.valueOf(3));
        fb.playTime = 0;
        fb.lastPlay = "";
        store.save(rec);

        dungeon.onSaoDangResource(session, new GamePacket(MsgIds.C2S_SAO_DANG_RESOURCE, 21,
                Pb.write(o -> {
                    Pb.int32(o, 1, DungeonService.RT_MIRROR);
                    Pb.int32(o, 2, 1);
                })));
        List<GamePacket> pkts = drain();
        Assertions.assertTrue(has(pkts, MsgIds.S2C_SAO_DANG_RESOURCE_RET),
                "三星星级+次数够的镜像扫荡必须真回 452；只回 450 就是被 VipCfg 第 46 列（全 0）挡了，"
                        + "而客户端 requireType=1 会放行 ⇒ 点了没反应");
    }

    /* ---------------- 工具 ---------------- */
    private PlayerRecord freshPlayer() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        // 清成「全新号」：没打过任何关、次数满、背包空。
        rec.progress.stageStars.clear();
        rec.progress.stagePlayLimits.clear();
        rec.pendingFbReward = null;
        rec.lastResourceRegionType = 0;
        rec.currentRegionId = 0;
        rec.bag.clear();
        rec.resourceFb.clear();
        // 服务端现在按客户端 GuanKaOpen 拦解锁：1-3 需要 1-2 已通关（lastNormalStage=1002），
        // 体力也要够 RegionList col6（普通 8）。
        rec.progress.lastNormalStage = REGION - 1;
        rec.progress.lastHardStage = 0;
        rec.stamina = 200;
        DungeonService.ensureAllResourceFbSlots(rec);
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
        while ((p = channel.readOutbound()) != null && guard++ < 128) {
            out.add(p);
        }
        return out;
    }

    private static boolean has(List<GamePacket> list, int msgId) {
        return indexOf(list, msgId) >= 0;
    }

    private static int indexOf(List<GamePacket> list, int msgId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).msgId == msgId) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] bodyOf(List<GamePacket> list, int msgId) {
        int i = indexOf(list, msgId);
        return i < 0 ? null : list.get(i).body;
    }
}
