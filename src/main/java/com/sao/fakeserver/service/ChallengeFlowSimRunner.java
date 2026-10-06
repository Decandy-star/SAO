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

import java.util.ArrayList;
import java.util.List;

/**
 * BOB + ZBZ + KFZ 冒烟核对。不挂启动；需要时调 {@link #verifyAll()} 或跑 {@code ChallengeFlowSimSpringTest}。
 */
@Component
public class ChallengeFlowSimRunner {
    private static final Logger log = LoggerFactory.getLogger(ChallengeFlowSimRunner.class);

    private final PlayerStore store;
    private final BobService bob;
    private final ZbzService zbz;
    private final FightSyncService fightSync;
    private final CultivateTables cultivate;
    private final FightConfigTables fightCfg;
    private final KfzFlowSimRunner kfzFlowSim;

    public ChallengeFlowSimRunner(PlayerStore store, BobService bob, ZbzService zbz,
                                  FightSyncService fightSync, CultivateTables cultivate,
                                  FightConfigTables fightCfg, KfzFlowSimRunner kfzFlowSim) {
        this.store = store;
        this.bob = bob;
        this.zbz = zbz;
        this.fightSync = fightSync;
        this.cultivate = cultivate;
        this.fightCfg = fightCfg;
        this.kfzFlowSim = kfzFlowSim;
    }

    /**
     * @return null=三挑战冒烟通过；非空=失败原因
     */
    public String verifyAll() {
        PlayerRecord host = resolveHost();
        if (host == null) {
            return "no host";
        }
        String bobErr = bob.smokeVerify(host);
        if (bobErr != null) {
            return "bob: " + bobErr;
        }
        // smoke 可能改过 host，重新读盘
        host = store.get(host.account);
        if (host == null) {
            return "host vanished after bob";
        }
        String zbzErr = zbz.smokeVerify(host);
        if (zbzErr != null) {
            return "zbz: " + zbzErr;
        }
        host = store.get(host.account);
        if (host == null) {
            return "host vanished after zbz";
        }
        String kfzErr = smokeKfz(host);
        if (kfzErr != null) {
            return "kfz: " + kfzErr;
        }
        log.info("challenge flow sim PASS bob+zbz+kfz");
        return null;
    }

    private String smokeKfz(PlayerRecord host) {
        PlayerRecord npcA = store.get(KfzNpcBootstrap.ACCOUNT_PREFIX + "0001");
        PlayerRecord npcB = store.get(KfzNpcBootstrap.ACCOUNT_PREFIX + "0002");
        if (npcA == null || npcB == null) {
            return "npc0001/0002 missing";
        }
        ensureKfzForms(host);
        String e1 = runKfzCase("host-atk-vs-npc-def1", host, PlayerRecord.FORMATION_KFZ_ATK,
                npcA, PlayerRecord.FORMATION_KFZ_DEF1);
        if (e1 != null) {
            return e1;
        }
        String e2 = runKfzCase("npcA-vs-npcB-def1", npcA, PlayerRecord.FORMATION_KFZ_ATK,
                npcB, PlayerRecord.FORMATION_KFZ_DEF1);
        if (e2 != null) {
            return e2;
        }
        return runKfzCase("npcB-atk-vs-host-def1", npcB, PlayerRecord.FORMATION_KFZ_ATK,
                host, PlayerRecord.FORMATION_KFZ_DEF1);
    }

    private String runKfzCase(String label, PlayerRecord atk, int formA, PlayerRecord def, int formB) {
        List<FightUnit> ua = FightRosterBuilder.fromPlayer(atk, cultivate, fightCfg, formA);
        List<FightUnit> ub = FightRosterBuilder.fromPlayer(def, cultivate, fightCfg, formB);
        if (ua.isEmpty() || ub.isEmpty()) {
            return label + " empty roster atk=" + ua.size() + " def=" + ub.size();
        }
        try {
            FightSyncService.RobotMatchResult sim = fightSync.simulatePlayerFormationVsPlayer(
                    atk, formA, def, formB);
            if (sim == null || sim.winnerGuid <= 0) {
                return label + " no winner";
            }
            log.info("challenge kfz PASS {} → win={} kill={}/{}",
                    label, sim.winnerGuid, sim.killNum, sim.loserKillNum);
            return null;
        } catch (RuntimeException e) {
            return label + " " + e.toString();
        }
    }

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

    private PlayerRecord resolveHost() {
        // 不写死 admin（多人环境下 admin 未必存在）：模拟主机 → 任意真人档 → 现建模拟主机。
        PlayerRecord sim = store.get(KfzFlowSimRunner.SIM_HOST_ACCOUNT);
        if (sim != null && countHeroes(sim) >= 5) {
            return sim;
        }
        for (PlayerRecord p : store.all()) {
            if (p == null || p.npcPassive || p.account == null) {
                continue;
            }
            if (KfzNpcBootstrap.isKfzNpcAccount(p.account)) {
                continue;
            }
            if (countHeroes(p) >= 5) {
                return p;
            }
        }
        // 兜底：与 KFZ 冒烟共用同一个模拟主机（空目录下自动建号），否则全新 data 目录必然 "no host"
        return kfzFlowSim.ensureSimHost();
    }

    private static int countHeroes(PlayerRecord rec) {
        return rec == null || rec.heroes == null ? 0 : rec.heroes.size();
    }
}
