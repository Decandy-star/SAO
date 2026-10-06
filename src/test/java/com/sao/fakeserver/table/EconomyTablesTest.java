package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

class EconomyTablesTest {
    @Test
    void loadEconomyTables() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("BuyTiLi.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        EconomyTables tables = new EconomyTables(props);
        tables.init();
        assertNotNull(tables.buyTiLi(1));
        assertTrue(tables.buyTiLi(1).cost > 0);
        assertNotNull(tables.buyJinBi(1));
        assertNotNull(tables.pay(8));
        assertTrue(tables.pay(8).grant(true) >= 60);
        assertNotNull(tables.shop(1));
        assertTrue(tables.shop(1).fieldCount >= 1);
        assertNotNull(tables.exchange("ZBSP1"));
        assertNotNull(tables.leap(4, 1));
        assertNotNull(tables.resetStar(4, 0));
        assertTrue(tables.jingLianStoneExp("JLBS01") > 0);
        assertTrue(tables.sellGold("GOODS104", 1) > 0);
        assertNotNull(tables.xiLian().stoneOri);
        assertArrayEquals(new int[] {9, 12, 18, 21}, tables.shop(1).refreshHours);
        assertEquals(3, tables.shop(1).freeRefresh);
        assertEquals(3, tables.shop(5).freeRefresh);
        assertEquals(15, tables.shop(2).openLevel);
        assertEquals(1, tables.buyTiLiMaxTimes(0));
        assertEquals(4, tables.buyJinBiMaxTimes(0));
        assertEquals(3, tables.buyTiLiMaxTimes(60));
        int[] hours = tables.shop(1).refreshHours;
        LocalDateTime t9 = LocalDateTime.of(2026, 10, 3, 9, 0);
        int slot9 = EconomyTables.latestPassedShopSlot(hours, t9);
        long now930 = LocalDateTime.of(2026, 10, 3, 9, 30).atZone(EconomyTables.SHOP_ZONE).toInstant().toEpochMilli();
        assertFalse(EconomyTables.shopDueSysRefresh(hours, slot9, now930));
        long now12 = LocalDateTime.of(2026, 10, 3, 12, 0).atZone(EconomyTables.SHOP_ZONE).toInstant().toEpochMilli();
        assertTrue(EconomyTables.shopDueSysRefresh(hours, slot9, now12));
        long now8 = LocalDateTime.of(2026, 10, 3, 8, 0).atZone(EconomyTables.SHOP_ZONE).toInstant().toEpochMilli();
        int slotPrev21 = EconomyTables.latestPassedShopSlot(hours, LocalDateTime.of(2026, 10, 3, 8, 0));
        assertTrue(EconomyTables.shopDueSysRefresh(hours, slotPrev21, now8 + 3600_000L));
        assertTrue(tables.shopOfferCountAtLevel(1, 10) > 0);
        assertTrue(tables.shopOfferCountAtLevel(2, 15) > 0);
        assertTrue(tables.shopOfferCountAtLevel(3, 5) > 0);
        assertTrue(tables.shopOfferCountAtLevel(4, 10) >= 8,
                "10 级刚开公会店时可选货必须够铺满 8 格，实际 " + tables.shopOfferCountAtLevel(4, 10));
        assertEquals(tables.shopOffers(5).size(), tables.shopOfferCountAtLevel(5, 12));
        assertTrue(tables.shopOffers(1).size() >= 8);
        assertTrue(tables.shopOffers(2).size() >= 8);
        assertTrue(tables.shopOffers(5).size() >= 8);
        assertTrue(tables.randomBoxPoolSize() >= 50);
        assertEquals(2, tables.choiceCells("BX276").size());
        assertEquals(4, tables.choiceCells("BX285").size());
        assertNotNull(tables.choiceCell("BX276", 1));
        assertEquals("GOODS145", tables.choiceCell("BX276", 1).goodId);
    }

