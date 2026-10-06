package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.fight.JinJieGrowType;
import com.sao.fakeserver.store.PlayerRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CultivateTablesTest {
    @Test
    void loadCultivateTables() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("WuJiangBaseAttri.ini"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        CultivateTables tables = new CultivateTables(props);
        tables.init();
        assertEquals(10, tables.starUpper());
        assertEquals(20, tables.maxSkillPoint());
        assertEquals(20, tables.buySkillPointCount());
        CultivateTables.HeroCfg black = tables.heroByIndex(18);
        assertNotNull(black);
        assertEquals("GOODS3", black.fragmentOri);
        assertNotNull(tables.heroByFragment("GOODS3"));
        assertTrue(tables.composeFragNeed(1) > 0);
        assertTrue(tables.upgradeCost(1, 4) > 0);
        assertNotNull(tables.equip("EQ0012"));
        assertNotNull(tables.equipRecipe("EQ0023"));
        assertNotNull(tables.starCost(4, 0));
        assertNotNull(tables.decomposeEquip(2, 0));
        assertTrue(tables.skillGold(1, 2) > 0);
        assertNotNull(tables.jinJie(black.jinJieType[0]));
        assertNotNull(tables.openCuiLian());
        // BaseScore：品级主序；同品级属性/被动可比
        PlayerRecord.Equipment green = new PlayerRecord.Equipment();
        green.ori = "EQ0012";
        green.level = 1;
        PlayerRecord.Equipment purple = new PlayerRecord.Equipment();
        purple.ori = "EQ0023";
        purple.level = 1;
        int g = tables.computeEquipBaseScore(green);
        int p = tables.computeEquipBaseScore(purple);
        assertTrue(p > g, "稀有应高于优质: p=" + p + " g=" + g);
        PlayerRecord.Equipment goldPassive = new PlayerRecord.Equipment();
        goldPassive.ori = "EQ0076";
        goldPassive.level = 1;
        int gp = tables.computeEquipBaseScore(goldPassive);
        assertTrue(gp > p, "金装+被动应更高: gp=" + gp + " p=" + p);
        assertTrue(tables.equip("EQ0076").beidongId > 0);
        CultivateTables.HeroCfg kirito = tables.heroByIndex(18);
        assertTrue(tables.canWear(kirito, tables.equip("EQ0012")));
        assertTrue(!tables.canWear(kirito, tables.equip("EQ0013")),
                "双手武器职与黑剑士武器职不同则不可穿");
        green.level = 80;
        green.stars = 5;
        assertTrue(tables.computeEquipBaseScore(green) > g, "强化应提高同分品级分数");

        // 器魂 9 列特效（EquipSoulList cols[21..29] = 客户端 EquipSoulProperty.cs:34-74 的 num2..num10）。
        // 客户端按「武器职 1→num2、2→num3、4→num4、3→num5；配件职 5→num7、6→num6、7→num8、8→P1=num10/P2=num9」
        // 错位写入 WeaponEffectParam1Add/PeiJianEffectParam1Add/PeiJianEffectParam2Add，服务端 parseEquipSoul 必须同口径。
        assertEquals(20f, tables.soulCfg(18).attrAdd[JinJieGrowType.WEAPON_EFFECT_P1], 0.001f,
                "hero18 武器职 1 → num2 = 20");
        assertEquals(40f, tables.soulCfg(31).attrAdd[JinJieGrowType.WEAPON_EFFECT_P1], 0.001f,
                "hero31 武器职 4 → num4（第 3 列）");
        assertEquals(20f, tables.soulCfg(26).attrAdd[JinJieGrowType.WEAPON_EFFECT_P1], 0.001f,
                "hero26 武器职 3 → num5（第 4 列）");
        assertEquals(20f, tables.soulCfg(34).attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1], 0.001f,
                "hero34 配件职 5 → num7（第 6 列）");
        assertEquals(20f, tables.soulCfg(21).attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1], 0.001f,
                "hero21 配件职 8 → P1 = num10（末列）");
        assertEquals(10f, tables.soulCfg(27).attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P2], 0.001f,
                "hero27 配件职 8 → P2 = num9（倒数第 2 列）");
    }
}
