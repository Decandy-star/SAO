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

class GachaPoolTest {
    @Test
    void poolFollowsPlan() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("gacha-pool.json"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        CultivateTables cultivate = new CultivateTables(props);
        cultivate.init();
        GachaPool pool = new GachaPool(props, cultivate);
        pool.init();
        assertTrue(pool.goldSize() >= 50, "gold pool should be equip frags + souls");
        assertTrue(pool.diamondSize() >= 80, "diamond pool should be equips + heroes");
        int goldW = 0;
        int diaW = 0;
        for (int i = 0; i < 20; i++) {
            GachaPool.Entry g = pool.roll(false, new Random(i));
            goldW += Math.max(1, g.weight);
            assertEquals("item", g.type, "gold pool must not grant heroes");
            assertFalse(g.isHero());
            GachaPool.Entry d = pool.roll(true, new Random(i + 100));
            diaW += Math.max(1, d.weight);
            assertTrue(d.isHero() || d.isEquip() || "item".equals(d.type));
            if (d.isHero()) {
                assertTrue(cultivate.isPlayableHero(d.heroIndex),
                        "diamond hero must be playable, got " + d.heroIndex);
            }
        }
        assertEquals(20, goldW);
        assertEquals(20, diaW);
    }
}
