package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日常 / 一次性任务表。列序对照客户端 DailyTaskPropertyCfg / OnceTaskPropertyCfg。
 */
@Component
public class TaskTables {
    private static final Logger log = LoggerFactory.getLogger(TaskTables.class);
    private static final String[] TABLE_FILES = {
            "DailyTaskConfig.txt",
            "OnceTaskConfig.txt",
    };

    private final SaoProperties props;
    private final Map<Integer, DailyCfg> daily = new LinkedHashMap<>();
    private final Map<Integer, OnceCfg> once = new LinkedHashMap<>();

    public TaskTables(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("cannot create tables dir {}", dir);
        }
        ensureTables(dir);
        parseDaily(readTable(dir, "DailyTaskConfig.txt"));
        parseOnce(readTable(dir, "OnceTaskConfig.txt"));
        log.info("task tables daily={} once={}", daily.size(), once.size());
    }

    public Collection<DailyCfg> dailyAll() {
        return daily.values();
    }

    public DailyCfg daily(int id) {
        return daily.get(Integer.valueOf(id));
    }

    public Collection<OnceCfg> onceAll() {
        return once.values();
    }

    public OnceCfg once(int id) {
        return once.get(Integer.valueOf(id));
    }

    private void parseDaily(String text) {
        daily.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 17) {
                continue;
            }
            DailyCfg r = new DailyCfg();
            r.id = toInt(cols, 1);
            r.levelLimit = toInt(cols, 6);
            r.guanKaLimit = toInt(cols, 7);
            r.type = toInt(cols, 8);
            r.finishCount = Math.max(1, toInt(cols, 9));
            r.exp = toInt(cols, 10);
            r.gold = toInt(cols, 11);
            r.rmb = toInt(cols, 12);
            r.goods1 = col(cols, 13);
            r.cnt1 = toInt(cols, 14);
            r.goods2 = col(cols, 15);
            r.cnt2 = toInt(cols, 16);
            if (r.id > 0) {
                daily.put(Integer.valueOf(r.id), r);
            }
        }
    }

    private void parseOnce(String text) {
        once.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 20) {
                continue;
            }
            OnceCfg r = new OnceCfg();
            r.id = toInt(cols, 1);
            r.levelLimit = toInt(cols, 6);
            r.guanKaLimit = toInt(cols, 7);
            r.preTaskId = toInt(cols, 8);
            r.type = toInt(cols, 9);
            r.finishCount = Math.max(1, toInt(cols, 10));
            r.customParam = toInt(cols, 11);
            r.notifyMail = toInt(cols, 12);
            r.name = col(cols, 2);
            r.exp = toInt(cols, 13);
            r.gold = toInt(cols, 14);
            r.rmb = toInt(cols, 15);
            r.goods1 = col(cols, 16);
            r.cnt1 = toInt(cols, 17);
            r.goods2 = col(cols, 18);
            r.cnt2 = toInt(cols, 19);
            if (r.id > 0) {
                once.put(Integer.valueOf(r.id), r);
            }
        }
    }

    private void ensureTables(Path dir) {
        boolean missing = false;
        for (String name : TABLE_FILES) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return;
        }
        GameTextLoader loader = GameTextLoader.load(props.getGameText());
        if (loader.isEmpty()) {
            return;
        }
        for (String name : TABLE_FILES) {
            String text = loader.get(name);
            if (text == null) {
                continue;
            }
            try {
                Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("write {} failed: {}", name, e.toString());
            }
        }
    }

    private static String readTable(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String[]> hashRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length > 1 && "#".equals(cols[0])) {
                rows.add(cols);
            }
        }
        return rows;
    }

    private static String col(String[] cols, int i) {
        if (i >= cols.length || cols[i] == null) {
            return "0";
        }
        return cols[i];
    }

    private static int toInt(String[] cols, int i) {
        if (i >= cols.length) {
            return 0;
        }
        try {
            return Integer.parseInt(cols[i].trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Float.parseFloat(cols[i].trim());
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    public static final class DailyCfg {
        public int id;
        public int levelLimit;
        public int guanKaLimit;
        public int type;
        public int finishCount = 1;
        public int exp;
        public int gold;
        public int rmb;
        public String goods1 = "0";
        public int cnt1;
        public String goods2 = "0";
        public int cnt2;
    }

    public static final class OnceCfg {
        public int id;
        public int levelLimit;
        public int guanKaLimit;
        public int preTaskId;
        public int type;
        public int finishCount = 1;
        public int customParam;
        /** OnceTaskConfig「接受任务是否发送通知邮件」：非 0 则解锁时发 mailType=18。 */
        public int notifyMail;
        public String name = "";
        public int exp;
        public int gold;
        public int rmb;
        public String goods1 = "0";
        public int cnt1;
        public String goods2 = "0";
        public int cnt2;
    }
}
