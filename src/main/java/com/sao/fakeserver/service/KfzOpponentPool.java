package com.sao.fakeserver.service;

import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.GameTables;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * KFZ 混合对手池：优先真人登录号，其次捏造 NPC（{@code npcPassive}），再不足才补 JJC_Robot。
 * NPC / 真人均走 PlayerRecord 三防；开战 {@code beginBattleKfzVsPlayer}。
 */
public final class KfzOpponentPool {
    public enum Kind {
        REAL,
        ROBOT
    }

    public static final class Opponent {
        public Kind kind;
        /** playerId 或 robotGuid。 */
        public int guid;
        public String name = "";
        public int resId;
        public int level;
        public int stars;
        /** 3 队 × 5 武将 index。 */
        public List<List<Integer>> defTeams = new ArrayList<>();
        /** 每队机器人装备/等级模板 guid；真人全 0。长度 3。 */
        public int[] equipTemplateGuid = new int[3];
    }

    private final PlayerStore store;
    private final GameTables tables;

    public KfzOpponentPool(PlayerStore store, GameTables tables) {
        this.store = store;
        this.tables = tables;
    }

    public List<Opponent> pickPysOpponents(PlayerRecord self, int need) {
        List<Opponent> out = new ArrayList<>();
        if (self == null || need <= 0) {
            return out;
        }
        int openLv = Math.max(1, tables.kfzBase().openLevel);
        int myScore = Math.max(0, self.kfz.score);
        List<PlayerRecord> reals = new ArrayList<>();
        for (PlayerRecord p : store.all()) {
            if (p == null || p.playerId == self.playerId) {
                continue;
            }
            if (p.level < openLv) {
                continue;
            }
            if (!p.kfz.eligibleThisWeek && !p.kfz.eligibleOverride) {
                continue;
            }
            if (!hasKfzDefOrHeroes(p)) {
                continue;
            }
            reals.add(p);
        }
        reals.sort(Comparator
                .comparingInt((PlayerRecord p) -> p.npcPassive ? 1 : 0)
                .thenComparingInt(p -> Math.abs(Math.max(0, p.kfz.score) - myScore)));
        Set<Integer> used = new HashSet<>();
        for (PlayerRecord p : reals) {
            if (out.size() >= need) {
                break;
            }
            Opponent o = buildRealOpponent(p);
            if (o == null) {
                continue;
            }
            out.add(o);
            used.add(o.guid);
        }
        int remain = need - out.size();
        if (remain > 0) {
            for (GameTables.RobotRow r : matchRobots(self, remain + 4)) {
                if (r == null || used.contains(r.targetGuid)) {
                    continue;
                }
                Opponent o = buildRobotOpponent(r);
                if (o == null) {
                    continue;
                }
                out.add(o);
                used.add(o.guid);
                if (out.size() >= need) {
                    break;
                }
            }
        }
        return out;
    }

    public Opponent resolveOpponent(PlayerRecord self, int targetGuid) {
        if (targetGuid <= 0) {
            return null;
        }
        PlayerRecord foe = store.findByPlayerId(targetGuid);
        if (foe != null && (self == null || foe.playerId != self.playerId)) {
            return buildRealOpponent(foe);
        }
        GameTables.RobotRow robot = tables.robotByGuid(targetGuid);
        if (robot != null) {
            return buildRobotOpponent(robot);
        }
        return null;
    }

    public Opponent buildRealOpponent(PlayerRecord foe) {
        if (foe == null) {
            return null;
        }
        Opponent o = new Opponent();
        o.kind = Kind.REAL;
        o.guid = foe.playerId;
        o.name = foe.roleName == null ? "" : foe.roleName;
        o.resId = foe.mainHeroIndex > 0 ? foe.mainHeroIndex : 18;
        o.level = foe.level;
        o.stars = Math.max(0, foe.kfz.stars);
        o.equipTemplateGuid = new int[]{0, 0, 0};
        o.defTeams = buildRealDefTeams(foe);
        return o;
    }