    /**
     * APK 真表（tables\TeQuanCard.txt，与 decompiled\gametext-tables\TeQuanCard.txt 一致）：两张卡
     * 0 基列 5/6（购买立即返物品/数量）与列 8/9（每日返物品/数量）**全是 0** → 解析必须归一成
     * 「空 ori + 0 数量」，即真表下买卡/每日领都不发任何物品（{@code PayService.grantCard}/
     * {@code onTeQuanDaily} 用 ori 空或数量 ≤0 判定不发）。
     */
    @Test
    void shippedTeQuanCardHasNoGoodsColumns() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("TeQuanCard.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        EconomyTables tables = new EconomyTables(props);
        tables.init();
        for (int type : new int[] {2, 4}) {
            EconomyTables.TeQuanRow card = tables.teQuan(type);
            assertNotNull(card, "TeQuanCard.txt 必须有 type=" + type + " 的卡");
            assertEquals("", card.buyGoodsOri, "真表列 5 是 \"0\" → 必须归一成空串（type=" + type + "）");
            assertEquals(0, card.buyGoodsCount, "真表列 6 是 \"0\" → 数量必须是 0（type=" + type + "）");
            assertEquals("", card.dailyGoodsOri, "真表列 8 是 \"0\" → 必须归一成空串（type=" + type + "）");
            assertEquals(0, card.dailyGoodsCount, "真表列 9 是 \"0\" → 数量必须是 0（type=" + type + "）");
        }
    }

