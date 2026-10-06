package com.sao.fakeserver.service;

import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.fight.FightUnit;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.FightConfigTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * KFZ 冒烟：主机↔NPC / NPC↔NPC 三场 {@link FightSyncService#simulatePlayerFormationVsPlayer}。
 * 不随启服自动跑；单测或显式 {@link #verify()}。
 */
@Component
public class KfzFlowSimRunner {
    private static final Logger log = LoggerFactory.getLogger(KfzFlowSimRunner.class);

    public static final String SIM_HOST_ACCOUNT = "sim_kfz_host";

    private final PlayerStore store;
    private final FightSyncService fightSync;
    private final CultivateTables cultivate;
    private final FightConfigTables fightCfg;
    public KfzFlowSimRunner(PlayerStore store, FightSyncService fightSync, CultivateTables cultivate,
                            FightConfigTables fightCfg) {
        this.store = store;
        this.fightSync = fightSync;
        this.cultivate = cultivate;
        this.fightCfg = fightCfg;
    }

    public void verify() {
        PlayerRecord npcA = store.get(KfzNpcBootstrap.ACCOUNT_PREFIX + "0001");
        PlayerRecord npcB = store.get(KfzNpcBootstrap.ACCOUNT_PREFIX + "0002");
        if (npcA == null || npcB == null) {
            log.warn("kfz flow sim skip: npc0001/0002 missing");
            return;
        }
        PlayerRecord host = resolveHost();
        if (host == null) {
            log.error("kfz flow sim FAIL: no host");
            return;
        }
        runCase("host-atk-vs-npc-def1", host, PlayerRecord.FORMATION_KFZ_ATK,
                npcA, PlayerRecord.FORMATION_KFZ_DEF1);
        runCase("npcA-vs-npcB-def1", npcA, PlayerRecord.FORMATION_KFZ_ATK,
                npcB, PlayerRecord.FORMATION_KFZ_DEF1);
        runCase("npcB-atk-vs-host-def1", npcB, PlayerRecord.FORMATION_KFZ_ATK,
                host, PlayerRecord.FORMATION_KFZ_DEF1);
    }

    private void runCase(String label, PlayerRecord atk, int formA, PlayerRecord def, int formB) {
        if (!assertRoster(label, atk, formA, def, formB)) {
            return;
        }
        try {
            FightSyncService.RobotMatchResult sim = fightSync.simulatePlayerFormationVsPlayer(
                    atk, formA, def, formB);
            log.info("kfz flow sim PASS {} → win={} kill={}/{} atk={} def={}",
                    label, sim.winnerGuid, sim.killNum, sim.loserKillNum, atk.playerId, def.playerId);
        } catch (RuntimeException e) {
            log.error("kfz flow sim FAIL {} atk={} def={}: {}",
                    label, atk.account, def.account, e.toString(), e);
        }
    }

    private boolean assertRoster(String label, PlayerRecord a, int formA, PlayerRecord b, int formB) {
        List<FightUnit> ua = FightRosterBuilder.fromPlayer(a, cultivate, fightCfg, formA);
        List<FightUnit> ub = FightRosterBuilder.fromPlayer(b, cultivate, fightCfg, formB);
        if (ua.isEmpty() || ub.isEmpty()) {
            log.error("kfz flow sim FAIL {} empty roster atk={}/{} def={}/{}",
                    label, a.account, ua.size(), b.account, ub.size());
            return false;
        }
        return true;
    }

    private PlayerRecord resolveHost() {
        // 不写死 admin：取等级最高的非 NPC 真人档，没有才现建模拟主机（多人环境下 admin 可能不存在）。
        PlayerRecord best = null;
        for (PlayerRecord p : store.all()) {
            if (p == null || p.npcPassive || p.account == null) {
                continue;
            }
            if (KfzNpcBootstrap.isKfzNpcAccount(p.account) || SIM_HOST_ACCOUNT.equals(p.account)) {
                continue;
            }
            if (countHeroes(p) < 5) {
                continue;
            }
            if (best == null || p.level > best.level
                    || (p.level == best.level && p.playerId > best.playerId)) {
                best = p;
            }
        }
        if (best != null) {
            ensureKfzForms(best);
            return best;
        }
        return createSimHost();
    }

    /** 保证主机有 KFZ 攻/防阵，避免冒烟空阵。 */
    private void ensureKfzForms(PlayerRecord rec) {
        rec.ensureCollections();
        List<String> ids = new ArrayList<>();
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h != null && h.id != null && !h.id.isEmpty()) {
                ids.add(h.id);
            }
            if (ids.size() >= 15) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        List<String> five = new ArrayList<>(ids.subList(0, Math.min(5, ids.size())));
        KfzNpcBootstrap.padFormation(five, ids, 5);
        if (FightRosterBuilder.fromPlayer(rec, cultivate, fightCfg, PlayerRecord.FORMATION_KFZ_ATK).isEmpty()) {
            rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_ATK, new ArrayList<>(five));
        }
        if (FightRosterBuilder.fromPlayer(rec, cultivate, fightCfg, PlayerRecord.FORMATION_KFZ_DEF1).isEmpty()) {
            rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_DEF1, new ArrayList<>(five));
        }
        store.save(rec);
    }

    /**
     * 供其它冒烟复用：返回（必要时新建）模拟主机 {@link #SIM_HOST_ACCOUNT}。
     * 建号逻辑与 KFZ 冒烟完全一致，避免各冒烟各自造档导致「no host」。
     */
    public PlayerRecord ensureSimHost() {
        PlayerRecord existing = store.get(SIM_HOST_ACCOUNT);
        if (existing != null && countHeroes(existing) >= 5) {
            return existing;
        }
        return createSimHost();
    }

    private PlayerRecord createSimHost() {
        PlayerRecord existing = store.get(SIM_HOST_ACCOUNT);
        if (existing != null && countHeroes(existing) >= 5) {
            existing.npcPassive = false;
            return existing;
        }
        List<Integer> pool = playableHeroIndices();
        if (pool.size() < 15) {
            log.error("kfz flow sim cannot create host: heroes={}", pool.size());
            return null;
        }
        PlayerRecord rec = new PlayerRecord();
        rec.ensureCollections();
        rec.account = SIM_HOST_ACCOUNT;
        rec.playerId = store.nextPlayerId();
        rec.npcPassive = false;
        rec.roleName = "KFZ试炼主机";
        rec.createdAt = Instant.now().toString();
        rec.level = 55;
        rec.mainRoleIndex = 1;
        rec.stamina = 120;
        rec.gold = 1000;
        Random rng = new Random(88001L);
        List<Integer> pick = new ArrayList<>(pool);
        Collections.shuffle(pick, rng);
        List<String> heroIds = new ArrayList<>();
        for (Integer idx : pick) {
            if (idx == null || idx <= 0 || heroIds.size() >= 15) {
                continue;
            }
            PlayerRecord.Hero h = new PlayerRecord.Hero();
            h.heroIndex = idx.intValue();
            h.id = PlayerDumpService.guidOf(SIM_HOST_ACCOUNT, idx.intValue());
            h.level = 52;
            h.stars = 3;
            h.stage = 0;
            h.skill1 = 3;
            h.skill2 = 3;
            h.skill3 = 3;
            h.skill4 = 3;
            h.ensureTimeStones();
            rec.heroes.add(h);
            heroIds.add(h.id);
        }
        rec.mainHeroIndex = rec.heroes.isEmpty() ? 18 : rec.heroes.get(0).heroIndex;
        List<String> def1 = new ArrayList<>(heroIds.subList(0, Math.min(5, heroIds.size())));
        KfzNpcBootstrap.padFormation(def1, heroIds, 5);
        rec.setFormationSlots(PlayerRecord.FORMATION_JJC_DEF, def1);
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_ATK, new ArrayList<>(def1));
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_DEF1, new ArrayList<>(def1));
        store.save(rec);
        log.info("kfz flow sim created host {} id={} heroes={}", SIM_HOST_ACCOUNT, rec.playerId, rec.heroes.size());
        return rec;
    }

    private static int countHeroes(PlayerRecord rec) {
        return rec == null || rec.heroes == null ? 0 : rec.heroes.size();
    }

    private List<Integer> playableHeroIndices() {
        List<Integer> out = new ArrayList<>();
        for (CultivateTables.HeroCfg h : cultivate.allHeroes()) {
            if (h != null && cultivate.isPlayableHero(h.index)) {
                out.add(h.index);
            }
        }
        Collections.sort(out);
        return out;
    }
}