    public Opponent buildRobotOpponent(GameTables.RobotRow primary) {
        if (primary == null) {
            return null;
        }
        Opponent o = new Opponent();
        o.kind = Kind.ROBOT;
        o.guid = primary.targetGuid;
        o.name = primary.name == null ? "机器人" : primary.name;
        o.resId = primary.resId > 0 ? primary.resId : 18;
        o.level = primary.level > 0 ? primary.level : 1;
        o.stars = Math.max(1, primary.stars);
        int[] templates = pickThreeDistinctRobotGuids(primary);
        o.equipTemplateGuid = templates;
        o.defTeams = buildRobotDefTeams(templates);
        return o;
    }

    /** 从 FORMATION_KFZ_DEF1/2/3 取 index；空槽从其它武将补，三队不复用。 */
    static List<List<Integer>> buildRealDefTeams(PlayerRecord foe) {
        Set<Integer> used = new LinkedHashSet<>();
        List<List<Integer>> teams = new ArrayList<>(3);
        int[] types = {
                PlayerRecord.FORMATION_KFZ_DEF1,
                PlayerRecord.FORMATION_KFZ_DEF2,
                PlayerRecord.FORMATION_KFZ_DEF3
        };
        for (int t : types) {
            List<Integer> team = new ArrayList<>(5);
            for (String id : foe.formationSlots(t)) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                PlayerRecord.Hero h = foe.findHero(id);
                if (h == null || h.heroIndex <= 0 || used.contains(h.heroIndex)) {
                    continue;
                }
                team.add(h.heroIndex);
                used.add(h.heroIndex);
                if (team.size() >= 5) {
                    break;
                }
            }
            teams.add(team);
        }
        List<Integer> pool = new ArrayList<>();
        if (foe.heroes != null) {
            for (PlayerRecord.Hero h : foe.heroes) {
                if (h != null && h.heroIndex > 0 && !used.contains(h.heroIndex)) {
                    pool.add(h.heroIndex);
                }
            }
        }
        int poolIdx = 0;
        for (List<Integer> team : teams) {
            while (team.size() < 5 && poolIdx < pool.size()) {
                int idx = pool.get(poolIdx++);
                team.add(idx);
                used.add(idx);
            }
            while (team.size() < 5) {
                int fallback = 18;
                for (int i = 1; i <= 80; i++) {
                    if (!used.contains(i)) {
                        fallback = i;
                        break;
                    }
                }
                team.add(fallback);
                used.add(fallback);
            }
        }
        return teams;
    }

    private List<List<Integer>> buildRobotDefTeams(int[] templates) {
        LinkedHashSet<Integer> uniq = new LinkedHashSet<>();
        for (int g : templates) {
            GameTables.RobotRow r = tables.robotByGuid(g);
            if (r == null || r.wjIndex == null) {
                continue;
            }
            for (int wj : r.wjIndex) {
                if (wj > 0) {
                    uniq.add(wj);
                }
            }
        }
        for (GameTables.RobotRow r : tables.robots()) {
            if (r == null || r.wjIndex == null) {
                continue;
            }
            for (int wj : r.wjIndex) {
                if (wj > 0) {
                    uniq.add(wj);
                }
                if (uniq.size() >= 15) {
                    break;
                }
            }
            if (uniq.size() >= 15) {
                break;
            }
        }
        for (int i = 1; uniq.size() < 15 && i <= 80; i++) {
            uniq.add(i);
        }
        List<Integer> flat = new ArrayList<>(uniq);
        while (flat.size() < 15) {
            flat.add(18);
        }
        if (flat.size() > 15) {
            flat = new ArrayList<>(flat.subList(0, 15));
        }
        List<List<Integer>> teams = new ArrayList<>(3);
        for (int t = 0; t < 3; t++) {
            teams.add(new ArrayList<>(flat.subList(t * 5, t * 5 + 5)));
        }
        return teams;
    }

    private int[] pickThreeDistinctRobotGuids(GameTables.RobotRow primary) {
        List<GameTables.RobotRow> all = tables.robots();
        if (all == null || all.isEmpty()) {
            return new int[]{primary.targetGuid, primary.targetGuid, primary.targetGuid};
        }
        int idx = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) != null && all.get(i).targetGuid == primary.targetGuid) {
                idx = i;
                break;
            }
        }
        LinkedHashSet<Integer> picked = new LinkedHashSet<>();
        picked.add(primary.targetGuid);
        for (int step = 1; picked.size() < 3 && step < all.size() + 2; step++) {
            GameTables.RobotRow a = all.get((idx + step) % all.size());
            GameTables.RobotRow b = all.get((idx - step % all.size() + all.size()) % all.size());
            if (a != null) {
                picked.add(a.targetGuid);
            }
            if (picked.size() >= 3) {
                break;
            }
            if (b != null) {
                picked.add(b.targetGuid);
            }
        }
        while (picked.size() < 3) {
            picked.add(primary.targetGuid + picked.size());
        }
        List<Integer> list = new ArrayList<>(picked);
        return new int[]{list.get(0), list.get(1), list.get(2)};
    }

    private List<GameTables.RobotRow> matchRobots(PlayerRecord rec, int need) {
        tables.ensureKfzRobotPool(Math.max(need, 8));
        GameTables.KfzBaseCfg cfg = tables.kfzBase();
        int myScore = Math.max(0, rec.kfz.score);
        int myStars = Math.max(0, rec.kfz.stars);
        int minMod = Math.max(1, cfg.minPiPeiModifier);
        int maxMod = Math.max(minMod, cfg.maxPiPeiModifier);
        int starTol = Math.max(0, cfg.starMatchValue);
        List<GameTables.RobotRow> all = tables.robots();
        List<GameTables.RobotRow> picked = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int range = minMod; picked.size() < need && range <= maxMod * 8; range += minMod) {
            List<GameTables.RobotRow> prefer = new ArrayList<>();
            List<GameTables.RobotRow> rest = new ArrayList<>();
            for (GameTables.RobotRow r : all) {
                if (r == null || used.contains(r.targetGuid)) {
                    continue;
                }
                int est = KfzService.robotSeedScore(r);
                if (Math.abs(est - myScore) > range) {
                    continue;
                }
                if (Math.abs(Math.max(1, r.stars) - myStars) <= starTol) {
                    prefer.add(r);
                } else {
                    rest.add(r);
                }
            }
            for (GameTables.RobotRow r : prefer) {
                if (picked.size() >= need) {
                    break;
                }
                picked.add(r);
                used.add(r.targetGuid);
            }
            for (GameTables.RobotRow r : rest) {
                if (picked.size() >= need) {
                    break;
                }
                picked.add(r);
                used.add(r.targetGuid);
            }
        }
        for (GameTables.RobotRow r : all) {
            if (picked.size() >= need) {
                break;
            }
            if (r != null && !used.contains(r.targetGuid)) {
                picked.add(r);
                used.add(r.targetGuid);
            }
        }
        return picked;
    }

    static boolean hasKfzDefOrHeroes(PlayerRecord p) {
        if (p == null) {
            return false;
        }
        int[] types = {
                PlayerRecord.FORMATION_KFZ_DEF1,
                PlayerRecord.FORMATION_KFZ_DEF2,
                PlayerRecord.FORMATION_KFZ_DEF3
        };
        for (int t : types) {
            for (String id : p.formationSlots(t)) {
                if (id != null && !id.isEmpty() && p.findHero(id) != null) {
                    return true;
                }
            }
        }
        return p.heroes != null && !p.heroes.isEmpty();
    }

    public static List<Integer> flattenDefTeams(List<List<Integer>> teams) {
        List<Integer> out = new ArrayList<>(15);
        if (teams == null) {
            return out;
        }
        for (List<Integer> team : teams) {
            if (team == null) {
                continue;
            }
            for (Integer h : team) {
                out.add(h == null ? 18 : h);
            }
        }
        while (out.size() < 15) {
            out.add(18);
        }
        if (out.size() > 15) {
            return new ArrayList<>(out.subList(0, 15));
        }
        return out;
    }
}