    /**
     * 特权卡物品列解析：把列 5/6/8/9 填上后必须解析进 {@code TeQuanRow} 的 4 个字段。
     *
     * <p>在 {@code target/test-data} 下的**整目录副本**上改表再解析，仓库里的 tables\TeQuanCard.txt
     * 一个字节都不动；同一份临时表里没改的 type4 行仍必须是「空 + 0」，证明这是表驱动而不是特例。
     */
    @Test
    void teQuanCardParsesGoodsColumns() throws IOException {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("TeQuanCard.txt"))) {
            return;
        }
        Path tmp = copyTablesDir(dir, Paths.get("target/test-data/tables-tequan-parse"));
        writeTeQuanGoodsColumns(tmp.resolve("TeQuanCard.txt"), 2, "GOODS3", 2, "GOODS4", 5);

        SaoProperties props = new SaoProperties();
        props.setTablesDir(tmp.toAbsolutePath().toString());
        EconomyTables tables = new EconomyTables(props);
        tables.init();

        EconomyTables.TeQuanRow month = tables.teQuan(2);
        assertNotNull(month, "临时表里必须有 type2 月卡");
        assertEquals("GOODS3", month.buyGoodsOri, "列 5 必须解析成立即返物品 GOODS3");
        assertEquals(2, month.buyGoodsCount, "列 6 必须解析成数量 2");
        assertEquals("GOODS4", month.dailyGoodsOri, "列 8 必须解析成每日返物品 GOODS4");
        assertEquals(5, month.dailyGoodsCount, "列 9 必须解析成数量 5");
        assertEquals(250, month.buyDiamond, "改物品列不能动列 4 立即返钻 250");
        assertEquals(120, month.dailyDiamond, "改物品列不能动列 7 每日返钻 120");

        EconomyTables.TeQuanRow zhiZun = tables.teQuan(4);
        assertNotNull(zhiZun, "临时表里必须有 type4 至尊卡");
        assertEquals("", zhiZun.buyGoodsOri, "没改的 type4 行仍是空 ori");
        assertEquals(0, zhiZun.buyGoodsCount, "没改的 type4 行数量仍是 0");
        assertEquals("", zhiZun.dailyGoodsOri, "没改的 type4 行每日 ori 仍是空");
        assertEquals(0, zhiZun.dailyGoodsCount, "没改的 type4 行每日数量仍是 0");
    }

    /**
     * 公会店（type4）货单必须来自 {@code GoodsList.txt} 的权威列，而不是按品质猜：
     * <ul>
     *   <li>col10「产出位置 = 4 公会商店产出」的 4 件 —— 真服玩家描述的「限定英雄碎片 + 精灵翅膀碎片」；</li>
     *   <li>col9「道具显示位置 = 4 进阶」的 96 件 —— 真服描述的「英雄进阶材料 / 进阶石」；</li>
     *   <li>真服点名、APK 标在别处的铸铁/星陨石/锻魂石、时光石 Ⅰ–Ⅲ、经验药水、金币包。</li>
     * </ul>
     * 价格一律用 col8「公会拍卖基础价格（勇气币）」（APK 只有这一列公会货币基价）。
     */
    @Test
    void guildShopPoolComesFromGoodsListColumns() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("GoodsList.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        EconomyTables tables = new EconomyTables(props);
        tables.init();

        // col8 公会货币基价 + col2 显示名
        assertEquals(40, tables.auctionPrice("GOODS118"), "GOODS118 雷肯 col8 = 40");
        assertEquals(22, tables.auctionPrice("ZBSP10"), "ZBSP10 稀有翅膀碎片 col8 = 22");
        assertEquals(14, tables.auctionPrice("GOODS42"), "GOODS42 格斗印记Ⅰ col8 = 14");
        assertEquals(10, tables.auctionPrice("ZBSX01"), "ZBSX01 铸铁 col8 = 10");
        assertEquals("雷肯", tables.goodsDisplayName("GOODS118"), "col2 是显示名");
        assertEquals(0, tables.auctionPrice("NO_SUCH_ORI"), "查不到 = 0");

        // col10 产出位置 = 4 公会商店产出，全表恰好这 4 件
        for (String ori : new String[] {"GOODS118", "SP035", "ZBSP10", "ZBSP21"}) {
            assertEquals(4, tables.goodsSource(ori), ori + " col10 必须是 4（公会商店产出）");
        }
        assertTrue(tables.goodsSource("GOODS42") != 4, "GOODS42 是进阶材料，来源不是公会店");

        List<EconomyTables.ShopOffer> guild = tables.shopOffers(4);
        assertTrue(guild.size() >= 60, "公会店货单必须成规模，实际 " + guild.size());
        for (String ori : new String[] {"GOODS118", "SP035", "ZBSP10", "ZBSP21", // 产出位置=4
                "GOODS42", "GOODS66", "ZBSX01", "ZBSX02", "ZBSX03", // 进阶材料 / 铸铁类
                "TS101", "TS201", "TS301", "TS401"}) {               // 时光石（TS<色>0<档>，都是Ⅰ档）
            EconomyTables.ShopOffer hit = findOffer(guild, ori);
            assertNotNull(hit, "公会店必须有 " + ori);
            assertEquals(tables.auctionPrice(ori), hit.price,
                    ori + " 的兄弟币售价必须等于 GoodsList col8");
        }
        // 时光石编码是 TS + 颜色 + 0 + 档位：TS104 = 红色时光石Ⅳ（col8 = 274），Ⅳ 档起不进公会店
        assertTrue(tables.goodsSource("TS104") != 4, "TS104 来源不是公会店");
        assertNull(findOffer(guild, "TS104"), "时光石Ⅳ（TS104）不进公会店");
        assertNull(findOffer(guild, "TS508"), "时光石Ⅷ（TS508）不进公会店");

        // 进阶材料按 col8 抬门槛：Ⅰ 级 10 级可买，Ⅸ 级要 45 级
        EconomyTables.ShopOffer one = findOffer(guild, "GOODS42");
        EconomyTables.ShopOffer nine = findOffer(guild, "GOODS291");
        assertNotNull(one, "必须有格斗印记Ⅰ");
        assertNotNull(nine, "必须有奥术晶石Ⅸ GOODS291");
        assertTrue(nine.minLevel > one.minLevel,
                "高阶进阶材料门槛必须更高：Ⅰ=" + one.minLevel + " Ⅸ=" + nine.minLevel);
        assertTrue(one.weight > nine.weight,
                "低阶进阶材料权重必须更高：Ⅰ=" + one.weight + " Ⅸ=" + nine.weight);
    }

    /**
     * 「大量 / 超量 / 随机」7 个箱的产出是**按养成曲线规划的估算值**（用户 m02388 指令），而不是从表里
     * 读出来的数字 —— 本用例把它们钉住，防止后续被误改，并把曲线锚点写在这里：
     * <ul>
     *   <li>金币在游戏里几乎只用于装备升级，{@code tables\EquipmentUpgrade.txt} 单次消耗实测
     *       Lv20=20000 / Lv30=60000 / Lv50=188000 / Lv70=380000 / Lv79=487280（满级 80）
     *       ⇒ 约定「一个大额金币箱 ≈ 对应等级段的 1 次（最多 1.5 次）装备升级费用」；</li>
     *   <li>比价：{@code tables\BuyJinBi.txt:2}「2 钻石 → 20000 金币」⇒ 1 钻石 = 10000 金币；</li>
     *   <li>系列顺延：金币箱 {@code HTJB1..25} = 260000 + 20000×(n−1)、碎片箱 {@code HTSP1..25}
     *       = 600 + 100×(n−1)（{@code GoodsList.txt:316-340} / {@code :290-314}）⇒ 第 26 号接着走。</li>
     * </ul>
     * 真服抓包到手后按 {@code docs\PROTOCOL_GAP_REPORT.md} §8.4 的表格逐值替换即可。
     */
    @Test
    void plannedBigMoneyBoxesFollowGrowthCurve() {
        Path dir = Paths.get("tables");
        if (!Files.isRegularFile(dir.resolve("GoodsList.txt"))) {
            return;
        }
        SaoProperties props = new SaoProperties();
        props.setTablesDir(dir.toString());
        EconomyTables tables = new EconomyTables(props);
        tables.init();

        // 系列顺延：第 26 号必须与第 25 号在同一条等差线上
        assertEquals(260000 + 20000 * 24, tables.boxCurrency("HTJB25").gold, "HTJB25 是系列第 25 号");
        assertEquals(260000 + 20000 * 25, tables.boxCurrency("HTJB26").gold, "HTJB26 必须顺延 HTJB 等差线");
        assertEquals(600 + 100 * 24, tables.boxCurrency("HTSP25").wnsp, "HTSP25 是系列第 25 号");
        assertEquals(600 + 100 * 25, tables.boxCurrency("HTSP26").wnsp, "HTSP26 必须顺延 HTSP 等差线");

        // 7 个规划值（改这里必须同步 docs\PROTOCOL_GAP_REPORT.md §8.4）
        assertEquals(760000, tables.boxCurrency("HTJB26").gold, "百战金币宝箱：≈Lv79 一次装备升级");
        assertEquals(3100, tables.boxCurrency("HTSP26").wnsp, "百战碎片宝箱：系列顺延");
        assertEquals(200000, tables.boxCurrency("TWBX003").gold, "百战金币宝箱1：百层塔常规层中段");
        assertEquals(400000, tables.boxCurrency("TWBX004").gold, "百战金币宝箱2：上一层 2 倍");
        assertEquals(600000, tables.boxCurrency("BX158").gold, "珍稀金币宝箱：夹在 BX169 40万 与 BX148 100万 之间");
        assertEquals(288888, tables.boxCurrency("BX237").gold, "红包：新年兑换页最高档");

        // 结构关系：TWBX004 写「超量」⇒ 必须是 TWBX003 的 2 倍
        assertEquals(2 * tables.boxCurrency("TWBX003").gold, tables.boxCurrency("TWBX004").gold,
                "TWBX004「超量」必须严格是 TWBX003 的 2 倍");
        // 全表属性=10 的箱子都不给钻石（BoxCurrency 的 javadoc 约定）
        for (String ori : new String[] {"HTJB26", "HTSP26", "TWBX003", "TWBX004", "BX158", "BX237"}) {
            assertTrue(tables.boxCurrency(ori).any(), ori + " 必须是货币箱（any() 为真）");
            assertEquals(0, tables.boxCurrency(ori).diamond, ori + " 属性=10 的箱子一律不给钻石");
        }

        // BX109 结衣魂石宝箱 = 英雄碎片（混合箱）+ 英魄（说明写「或」，假服机制只能「都给」）
        EconomyTables.BoxCurrency jieYi = tables.boxCurrency("BX109");
        assertNotNull(jieYi, "结衣魂石宝箱必须已登记");
        assertEquals("SP039", jieYi.alsoItemOri, "主产出是结衣碎片");
        assertEquals(2, jieYi.alsoItemCount, "结衣碎片 ×2");
        assertEquals(200, jieYi.yingPo, "英魄 200");
        assertEquals(0, jieYi.gold, "魂石箱不给金币");

        // 百层塔层 31+ 的两个材料箱（HundredTowerList.txt）：说明列自带区间 ⇒ 取区间下限
        EconomyTables.BoxCurrency catalyst = tables.boxCurrency("BX240");
        assertNotNull(catalyst, "精炼催化剂宝箱必须已登记（百层塔层 31+ 唯一产出点）");
        assertEquals("JLFY02", catalyst.alsoItemOri, "BX240 说明「打开后获得4~5个精炼催化剂」");
        assertEquals(4, catalyst.alsoItemCount, "取区间下限 4");
        assertTrue(catalyst.any(), "纯道具混合箱也必须 any() 为真，否则会被当成未登记箱走随机池");
        EconomyTables.BoxCurrency crystal = tables.boxCurrency("BX241");
        assertNotNull(crystal, "精纯结晶宝箱必须已登记（百层塔层 31+ 唯一产出点）");
        assertEquals("JLFY03", crystal.alsoItemOri, "BX241 说明「打开后获得1~2个精纯结晶」");
        assertEquals(1, crystal.alsoItemCount, "取区间下限 1");
        assertTrue(crystal.any(), "纯道具混合箱也必须 any() 为真");

        // mul 只乘货币：道具由开箱循环逐次发（DungeonService.onOpenBaoXiang），
        // 若这里也乘 count，十连就变成 count²（BX109 十连 200 片而不是 20 片）
        assertEquals(1, crystal.mul(10).alsoItemCount, "mul 不得放大 alsoItemCount");
        assertEquals(0, crystal.mul(10).yingPo, "纯道具箱本来就没有货币");
        assertEquals(2000, tables.boxCurrency("BX109").mul(10).yingPo, "货币仍要按十连 ×10");
    }

    private static EconomyTables.ShopOffer findOffer(List<EconomyTables.ShopOffer> list, String ori) {
        for (EconomyTables.ShopOffer o : list) {
            if (ori.equals(o.ori)) {
                return o;
            }
        }
        return null;
    }

    /** 把 tables/ 整目录复制到 dest（先清空 dest），供「改表再解析」用例用。 */
    private static Path copyTablesDir(Path src, Path dest) throws IOException {
        if (Files.isDirectory(dest)) {
            Files.walk(dest).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(dest);
        List<Path> files = new ArrayList<>();
        Files.walk(src).filter(Files::isRegularFile).forEach(files::add);
        for (Path f : files) {
            Path target = dest.resolve(src.relativize(f).toString());
            Files.createDirectories(target.getParent());
            Files.copy(f, target);
        }
        return dest;
    }

    /** 改写 TeQuanCard.txt 里 type 那一行的 4 个物品列（0 基 5/6/8/9），其余列原样保留。 */
    private static void writeTeQuanGoodsColumns(Path file, int type,
            String buyOri, int buyCount, String dailyOri, int dailyCount) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean patched = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length < 10 || !String.valueOf(type).equals(cols[1])) {
                continue;
            }
            cols[5] = buyOri;
            cols[6] = String.valueOf(buyCount);
            cols[8] = dailyOri;
            cols[9] = String.valueOf(dailyCount);
            lines.set(i, String.join("\t", cols));
            patched = true;
        }
        assertTrue(patched, "TeQuanCard.txt 必须有 type=" + type + " 那张卡");
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
