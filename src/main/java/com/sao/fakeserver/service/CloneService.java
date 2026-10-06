package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.GameTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 克隆战（变体纷争）假房间：建房 / 开战 / 结算。
 * 敌方强度与奖励均由假服按难度档下发（APK 只读 5802 攻防血 + 5801 预览；无 Clone 掉落表）。
 */
@Service
public class CloneService {
    private static final Logger log = LoggerFactory.getLogger(CloneService.class);
    /** 三难度 Boss 武将 index（大厅 targetResId；仅造型/选档，强度见 {@link #bossHeroFor}）。 */
    public static final int[] BOSSES = {18, 28, 39};
    private static final int REGION = 90;
    /** 主线章节场景 ID 下限（&lt;1000 为镜像等资源本）。 */
    private static final int MAINLINE_REGION_MIN = 1000;
    /** 败场安慰：初级精炼宝石×1（不发武将碎片）。 */
    private static final String LOSE_ORI = "JLBS01";
    private static final int LOSE_CNT = 1;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final FightSyncService fightSync;
    private final GameTables tables;
    private final CultivateTables cultivate;
    private final MailService mail;

    public CloneService(PlayerStore store, PlayerDumpService dump,
                        FightSyncService fightSync, GameTables tables, CultivateTables cultivate,
                        MailService mail) {
        this.store = store;
        this.dump = dump;
        this.fightSync = fightSync;
        this.tables = tables;
        this.cultivate = cultivate;
        this.mail = mail;
    }

    /** 0 易 / 1 中 / 2 难；未知 id 当易。 */
    public static int difficultyOf(int bossResId) {
        for (int i = 0; i < BOSSES.length; i++) {
            if (BOSSES[i] == bossResId) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 敌方养成快照（5802 + beginCloneBattle 同源）。
     * 产品规则：易 lv30★2 / 中 lv45★3 / 难 lv60★4；技能同星。
     */
    public static PlayerRecord.Hero bossHeroFor(int bossResId) {
        int d = difficultyOf(bossResId);
        int idx = bossResId > 0 ? bossResId : BOSSES[0];
        int[] lv = {30, 45, 60};
        int[] stars = {2, 3, 4};
        int sk = stars[d];
        PlayerRecord.Hero h = new PlayerRecord.Hero();
        h.id = "clone-boss";
        h.heroIndex = idx;
        h.level = lv[d];
        h.stars = stars[d];
        h.skill1 = sk;
        h.skill2 = sk;
        h.skill3 = sk;
        h.skill4 = sk;
        return h;
    }

    /**
     * 胜场掉落（自主规划）：随机武将碎片（主线当前可达关卡不会掉的）+ 精炼材料。
     * 碎片数量随难度升：易×2 / 中×4 / 难×6。
     * 精炼：低档量多、高档品质升量少。
     *
     * @param fragOri 进房已 lock 的碎片 Ori；空则只发精炼（大厅预览）
     */
    public static List<GameTables.GoodsDrop> winDrops(int bossResId, String fragOri) {
        int d = difficultyOf(bossResId);
        if (d < 0) {
            d = 0;
        }
        if (d > 2) {
            d = 2;
        }
        List<GameTables.GoodsDrop> out = new ArrayList<>(3);
        if (fragOri != null && !fragOri.isEmpty()) {
            int[] fragCnt = {2, 4, 6};
            out.add(new GameTables.GoodsDrop(fragOri, fragCnt[d]));
        }
        if (d == 0) {
            out.add(new GameTables.GoodsDrop("JLBS01", 6));
            out.add(new GameTables.GoodsDrop("JLFY01", 2));
        } else if (d == 1) {
            out.add(new GameTables.GoodsDrop("JLBS02", 3));
            out.add(new GameTables.GoodsDrop("JLFY01", 3));
        } else {
            out.add(new GameTables.GoodsDrop("JLBS03", 2));
            out.add(new GameTables.GoodsDrop("JLFY02", 2));
        }
        return out;
    }

    public static List<GameTables.GoodsDrop> loseDrops() {
        return Collections.singletonList(new GameTables.GoodsDrop(LOSE_ORI, LOSE_CNT));
    }

    public void onBase(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        PlayerRecord.CloneRoom room = rec.clone;
        session.send(MsgIds.S2C_CLONE_BASE, pkt, dump.cloneBase(rec, pickRoomPartner(rec), room.inRoom, true,
                room.inRoom ? room.targetResId : 0, room.inRoom && room.allowQuickJoin, BOSSES));
    }

    public void onCreate(GameSession session, GamePacket pkt) {
        enterRoom(session, pkt, Pb.read(pkt.body).getInt(1, BOSSES[0]));
    }

    public void onQuickJoin(GameSession session, GamePacket pkt) {
        int boss = Pb.read(pkt.body).getInt(1, 0);
        if (boss <= 0) {
            boss = BOSSES[0];
        }
        enterRoom(session, pkt, boss);
    }

    public void onJoinById(GameSession session, GamePacket pkt) {
        session.send(MsgIds.S2C_CLONE_JOIN_BY_ID, pkt, dump.cloneJoinByIdRet(0));
        enterRoom(session, pkt, BOSSES[0]);
    }

    public void onLeave(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        rec.clone.inRoom = false;
        rec.clone.targetResId = 0;
        rec.clone.allowQuickJoin = true;
        rec.clone.rewardFragOri = null;
        store.save(rec);
        session.send(MsgIds.S2C_CLONE_BASE, pkt, dump.cloneBase(rec, null, false, true, 0, true, BOSSES));
    }

    public void onFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int boss = rec.clone.targetResId > 0 ? rec.clone.targetResId : BOSSES[0];
        if (rec.clone.rewardFragOri == null || rec.clone.rewardFragOri.isEmpty()) {
            rec.clone.rewardFragOri = rollFragNotInMainline(rec);
            store.save(rec);
        }
        session.send(MsgIds.S2C_CLONE_FIGHT_TEAMS, pkt, dump.cloneFightTeams(rec, boss));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(REGION));
        fightSync.clear(session);
        fightSync.beginCloneBattle(session, boss);
        rec.currentRegionId = REGION;
        store.save(rec);
        log.info("{} clone fight boss={} diff={} frag={}",
                rec.account, boss, difficultyOf(boss), rec.clone.rewardFragOri);
    }

