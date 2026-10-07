package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTablesTest {
    @Test
    void hundredTowerRewardsComeFromTable() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("HundredTowerList.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        GameTables tables = new GameTables(props);
        tables.init();
        // 表「金币」列 50 层全 0 ⇒ 百层塔不发当场金币，金币收益在表里发的 TWBX 金币箱内
        for (int layer = 1; layer <= tables.bctMaxLayer(); layer++) {
            assertEquals(0, tables.bctReward(layer).gold, "层 " + layer + " 的 HundredTowerList 金币列应为 0");
        }
        // 物品_1/2.. 逐层照抄：单数层 TWBX003+ZBSX01×10，偶数层 TWBX001，每 5 层加档
        assertEquals("TWBX003x1,ZBSX01x10", join(tables.bctReward(1).goods));
        assertEquals("TWBX001x1", join(tables.bctReward(2).goods));
        assertEquals("TWBX004x1,TWBX002x1,ZBSX01x20,ZBSX02x5", join(tables.bctReward(5).goods));
        // 层 31 起带精炼催化剂箱/魔法尘×10，层 50 才带精纯结晶箱
        assertEquals("TWBX003x1,ZBSX01x10,BX240x1,BX242x1", join(tables.bctReward(31).goods));
        assertEquals("TWBX004x1,TWBX002x1,ZBSX01x20,ZBSX02x5,BX241x1", join(tables.bctReward(50).goods));
        // 表外层号（缺行）回退到「产品规划」档：金币 + HTE 装备箱，保证不空手
        GameTables.ResourceFbPlan fallback = tables.bctReward(999);
        assertTrue(fallback.gold > 0 && !fallback.goods.isEmpty(), "表外层号必须有兜底奖励");
        assertEquals("HTE3x1", join(fallback.goods));
    }

    private static String join(java.util.List<GameTables.GoodsDrop> drops) {
        StringBuilder sb = new StringBuilder();
        for (GameTables.GoodsDrop g : drops) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(g.ori).append('x').append(g.count);
        }
        return sb.toString();
    }

    @Test
    void loadFromTablesDir() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("PlayerLevelInfo.ini"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        GameTables tables = new GameTables(props);
        tables.init();
        assertTrue(tables.nextPlayerExp(10) > 0);
        assertTrue(tables.nextWjExp(10) > 0);
        assertFalse(tables.robots().isEmpty());
        GameTables.RankPrizeRow top = tables.prizeForRank(1);
        GameTables.RankPrizeRow last = tables.prizeForRank(5001);
        assertTrue(top != null && top.jjcScore >= 1200);
        assertTrue(last != null && last.jjcScore >= 200);
        GameTables.DropRow row = tables.drop(12, 1);
        if (row != null) {
            assertTrue(row.gold >= 0);
            row.roll(new Random(1), true);
        }
        assertTrue(tables.draw().jbOnce > 0);
    }

    /**
     * 主线进本/扫荡要用的三张表：RegionList（体力）、ChapterList（章节开启等级）、
     * GlobalSetup_CH（精英总开关/扫荡开启关/每日次数上限）。值取实表（UTF-8 + TAB）。
     */
    @Test
    void mainFbGatesComeFromChapterAndGlobalSetupTables() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("RegionList.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        props.setGameText("../Assets/Resources/GameText.txt");
        GameTables tables = new GameTables(props);
        tables.init();
        // RegionList：col6 普通 / col7 精英（1003 = 1-3，实表 8 / 16）
        assertEquals(8, tables.regionEnergy(1003, 1));
        assertEquals(16, tables.regionEnergy(1003, 2));
        // ChapterList：前 7 章开启等级 1，第 8 章 18 … 第 28 章 80（共 28 章）
        assertEquals(1, tables.chapterOpenLevel(1));
        assertEquals(1, tables.chapterOpenLevel(7));
        assertEquals(18, tables.chapterOpenLevel(8));
        assertEquals(80, tables.chapterOpenLevel(28));
        // GlobalSetup_CH：token66 精英总开关、token67 扫荡开启关
        assertEquals(3010, tables.eliteUnlockRegion());
        assertEquals(2001, tables.normalFbOpenSaoDang());
        // 每日次数上限：第三/六/九/十关（普通 10、精英 3），其他关号 0
        assertEquals(10, tables.mainFbPlayLimit(1, 3));
        assertEquals(10, tables.mainFbPlayLimit(1, 10));
        assertEquals(3, tables.mainFbPlayLimit(2, 3));
        assertEquals(3, tables.mainFbPlayLimit(2, 10));
        assertEquals(0, tables.mainFbPlayLimit(1, 1));
    }

    /** VipCfg：十连扫荡门（col5）与买副本次数上限（col7/col8，普通/精英）。 */
    @Test
    void vipSweepAndResetLimitsComeFromVipCfg() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("VipCfg.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        EconomyTables economy = new EconomyTables(props);
        economy.init();
        // 本仓表：VIP0/VIP1 不给十连扫荡，VIP2 起给（vip() 按累计钻石取档：VIP1=60、VIP2=300）
        assertFalse(economy.openShaoDang10Ci(0));
        assertFalse(economy.openShaoDang10Ci(60));
        assertTrue(economy.openShaoDang10Ci(300));
        // 买副本次数日上限：VIP0=0/0、VIP1=1/1、VIP2=2/2
        assertEquals(0, economy.fbResetMaxCount(0));
        assertEquals(0, economy.jyFbResetMaxCount(0));
        assertEquals(1, economy.fbResetMaxCount(60));
        assertEquals(1, economy.jyFbResetMaxCount(60));
        assertEquals(2, economy.fbResetMaxCount(300));
        assertEquals(2, economy.jyFbResetMaxCount(300));
    }
}
