package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 日历签到表 GameData/QianDao.txt（客户端 SignInPropertyCfgMgr 同份）。
 */
@Component
public class SignInTables {
    private static final Logger log = LoggerFactory.getLogger(SignInTables.class);

    private final SaoProperties props;
    private final Map<Integer, DayRow> byDay = new HashMap<>();

    public SignInTables(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void load() throws Exception {
        Path dir = Paths.get(props.getTablesDir());
        Path file = dir.resolve("QianDao.txt");
        if (!Files.isRegularFile(file)) {
            log.warn("missing {}", file.toAbsolutePath());
            return;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        byDay.clear();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t");
            // # day goodsOri count rmb gold vipDouble
            if (cols.length < 7) {
                continue;
            }
            DayRow r = new DayRow();
            r.day = toInt(cols[1]);
            r.ori = cols[2] == null ? "0" : cols[2].trim();
            r.goodsCount = toInt(cols[3]);
            r.rmb = toInt(cols[4]);
            r.gold = toInt(cols[5]);
            r.vipDouble = toInt(cols[6]);
            if (r.day > 0) {
                byDay.put(Integer.valueOf(r.day), r);
            }
        }
        log.info("QianDao days={}", byDay.size());
    }

    public DayRow day(int dayNumber) {
        return byDay.get(Integer.valueOf(dayNumber));
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static final class DayRow {
        public int day;
        public String ori = "0";
        public int goodsCount;
        public int rmb;
        public int gold;
        public int vipDouble;
    }
}