    public void onResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int boss = rec.clone.targetResId > 0 ? rec.clone.targetResId : BOSSES[0];
        int result = Pb.read(pkt.body).getInt(1, 2);
        if (fightSync.hasBattle(session)) {
            result = fightSync.playerWon(session, rec.playerId) ? 1 : 2;
        }
        boolean win = result == 1;
        String frag = rec.clone.rewardFragOri;
        if (win && (frag == null || frag.isEmpty())) {
            frag = rollFragNotInMainline(rec);
        }
        // 真服路径：Sys_MailConfig 22=变体纷争奖励 / 23=碎片奖励；5803 仅 result，不推背包
        if (win) {
            List<PlayerRecord.MailItem> refine = new ArrayList<>();
            List<PlayerRecord.MailItem> frags = new ArrayList<>();
            for (GameTables.GoodsDrop g : winDrops(boss, frag)) {
                if (g == null || g.ori == null || g.count <= 0) {
                    continue;
                }
                PlayerRecord.MailItem it = new PlayerRecord.MailItem();
                it.ori = g.ori;
                it.count = g.count;
                if (frag != null && frag.equals(g.ori)) {
                    frags.add(it);
                } else {
                    refine.add(it);
                }
            }
            if (!refine.isEmpty()) {
                mail.sendSystemMail(rec.account, MailService.MAIL_TYPE_CLONE_REWARD,
                        null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, refine);
            }
            if (!frags.isEmpty()) {
                mail.sendSystemMail(rec.account, MailService.MAIL_TYPE_CLONE_FRAG,
                        null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, frags);
            }
        } else {
            List<PlayerRecord.MailItem> lose = new ArrayList<>();
            for (GameTables.GoodsDrop g : loseDrops()) {
                if (g == null || g.ori == null || g.count <= 0) {
                    continue;
                }
                PlayerRecord.MailItem it = new PlayerRecord.MailItem();
                it.ori = g.ori;
                it.count = g.count;
                lose.add(it);
            }
            if (!lose.isEmpty()) {
                mail.sendSystemMail(rec.account, MailService.MAIL_TYPE_CLONE_REWARD,
                        null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, lose);
            }
        }
        rec.clone.inRoom = false;
        rec.clone.targetResId = 0;
        rec.clone.rewardFragOri = null;
        store.save(rec);
        session.send(MsgIds.S2C_CLONE_RESULT, pkt, dump.cloneResult(result));
        log.info("{} clone result={} boss={} frag={} viaMail",
                rec.account, result, boss, frag);
    }

    public void onChangeWj(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int resId = Pb.read(pkt.body).getInt(1, 0);
        if (resId > 0) {
            PlayerRecord.Hero picked = rec.findHeroByIndex(resId);
            if (picked != null && picked.id != null) {
                List<String> slots = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_CLONE_ATK));
                if (slots.isEmpty()) {
                    slots.add(picked.id);
                } else {
                    slots.set(0, picked.id);
                }
                rec.setFormationSlots(PlayerRecord.FORMATION_CLONE_ATK, slots);
                store.save(rec);
            }
        }
        session.send(MsgIds.S2C_CLONE_ROOM_TEAM, pkt, dump.cloneRoomTeam(rec));
        session.send(MsgIds.S2C_CLONE_BASE, pkt, dump.cloneBase(rec, pickRoomPartner(rec), true, true,
                rec.clone.targetResId, rec.clone.allowQuickJoin, BOSSES));
    }

    public void onChangeQuickJoin(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        boolean allow = Pb.read(pkt.body).getBool(1);
        rec.clone.allowQuickJoin = allow;
        store.save(rec);
        session.send(MsgIds.S2C_CLONE_QUICK_JOIN, pkt, dump.cloneQuickJoinAllow(allow));
    }

    /** APK 邀请钮已先 toast；假房不转发，空处理即可（勿抛错）。 */
    public void onInvite(GameSession session, GamePacket pkt) {
        log.debug("{} clone invite ignored (fake solo room)", session.player() != null
                ? session.player().account : "?");
    }

    private void enterRoom(GameSession session, GamePacket pkt, int boss) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        rec.clone.targetResId = boss > 0 ? boss : BOSSES[0];
        rec.clone.inRoom = true;
        rec.clone.allowQuickJoin = true;
        rec.clone.rewardFragOri = rollFragNotInMainline(rec);
        store.save(rec);
        session.send(MsgIds.S2C_CLONE_BASE, pkt, dump.cloneBase(rec, pickRoomPartner(rec), true, true,
                rec.clone.targetResId, rec.clone.allowQuickJoin, BOSSES));
        log.info("{} clone enter boss={} frag={} excludeMainlineTo n={} h={}",
                rec.account, rec.clone.targetResId, rec.clone.rewardFragOri,
                rec.progress.lastNormalStage, rec.progress.lastHardStage);
    }

    /**
     * 从可玩武将碎片中随机一枚：排除 RegionDropList 主线（region≥1000）里，
     * 玩家当前已通关进度（lastNormal / lastHard）及之前关会掉的碎片。
     * 池空（通关极深）则回退全可玩碎片。
     */
    String rollFragNotInMainline(PlayerRecord rec) {
        Set<String> excluded = mainlineFragOrisUpTo(
                rec == null || rec.progress == null ? 0 : rec.progress.lastNormalStage,
                rec == null || rec.progress == null ? 0 : rec.progress.lastHardStage);
        List<String> pool = new ArrayList<>();
        for (CultivateTables.HeroCfg h : cultivate.allHeroes()) {
            if (h == null || !cultivate.isPlayableHero(h.index)) {
                continue;
            }
            String ori = h.fragmentOri;
            if (ori == null || ori.isEmpty() || "0".equals(ori)) {
                continue;
            }
            if (excluded.contains(ori)) {
                continue;
            }
            pool.add(ori);
        }
        if (pool.isEmpty()) {
            for (CultivateTables.HeroCfg h : cultivate.allHeroes()) {
                if (h == null || !cultivate.isPlayableHero(h.index)) {
                    continue;
                }
                if (h.fragmentOri != null && !h.fragmentOri.isEmpty() && !"0".equals(h.fragmentOri)) {
                    pool.add(h.fragmentOri);
                }
            }
        }
        if (pool.isEmpty()) {
            return "GOODS3";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** 主线已可达关卡会掉的武将碎片 Ori 集合。 */
    Set<String> mainlineFragOrisUpTo(int lastNormal, int lastHard) {
        Set<String> out = new HashSet<>();
        for (GameTables.DropRow row : tables.allDrops()) {
            if (row == null || row.regionId < MAINLINE_REGION_MIN) {
                continue;
            }
            if (row.difficult == 1) {
                if (lastNormal <= 0 || row.regionId > lastNormal) {
                    continue;
                }
            } else if (row.difficult == 2) {
                if (lastHard <= 0 || row.regionId > lastHard) {
                    continue;
                }
            } else {
                continue;
            }
            for (GameTables.GoodsDrop g : row.certain) {
                addIfHeroFrag(out, g == null ? null : g.ori);
            }
            for (GameTables.RandDrop g : row.random) {
                addIfHeroFrag(out, g == null ? null : g.ori);
            }
            addIfHeroFrag(out, row.specialOri);
        }
        return out;
    }

    private void addIfHeroFrag(Set<String> out, String ori) {
        if (ori == null || ori.isEmpty() || "0".equals(ori)) {
            return;
        }
        if (cultivate.heroByFragment(ori) != null) {
            out.add(ori);
        }
    }

    private PlayerRecord pickRoomPartner(PlayerRecord host) {
        if (host == null) {
            return null;
        }
        PlayerRecord named = store.get(KfzNpcBootstrap.ACCOUNT_PREFIX + "0001");
        if (named != null && named.npcPassive && named.playerId != host.playerId) {
            named.ensureCollections();
            return named;
        }
        for (PlayerRecord p : store.all()) {
            if (p == null || !p.npcPassive || p.playerId == host.playerId) {
                continue;
            }
            p.ensureCollections();
            return p;
        }
        return null;
    }
}
