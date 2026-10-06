package com.sao.fakeserver.table;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 活动页：本地奖池（七日 / 体力）+ 2601 全开 title 目录（对齐 Help huodongxitong_* 与 ActDetail prefab）。
 * 侧栏图标由客户端隐藏；此处 atalas/icon 可留空。
 */
@Component
public class ActivityTables {

    public static final String NAME_7DAY = "登录送壕礼";
    public static final String NAME_VP = "免费吃大餐";
    public static final String NAME_TEQUAN = "至尊福利";

    // NOTE（§6-10 死代码清理）：此处原有 ALL_TITLES / allTitles() / t(…) 三个成员，
    // 是全工程零调用的死目录（原「2601 全开 title」方案）。现役目录唯一来源是
    // ActExtCfg.titles()（读 tables/activity-ext.json，缺文件时 fallbackTitles() 兜底）。
    // 保留 TitleSpec 类型本身：ActExtCfg 与 PlayerDumpService.activityStatus 都在用。

    private final List<DayRow> poolLeft = new ArrayList<>();
    private final List<DayRow> poolRight = new ArrayList<>();
    private final List<VpRow> vpRows = new ArrayList<>();

    public static final class TitleSpec {
        public int type;
        public int subId;
        public String name;
    }

    @PostConstruct
    public void load() {
        poolLeft.clear();
        poolRight.clear();
        vpRows.clear();
        // 左角色（mainRoleIndex=1）→ 7Day 表1
        poolLeft.add(day(1, 50, 100000, award("GOODS3", 10, 0)));
        poolLeft.add(day(2, 80, 200000, award("GOODS3", 10, 0), award("EQ0023", 1, 0)));
        poolLeft.add(day(3, 100, 300000, award("GOODS3", 10, 0), award("EQ0042", 1, 0)));
        poolLeft.add(day(4, 150, 500000, award("GOODS3", 10, 0), award("TS102", 1, 0)));
        poolLeft.add(day(5, 180, 500000, award("GOODS3", 10, 0), award("TS403", 1, 0)));
        poolLeft.add(day(6, 200, 500000, award("GOODS3", 10, 0), award("TS104", 1, 0)));
        poolLeft.add(day(7, 250, 500000, award("GOODS3", 10, 3), award("EQ0034", 1, 1)));
        // 右角色（mainRoleIndex=2）→ 7Day 表2
        poolRight.add(day(1, 50, 100000, award("GOODS6", 10, 0)));
        poolRight.add(day(2, 80, 200000, award("GOODS6", 10, 0), award("EQ0023", 1, 0)));
        poolRight.add(day(3, 100, 300000, award("GOODS6", 10, 0), award("EQ0042", 1, 0)));
        poolRight.add(day(4, 150, 500000, award("GOODS6", 10, 0), award("TS102", 1, 0)));
        poolRight.add(day(5, 180, 500000, award("GOODS6", 10, 0), award("TS403", 1, 0)));
        poolRight.add(day(6, 200, 500000, award("GOODS6", 10, 0), award("TS104", 1, 0)));
        poolRight.add(day(7, 250, 500000, award("GOODS6", 10, 3), award("EQ0034", 1, 1)));
        vpRows.add(vp(1, 12, 0, 14, 0, 80));
        vpRows.add(vp(2, 18, 0, 20, 0, 80));
        vpRows.add(vp(3, 21, 0, 23, 0, 80));
    }

    public DayRow dayFor(int mainRoleIndex, int day) {
        List<DayRow> pool = mainRoleIndex == 2 ? poolRight : poolLeft;
        if (day < 1 || day > pool.size()) {
            return null;
        }
        return pool.get(day - 1);
    }

    public List<VpRow> vpRows() {
        return Collections.unmodifiableList(vpRows);
    }

    /** 当前时段可领类型；窗外为 0。 */
    public int activeVpType(LocalTime now) {
        for (VpRow r : vpRows) {
            if (!now.isBefore(r.begin) && now.isBefore(r.end)) {
                return r.type;
            }
        }
        return 0;
    }

    public VpRow vp(int type) {
        for (VpRow r : vpRows) {
            if (r.type == type) {
                return r;
            }
        }
        return null;
    }

    @SafeVarargs
    private static DayRow day(int day, int rmb, int gold, Award... awards) {
        DayRow r = new DayRow();
        r.day = day;
        r.rmb = rmb;
        r.gold = gold;
        for (Award a : awards) {
            if (a != null && a.ori != null && !a.ori.isEmpty() && !"0".equals(a.ori) && a.count > 0) {
                r.awards.add(a);
            }
        }
        return r;
    }

    private static Award award(String ori, int count, int stars) {
        Award a = new Award();
        a.ori = ori;
        a.count = count;
        a.stars = stars;
        return a;
    }

    private static VpRow vp(int type, int bh, int bm, int eh, int em, int awardVp) {
        VpRow r = new VpRow();
        r.type = type;
        r.begin = LocalTime.of(bh, bm);
        r.end = LocalTime.of(eh, em);
        r.awardVp = awardVp;
        return r;
    }

    public static final class DayRow {
        public int day;
        public int rmb;
        public int gold;
        public final List<Award> awards = new ArrayList<>();
    }

    public static final class Award {
        public String ori;
        public int count;
        public int stars;
    }

    public static final class VpRow {
        public int type;
        public LocalTime begin;
        public LocalTime end;
        public int awardVp;
    }
}
