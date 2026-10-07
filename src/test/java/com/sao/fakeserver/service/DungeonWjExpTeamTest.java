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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通关/扫荡的「武将经验只发上阵 5 人」回归测试（用户 m01484 报障）。
 *
 * <p>报障原话：「通关应该只给队伍里的五个武将发经验，而不是全表的」。客户端结算面板
 * （{@code NormalFBGoodsGrant.cs:60-70}）把 S2C 401 的 {@code WuJiangExp} 整份喂给上阵的 5 人，
 * 升级回调也只改 UI 文本、不回写；服务端原先对 {@code rec.heroes} 全体调
 * {@code ProgressService.addWjExp} ⇒ 没上阵的武将也涨经验，与面板显示对不上。
 *
 * <p>三个发放点都要按「本场战斗的上阵阵容」过滤（{@code DungeonService.deployedHeroes}）：
 * <ul>
 *   <li>主线通关 {@code applyWin} → 客户端 {@code EmBattleSystem} mType=0，PVE 阵容
 *       （{@code UIModulesManager.cs:4415 PVEEnBattle}）。</li>
 *   <li>主线扫荡 {@code sweep} → 同一套 PVE 阵容。</li>
 *   <li>资源本通关 {@code applyResourceWin} → 该玩法的阵容类型（镜像 5 / 不可触 6 / 致命 8 /
 *       圣诞 9 / 镰刀 10，与客户端 {@code eFormationType} 同号）。</li>
 * </ul>
 * 未上阵的武将在 {@code wjLeveled} 里保留 FALSE —— {@code pushWjProgress} 是按
 * {@code rec.heroes} 下标循环的，列表长度必须一致，否则推错人。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-wjexp-team",
        "sao.world-dir=target/test-data/world-wjexp-team",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class DungeonWjExpTeamTest {
    private static final String ACCOUNT = "test-wjexp-team";
    /** 3-3 普通：章内第 3 关 ⇒ 受限关（能扫荡），且表里有武将经验列。 */
    private static final int REGION = 1003;
    private static final int DIFF = 1;
    private static final int HERO_COUNT = 8;

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
    public void mainFbWinAwardsWuJiangExpOnlyToPveTeam() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        Map<String, Long> before = snapshot(rec);

        dungeon.onEnterFb(session, new GamePacket(MsgIds.C2S_ENTER_FB, 11,
                Pb.write(o -> {
                    Pb.int32(o, 1, REGION);
                    Pb.int32(o, 2, DIFF);
                })));
        drain();
        dungeon.onResultFb(session, new GamePacket(MsgIds.C2S_RESULT_FB, 12,
                Pb.write(o -> {
                    Pb.int32(o, 1, 1);
                    Pb.int32(o, 2, 3);
                })));
        drain();

        assertOnlyTeamGained(rec, before, new int[]{1, 2, 3, 4, 5}, new int[]{6, 7, 8},
                "主线通关");
    }

    @Test
    public void mainFbSweepAwardsWuJiangExpOnlyToPveTeam() {
        PlayerRecord rec = freshPlayer();
        rec.progress.stageStars.put(PlayerRecord.stageKey(REGION, DIFF), Integer.valueOf(3));
        dump.ensureMainFbPlayLimit(rec, PlayerRecord.stageKey(REGION, DIFF), REGION, DIFF);
        store.save(rec);
        GameSession session = bind(rec);
        Map<String, Long> before = snapshot(rec);

        dungeon.onSaoDang(session, new GamePacket(MsgIds.C2S_SAO_DANG, 13,
                Pb.write(o -> {
                    Pb.int32(o, 1, REGION);
                    Pb.int32(o, 2, DIFF);
                    Pb.bool(o, 3, true);
                })));
        List<GamePacket> pkts = drain();
        Assertions.assertTrue(has(pkts, MsgIds.S2C_SAO_DANG_RET),
                "三星受限关单次扫荡必须回 410（本用例前提：扫荡真的执行了）");

        assertOnlyTeamGained(rec, before, new int[]{1, 2, 3, 4, 5}, new int[]{6, 7, 8},
                "主线扫荡");
    }

    @Test
    public void resourceWinAwardsWuJiangExpOnlyToThatModeTeam() {
        PlayerRecord rec = freshPlayer();
        // 镜像本：客户端用 mCompleteMember.MirrorEnBattle（eFormationType 5），不是 PVE 阵容。
        rec.lastResourceRegionType = DungeonService.RT_MIRROR;
        rec.lastResourceLevel = 1;
        // 镜像的 RegionDropList 行（21–27）金/经验列全是 0，直接打本发不出武将经验；
        // 这里手工塞一份 pending（region/difficult 必须等于 firstRegionOfType(5)=20 与难度 1，
        // applyResourceWin 走 takePendingFb 时才会取用）来钉「只发上阵 5 人」的过滤逻辑。
        PlayerRecord.PendingFbReward pending = new PlayerRecord.PendingFbReward();
        pending.region = tables.firstRegionOfType(DungeonService.RT_MIRROR);
        pending.difficult = 1;
        pending.wjExp = 60;
        rec.pendingFbReward = pending;
        store.save(rec);
        GameSession session = bind(rec);
        Map<String, Long> before = snapshot(rec);

        dungeon.onResultFb(session, new GamePacket(MsgIds.C2S_RESULT_FB, 14,
                Pb.write(o -> {
                    Pb.int32(o, 1, 1);
                    Pb.int32(o, 2, 3);
                })));
        drain();

        assertOnlyTeamGained(rec, before, new int[]{6, 7, 8}, new int[]{1, 2, 3, 4, 5},
                "镜像本通关");
    }

    /* ---------------- 工具 ---------------- */

    private void assertOnlyTeamGained(PlayerRecord rec, Map<String, Long> before,
                                      int[] team, int[] idle, String what) {
        for (int idx : team) {
            String id = heroId(idx);
            Assertions.assertTrue(progressOf(rec, id) > before.get(id).longValue(),
                    what + "：上阵武将 " + id + " 必须拿到武将经验（否则面板 EXP+ 是假的）");
        }
        for (int idx : idle) {
            String id = heroId(idx);
            Assertions.assertEquals(before.get(id).longValue(), progressOf(rec, id),
                    what + "：没上阵的武将 " + id + " 不该拿通关武将经验（用户 m01484 报障）");
        }
    }

    private PlayerRecord freshPlayer() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        rec.progress.stageStars.clear();
        rec.progress.stagePlayLimits.clear();
        rec.pendingFbReward = null;
        rec.lastResourceRegionType = 0;
        rec.currentRegionId = 0;
        rec.bag.clear();
        rec.resourceFb.clear();
        rec.progress.lastNormalStage = REGION - 1;
        rec.progress.lastHardStage = 0;
        rec.stamina = 200;
        // 武将等级上限 = min(表上限, 账号等级)（ProgressService.addWjExp:132-135）；抬到 80
        // 让经验只落在 exp 上、不触发升级（升级会推战斗力包，与本用例无关）。
        rec.level = 80;
        rec.heroes.clear();
        for (int i = 1; i <= HERO_COUNT; i++) {
            PlayerRecord.Hero hero = new PlayerRecord.Hero();
            hero.heroIndex = i;
            hero.id = heroId(i);
            hero.level = 80;
            hero.exp = 0;
            rec.heroes.add(hero);
        }
        rec.setFormationSlots(PlayerRecord.FORMATION_PVE, team(1, 2, 3, 4, 5));
        rec.setFormationSlots(DungeonService.RT_MIRROR, team(6, 7, 8));
        DungeonService.ensureAllResourceFbSlots(rec);
        store.save(rec);
        return rec;
    }

    private static String heroId(int heroIndex) {
        return PlayerDumpService.guidOf(ACCOUNT, heroIndex);
    }

    private static List<String> team(int... heroIndexes) {
        List<String> out = new ArrayList<>();
        for (int idx : heroIndexes) {
            out.add(heroId(idx));
        }
        return out;
    }

    /** 武将成长度：等级×1e6 + 经验，升级/涨经验都会变大（不用管升级清零）。 */
    private static Map<String, Long> snapshot(PlayerRecord rec) {
        Map<String, Long> out = new HashMap<>();
        for (PlayerRecord.Hero hero : rec.heroes) {
            out.put(hero.id, Long.valueOf(progressOf(rec, hero.id)));
        }
        return out;
    }

    private static long progressOf(PlayerRecord rec, String heroId) {
        PlayerRecord.Hero hero = rec.findHero(heroId);
        Assertions.assertNotNull(hero, "武将档里必须有 " + heroId);
        return hero.level * 1000000L + hero.exp;
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
        while ((p = channel.readOutbound()) != null && guard++ < 256) {
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
