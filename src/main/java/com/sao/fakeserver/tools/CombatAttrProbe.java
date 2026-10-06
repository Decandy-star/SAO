package com.sao.fakeserver.tools;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.fight.CombatAttrCalculator;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.table.CultivateTables;

/**
 * 自组阵容跑面板，并与 APK EquipmentData.GetAttrValueFloat（jingLian=0）对照。
 * <pre>
 * mvn -q -DskipTests exec:java -Dexec.mainClass=com.sao.fakeserver.tools.CombatAttrProbe
 * </pre>
 */
public final class CombatAttrProbe {
    private CombatAttrProbe() {
    }

    public static void main(String[] args) {
        SaoProperties props = new SaoProperties();
        props.setTablesDir("tables");
        CultivateTables tables = new CultivateTables(props);
        tables.init();

        dumpCase(tables, "A裸装单将", lineup(hero(17, 30, 3, 0)), null);
        dumpCase(tables, "B高星高阶", lineup(hero(17, 50, 6, 5)), null);

        PlayerRecord.Hero h17 = hero(17, 40, 4, 2);
        PlayerRecord recC = lineup(h17, hero(18, 40, 4, 2), hero(19, 40, 4, 2));
        fillBuddies(recC, 17, 18, 19);
        dumpCase(tables, "C三人羁绊位", recC, h17);

        PlayerRecord recD = lineup(hero(17, 40, 4, 2), hero(18, 40, 4, 2), hero(19, 40, 4, 2));
        fillBuddies(recD, 17, 18, 19);
        String eqOri = attachSampleEquip(tables, recD, recD.heroes.get(0), 20, 3, 0);
        dumpCase(tables, "D羁绊+装备(jingLian0)", recD, recD.heroes.get(0));

        int[] fp = CombatAttrCalculator.fightPowerReturn(tables, recD);
        System.out.printf("D FightPowerReturn atk/def/hp = %d/%d/%d%n", fp[0], fp[1], fp[2]);

        if (eqOri != null) {
            System.out.println("======== equip jingLian/cuiLian table cases ========");
            CultivateTables.EquipCfg ec = tables.equip(eqOri);
            // 紫装品质才有精炼表；若本件 quality<4 换一件紫武
            if (ec.quality < 4) {
                for (CultivateTables.EquipCfg e2 : tables.allEquips()) {
                    if (e2 != null && e2.type == 1 && e2.quality >= 4 && e2.atkGrow > 0f) {
                        ec = e2;
                        eqOri = e2.ori;
                        System.out.println("  switch probe equip to " + eqOri + " q=" + ec.quality);
                        break;
                    }
                }
            }
            printEquip(tables, eqOri, ec, 20, 3, 0, 0, 0, 0, 0);
            printEquip(tables, eqOri, ec, 20, 3, 0, 0, 5, 0, 0);
            printEquip(tables, eqOri, ec, 20, 3, 0, 0, 5, 5, 3);
            printEquip(tables, eqOri, ec, 20, 5, 2, 1, 0, 0, 0);
            printEquip(tables, eqOri, ec, 20, 5, 2, 11111, 0, 0, 0);
        }
    }

    private static void printEquip(CultivateTables tables, String ori, CultivateTables.EquipCfg ec,
                                   int lv, int star, int gh, int cui, int sub1, int sub2, int fei) {
        float atk = tables.equipAttrFloat(ec, 0, lv, star, gh, cui, sub1, sub2, fei);
        float hp = tables.equipAttrFloat(ec, 3, lv, star, gh, cui, sub1, sub2, fei);
        System.out.printf("  %s lv=%d★%d gh=%d cui=%d sub=%d/%d fei=%d -> atk=%.2f hp=%.2f%n",
                ori, lv, star, gh, cui, sub1, sub2, fei, atk, hp);
    }

    private static boolean almost(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static void dumpCase(CultivateTables tables, String title, PlayerRecord rec, PlayerRecord.Hero focus) {
        System.out.println("======== " + title + " ======== ");
        for (PlayerRecord.Hero h : rec.heroes) {
            if (focus != null && h.heroIndex != focus.heroIndex) {
                continue;
            }
            CultivateTables.CombatStats bare = CombatAttrCalculator.build(tables, rec, h, false);
            CultivateTables.CombatStats full = CombatAttrCalculator.build(tables, rec, h, true);
            if (bare == null || full == null) {
                System.out.printf("  wj=%d MISSING cfg%n", h.heroIndex);
                continue;
            }
            System.out.printf(
                    "  wj=%d lv=%d star=%d stage=%d | bare atk=%.1f/%.1f def=%.1f/%.1f hp=%d fp=%d%n"
                            + "                         | full atk=%.1f/%.1f def=%.1f/%.1f hp=%d fp=%d%n",
                    h.heroIndex, h.level, h.stars, h.stage,
                    bare.phyAtk, bare.magAtk, bare.pdef, bare.mdef, bare.maxHp, tables.fightPowerOf(bare),
                    full.phyAtk, full.magAtk, full.pdef, full.mdef, full.maxHp, tables.fightPowerOf(full));
        }
    }

    private static void fillBuddies(PlayerRecord rec, int... idxs) {
        rec.jiban.ensure();
        rec.jiban.buddies.clear();
        for (int idx : idxs) {
            rec.jiban.buddies.add(idx);
        }
        while (rec.jiban.buddies.size() < 10) {
            rec.jiban.buddies.add(0);
        }
    }

    private static PlayerRecord lineup(PlayerRecord.Hero... heroes) {
        PlayerRecord rec = new PlayerRecord();
        rec.account = "probe";
        rec.playerId = 1;
        for (PlayerRecord.Hero h : heroes) {
            rec.heroes.add(h);
        }
        rec.jiban.ensure();
        return rec;
    }

    private static PlayerRecord.Hero hero(int index, int level, int stars, int stage) {
        PlayerRecord.Hero h = new PlayerRecord.Hero();
        h.id = "wj-" + index;
        h.heroIndex = index;
        h.level = level;
        h.stars = stars;
        h.stage = stage;
        h.skill1 = 1;
        h.skill2 = 1;
        h.skill3 = 1;
        h.skill4 = 1;
        return h;
    }

    private static String attachSampleEquip(CultivateTables tables, PlayerRecord rec, PlayerRecord.Hero h,
                                            int level, int stars, int guhua) {
        for (CultivateTables.EquipCfg e : tables.allEquips()) {
            if (e == null || e.type != 1 || e.atkBase <= 0f) {
                continue;
            }
            PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
            eq.id = "eq-probe";
            eq.ori = e.ori;
            eq.owner = h.id;
            eq.level = level;
            eq.stars = stars;
            eq.guhua = guhua;
            rec.equipments.add(eq);
            System.out.println("  equip attached: " + e.ori + " lv" + level + " star" + stars + " gh" + guhua);
            return e.ori;
        }
        return null;
    }
}
