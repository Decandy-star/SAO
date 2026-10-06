package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.GameTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * KFZ 捏造类真人号：多战力段、每号 15 武将（三防）+ 少量装备/技能/进阶熟练度。
 * 账号 {@code npc_kfz_0001..}；{@code npcPassive}；启动补齐到 {@link SaoProperties#getKfzNpcCount()}。
 * 已存在：保留积分，缺装/缺熟练度则补齐。云发布无需上传 data/——jar 启动自生成。
 */
@Component
@Order(50)
public class KfzNpcBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KfzNpcBootstrap.class);

    public static final String ACCOUNT_PREFIX = "npc_kfz_";
    /** 养成 schema：2=装/技能；3=+BOB/ZBZ/KFZ 多阵与 zbz 布防。 */
    public static final int NPC_SCHEMA = 3;
    /** BOB 4 + ZBZ 2 + KFZ 5，共 11 档循环。 */
    private static final int TIERS = 11;
    private static final int HEROES_PER_NPC = 15;

    private static final String[] NAMES = {
            "霜刃", "夜鸦", "赤霞", "银枪", "墨羽", "苍岚", "烈阳", "寒星",
            "幻樱", "铁心", "青锋", "流火", "月影", "破军", "惊鸿", "玄甲",
            "风袭", "雷鸣", "雪落", "炎舞", "幽兰", "金鳞", "碧海", "紫电",
            "孤鸿", "猎手", "断岳", "流云", "夜叉", "天狼", "白虹", "朱雀"
    };

    /** 账号等级：BOB 20/25/30/35 → ZBZ 40/45 → KFZ 50/55/60/70/80。 */
    private static final int[] TIER_ACCOUNT_LV = {20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80};
    private static final int[] TIER_HERO_LV = {18, 22, 26, 30, 36, 42, 45, 55, 65, 75, 85};
    private static final int[] TIER_STARS = {1, 2, 2, 3, 3, 3, 2, 3, 4, 5, 5};
    private static final int[] TIER_STAGE = {0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3};
    private static final int[] TIER_KFZ_STARS = {1, 1, 1, 1, 2, 2, 1, 2, 3, 4, 5};
    private static final int[] TIER_SKILL = {1, 2, 2, 3, 3, 4, 2, 3, 4, 5, 6};
    private static final int[] TIER_SHULIAN = {20, 40, 60, 80, 100, 120, 40, 80, 120, 180, 240};

    private final PlayerStore store;
    private final CultivateTables cultivate;
    private final GameTables tables;
    private final SaoProperties props;

    public KfzNpcBootstrap(PlayerStore store, CultivateTables cultivate, GameTables tables,
                             SaoProperties props) {
        this.store = store;
        this.cultivate = cultivate;
        this.tables = tables;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Integer> pool = playableHeroIndices();
        if (pool.size() < HEROES_PER_NPC) {
            log.warn("kfz npc bootstrap skip: playable heroes={} need>={}", pool.size(), HEROES_PER_NPC);
            return;
        }
        List<Integer> equipLibs = tables.robotEquipLibIds();
        if (equipLibs.isEmpty()) {
            log.warn("kfz npc bootstrap: no JJC_RobotEquips libs, heroes only");
        }
        int target = Math.max(20, Math.min(1000, props.getKfzNpcCount()));
        int created = 0;
        int enriched = 0;
        for (int i = 1; i <= target; i++) {
            String account = ACCOUNT_PREFIX + String.format("%04d", i);
            int tier = (i - 1) % TIERS;
            int slot = (i - 1) / TIERS;
            PlayerRecord existing = store.get(account);
            if (existing != null) {
                if (enrichNpc(existing, tier, equipLibs)) {
                    store.save(existing);
                    enriched++;
                }
                continue;
            }
            PlayerRecord rec = buildNpc(account, tier, slot, pool, equipLibs);
            store.save(rec);
            created++;
            if (created <= 5 || i == target) {
                log.info("kfz npc created {} id={} tier={} heroes={} eqs={} totalFp={} score={}",
                        account, rec.playerId, tier + 1, rec.heroes.size(),
                        rec.equipments == null ? 0 : rec.equipments.size(),
                        rec.npcTotalFightPower, rec.kfz.score);
            }
        }
        // 旧版 npc_kfz_t* 也补装
        for (PlayerRecord p : store.all()) {
            if (p == null || p.account == null || !p.account.startsWith(ACCOUNT_PREFIX)) {
                continue;
            }
            if (p.account.matches("npc_kfz_\\d{4}")) {
                continue;
            }
            int tier = Math.max(0, Math.min(TIERS - 1, guessTier(p)));
            if (enrichNpc(p, tier, equipLibs)) {
                store.save(p);
                enriched++;
            }
        }
        log.info("kfz npc bootstrap done target={} created={} enriched={} equipLibs={}",
                target, created, enriched, equipLibs.size());
    }

    private int guessTier(PlayerRecord p) {
        int lv = p == null ? 0 : p.level;
        int best = 0;
        for (int i = 0; i < TIERS; i++) {
            if (lv + 2 >= TIER_ACCOUNT_LV[i]) {
                best = i;
            }
        }
        return best;
    }

    /** @return true 若改动需落盘 */
    private boolean enrichNpc(PlayerRecord rec, int tier, List<Integer> equipLibs) {
        rec.ensureCollections();
        rec.npcPassive = true;
        boolean changed = false;
        if (rec.kfz.npcSchema < 2) {
            int sk = TIER_SKILL[tier];
            int shu = TIER_SHULIAN[tier];
            if (rec.heroes != null) {
                for (PlayerRecord.Hero h : rec.heroes) {
                    if (h == null) {
                        continue;
                    }
                    h.skill1 = Math.max(h.skill1, sk);
                    h.skill2 = Math.max(h.skill2, sk);
                    h.skill3 = Math.max(h.skill3, sk);
                    h.skill4 = Math.max(h.skill4, sk);
                    if (h.stagePara1 < shu) {
                        h.stagePara1 = shu;
                        h.stagePara2 = shu;
                        h.stagePara3 = shu;
                        h.stagePara4 = shu;
                    }
                    h.ensureTimeStones();
                }
            }
            recomputeTotalFp(rec);
            rec.kfz.npcSchema = 2;
            rec.kfz.eligibleOverride = true;
            rec.kfz.eligibleThisWeek = true;
            changed = true;
        }
        // 无论 schema：缺装的武将补 JJC_RobotEquips（开战包要写 Brief）
        int beforeEq = rec.equipments == null ? 0 : rec.equipments.size();
        attachEquipsForAllHeroes(rec, tier, equipLibs, new Random(rec.playerId * 31L + 7));
        int afterEq = rec.equipments == null ? 0 : rec.equipments.size();
        if (afterEq > beforeEq) {
            recomputeTotalFp(rec);
            changed = true;
        }
        if (rec.kfz.npcSchema < NPC_SCHEMA) {
            applyGameplayFormations(rec);
            rec.kfz.npcSchema = NPC_SCHEMA;
            changed = true;
        }
        return changed;
    }

    private PlayerRecord buildNpc(String account, int tier, int slot, List<Integer> pool,
                                    List<Integer> equipLibs) {
        PlayerRecord rec = new PlayerRecord();
        rec.ensureCollections();
        rec.account = account;
        rec.playerId = store.nextPlayerId();
        rec.npcPassive = true;
        rec.roleName = NAMES[(tier + slot * TIERS) % NAMES.length] + (1000 + slot * TIERS + tier);
        rec.createdAt = Instant.now().toString();
        rec.level = TIER_ACCOUNT_LV[tier];
        rec.mainRoleIndex = 1;
        rec.stamina = 120;
        rec.gold = 1000;

        int heroLv = TIER_HERO_LV[tier];
        int stars = TIER_STARS[tier];
        int stage = TIER_STAGE[tier];
        int sk = TIER_SKILL[tier];
        int shu = TIER_SHULIAN[tier];
        Random rng = new Random(10007L * (tier + 1) + 97L * (slot + 1) + account.hashCode());
        List<Integer> pick = new ArrayList<>(pool);
        Collections.shuffle(pick, rng);
        List<Integer> chosen = new ArrayList<>(HEROES_PER_NPC);
        for (Integer idx : pick) {
            if (idx == null || idx <= 0 || chosen.contains(idx)) {
                continue;
            }
            chosen.add(idx);
            if (chosen.size() >= HEROES_PER_NPC) {
                break;
            }
        }
        List<String> heroIds = new ArrayList<>();
        for (int idx : chosen) {
            PlayerRecord.Hero h = new PlayerRecord.Hero();
            h.heroIndex = idx;
            h.id = PlayerDumpService.guidOf(account, idx);
            h.level = Math.max(1, heroLv + (rng.nextInt(3) - 1));
            h.stars = Math.max(1, stars);
            h.stage = Math.max(0, stage);
            h.stagePara1 = shu;
            h.stagePara2 = shu;
            h.stagePara3 = shu;
            h.stagePara4 = shu;
            h.skill1 = sk;
            h.skill2 = sk;
            h.skill3 = sk;
            h.skill4 = sk;
            h.ensureTimeStones();
            rec.heroes.add(h);
            heroIds.add(h.id);
        }
        while (heroIds.size() < HEROES_PER_NPC && !heroIds.isEmpty()) {
            heroIds.add(heroIds.get(heroIds.size() % heroIds.size()));
        }
        rec.mainHeroIndex = rec.heroes.isEmpty() ? 18 : rec.heroes.get(0).heroIndex;

        List<String> def1 = new ArrayList<>(heroIds.subList(0, Math.min(5, heroIds.size())));
        List<String> def2 = new ArrayList<>(heroIds.subList(Math.min(5, heroIds.size()), Math.min(10, heroIds.size())));
        List<String> def3 = new ArrayList<>(heroIds.subList(Math.min(10, heroIds.size()), Math.min(15, heroIds.size())));
        padFormation(def1, heroIds, 5);
        padFormation(def2, heroIds, 5);
        padFormation(def3, heroIds, 5);
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_DEF1, def1);
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_DEF2, def2);
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_DEF3, def3);
        rec.setFormationSlots(PlayerRecord.FORMATION_KFZ_ATK, new ArrayList<>(def1));
        rec.setFormationSlots(PlayerRecord.FORMATION_PVE, new ArrayList<>(def1));
        rec.setFormationSlots(PlayerRecord.FORMATION_JJC_DEF, new ArrayList<>(def1));
        applyGameplayFormations(rec, def1, def2, heroIds);

        attachEquipsForAllHeroes(rec, tier, equipLibs, rng);
        recomputeTotalFp(rec);

        rec.kfz.eligibleOverride = true;
        rec.kfz.eligibleThisWeek = true;
        rec.kfz.stars = TIER_KFZ_STARS[tier];
        rec.kfz.score = Math.max(40, rec.npcTotalFightPower / 80) + rng.nextInt(30);
        rec.kfz.rank = 40 + tier * 8 + (slot % 20);
        rec.kfz.phase = 1;
        rec.kfz.npcSchema = NPC_SCHEMA;
        return rec;
    }

    private void applyGameplayFormations(PlayerRecord rec) {
        List<String> heroIds = new ArrayList<>();
        if (rec.heroes != null) {
            for (PlayerRecord.Hero h : rec.heroes) {
                if (h != null && h.id != null && !h.id.isEmpty()) {
                    heroIds.add(h.id);
                }
            }
        }
        List<String> def1 = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_KFZ_DEF1));
        List<String> def2 = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_KFZ_DEF2));
        if (def1.isEmpty() && !heroIds.isEmpty()) {
            def1 = new ArrayList<>(heroIds.subList(0, Math.min(5, heroIds.size())));
            padFormation(def1, heroIds, 5);
        }
        if (def2.isEmpty() && heroIds.size() > 5) {
            def2 = new ArrayList<>(heroIds.subList(Math.min(5, heroIds.size()), Math.min(10, heroIds.size())));
            padFormation(def2, heroIds, 5);
        }
        applyGameplayFormations(rec, def1, def2, heroIds);
    }

    private static void applyGameplayFormations(PlayerRecord rec, List<String> def1, List<String> def2,
                                                List<String> heroIds) {
        rec.setFormationSlots(PlayerRecord.FORMATION_BOB_ATK, new ArrayList<>(def1));
        List<String> zbzSlot = new ArrayList<>();
        if (def1 != null && !def1.isEmpty()) {
            zbzSlot.add(def1.get(0));
        } else if (heroIds != null && !heroIds.isEmpty()) {
            zbzSlot.add(heroIds.get(0));
        }
        rec.setFormationSlots(PlayerRecord.FORMATION_ZBZ, zbzSlot);
        List<String> zbzDef = new ArrayList<>();
        if (def2 != null && !def2.isEmpty()) {
            zbzDef.addAll(def2);
        } else if (def1 != null) {
            zbzDef.addAll(def1);
        }
        while (zbzDef.size() < 10 && heroIds != null && !heroIds.isEmpty()) {
            zbzDef.add(heroIds.get(zbzDef.size() % heroIds.size()));
        }
        if (zbzDef.size() > 10) {
            zbzDef = new ArrayList<>(zbzDef.subList(0, 10));
        }
        rec.zbz.defenseWuJiangIds = zbzDef;
    }

    private void attachEquipsForAllHeroes(PlayerRecord rec, int tier, List<Integer> equipLibs, Random rng) {
        if (equipLibs == null || equipLibs.isEmpty() || rec.heroes == null) {
            return;
        }
        if (rec.equipments == null) {
            rec.equipments = new ArrayList<>();
        }
        // 清掉无 owner 的脏装，保留已穿
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h == null || h.id == null) {
                continue;
            }
            boolean has = false;
            for (PlayerRecord.Equipment eq : rec.equipments) {
                if (eq != null && h.id.equals(eq.owner)) {
                    has = true;
                    break;
                }
            }
            if (has) {
                continue;
            }
            int libId = equipLibs.get(Math.floorMod(rng.nextInt(), equipLibs.size()));
            // 高档倾向较大 id
            if (tier >= 3 && equipLibs.size() > 1) {
                libId = equipLibs.get(Math.min(equipLibs.size() - 1,
                        Math.max(0, equipLibs.size() * 2 / 3 + rng.nextInt(Math.max(1, equipLibs.size() / 3)))));
            }
            GameTables.RobotEquipLib lib = tables.robotEquipLib(libId);
            if (lib == null) {
                continue;
            }
            CultivateTables.HeroCfg cfg = cultivate.heroByIndex(h.heroIndex);
            if (cfg != null) {
                addOwnedEquip(rec, h.id, lib, slotOri(lib.weaponsAndPeiJians, cfg.weaponJob));
                addOwnedEquip(rec, h.id, lib, slotOri(lib.weaponsAndPeiJians, cfg.peiJianJob));
            }
            for (int i = 3; i <= 5; i++) {
                addOwnedEquip(rec, h.id, lib, slotOri(lib.equips, i));
            }
        }
    }

    private void addOwnedEquip(PlayerRecord rec, String owner, GameTables.RobotEquipLib lib, String ori) {
        if (ori == null || ori.isEmpty() || "0".equals(ori)) {
            return;
        }
        if (cultivate.equip(ori) == null) {
            return;
        }
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = rec.account + "-eq-" + (rec.nextEquipSeq++);
        eq.ori = ori;
        eq.owner = owner;
        eq.level = Math.max(1, lib.level);
        eq.stars = Math.max(0, lib.stars);
        eq.guhua = Math.max(0, lib.guHuaLevel);
        eq.cuiLianParts = new boolean[]{false, false, false, false};
        rec.equipments.add(eq);
    }

    private static String slotOri(String[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) {
            return null;
        }
        String s = arr[idx];
        if (s == null || s.isEmpty() || "0".equals(s)) {
            return null;
        }
        return s;
    }

    private void recomputeTotalFp(PlayerRecord rec) {
        int total = 0;
        if (rec.heroes != null) {
            for (PlayerRecord.Hero h : rec.heroes) {
                if (h == null) {
                    continue;
                }
                h.fightPower = cultivate.computeFightPower(rec, h);
                total += Math.max(0, h.fightPower);
            }
        }
        rec.npcTotalFightPower = total;
    }

    public static void padFormation(List<String> team, List<String> pool, int need) {
        int i = 0;
        while (team.size() < need && pool != null && !pool.isEmpty() && i < need * 4) {
            team.add(pool.get(i % pool.size()));
            i++;
        }
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

    public static boolean isKfzNpcAccount(String account) {
        return account != null && account.startsWith(ACCOUNT_PREFIX);
    }
}
