package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 竞技场日程配置：tables/daily-activity.json。
 * 只管 JJC 排名邮时刻、周重置、挑战跨度；月卡返钻等不在此文件。
 */
@Component
public class DailyActivityTables {
    private static final Logger log = LoggerFactory.getLogger(DailyActivityTables.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path file;
    private volatile FileConfig config = FileConfig.defaults();
    private long loadedMtime = -1L;

    public DailyActivityTables(SaoProperties props) {
        this.file = Paths.get(props.getTablesDir()).resolve("daily-activity.json");
    }

    @PostConstruct
    public void init() {
        reload(true);
    }

    public synchronized FileConfig current() {
        reload(false);
        return config;
    }

    public synchronized void reload(boolean force) {
        try {
            if (!Files.isRegularFile(file)) {
                FileConfig def = FileConfig.defaults();
                Files.createDirectories(file.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), def);
                config = def;
                loadedMtime = Files.getLastModifiedTime(file).toMillis();
                log.info("wrote default {}", file.toAbsolutePath());
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (!force && mtime == loadedMtime) {
                return;
            }
            FileConfig loaded = mapper.readValue(file.toFile(), FileConfig.class);
            if (loaded == null) {
                loaded = FileConfig.defaults();
            }
            loaded.ensure();
            config = loaded;
            loadedMtime = mtime;
            log.info("loaded daily-activity jjcHour={}:{} weeklyReset={} challengeMax={}",
                    config.jjcRankMail == null ? 21 : config.jjcRankMail.hour,
                    config.jjcRankMail == null ? 0 : config.jjcRankMail.minute,
                    config.jjcWeeklyReset == null || !config.jjcWeeklyReset.enabled ? "off"
                            : ("w" + config.jjcWeeklyReset.weekday + " " + config.jjcWeeklyReset.hour + ":"
                            + config.jjcWeeklyReset.minute),
                    config.jjcChallengeMaxAbove);
        } catch (IOException e) {
            log.warn("daily-activity read failed: {}", e.toString());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileConfig {
        @JsonProperty("_说明")
        public String note = FileConfig.NOTE;
        public JjcRankMail jjcRankMail = new JjcRankMail();
        public JjcWeeklyReset jjcWeeklyReset = new JjcWeeklyReset();
        /** 争霸排位日排名邮（默认 23:30，对齐排位结束 23:20 之后）。 */
        public JjcRankMail zbzRankMail = zbzRankMailDefault();
        /** 跨服排位日排名邮（mailType=20）。 */
        public JjcRankMail kfzPaiWeiRankMail = kfzPaiWeiRankMailDefault();
        /** 跨服巅峰排名邮（默认 21:35，对齐 finalEnd 21:30 之后）。 */
        public JjcRankMail kfzDfzRankMail = kfzDfzRankMailDefault();

        private static JjcRankMail zbzRankMailDefault() {
            JjcRankMail m = new JjcRankMail();
            m.hour = 23;
            m.minute = 30;
            return m;
        }

        private static JjcRankMail kfzPaiWeiRankMailDefault() {
            JjcRankMail m = new JjcRankMail();
            m.hour = 21;
            m.minute = 0;
            return m;
        }

        private static JjcRankMail kfzDfzRankMailDefault() {
            JjcRankMail m = new JjcRankMail();
            m.hour = 21;
            m.minute = 35;
            return m;
        }
        /** 挑战列表最多比自己高多少名。3 个对手落在这个窗口里。 */
        public int jjcChallengeMaxAbove = 50;

        public static final String NOTE =
                "竞技场/争霸/跨服日程。改完不必重启：公共调度启动补跑、每日 0 点、发邮钟点闸门会按文件时间戳重读。"
                        + " jjcRankMail：每天几点发 JJC 排名系统邮（mailType=1）。"
                        + " zbzRankMail：排位结束后发争霸排名邮（mailType=19，表 ZhengBaZhan 排位赛排名奖励）。"
                        + " kfzPaiWeiRankMail：跨服排位日排名邮（mailType=20，KuaFuZhanPrize 排位赛排名奖励）。"
                        + " kfzDfzRankMail：巅峰结束后发跨服巅峰排名邮（mailType=20，默认 21:35）。"
                        + " jjcWeeklyReset：每周几几点把玩家名次打回机器人之后。"
                        + " jjcChallengeMaxAbove：挑战列表最多比自己高多少名。"
                        + " 月卡/至尊返钻不在此文件。weekday 1=周一 … 7=周日。";

        public static FileConfig defaults() {
            FileConfig c = new FileConfig();
            c.ensure();
            return c;
        }

        public void ensure() {
            if (jjcRankMail == null) {
                jjcRankMail = new JjcRankMail();
            }
            if (jjcWeeklyReset == null) {
                jjcWeeklyReset = new JjcWeeklyReset();
            }
            if (zbzRankMail == null) {
                zbzRankMail = zbzRankMailDefault();
            }
            if (kfzPaiWeiRankMail == null) {
                kfzPaiWeiRankMail = kfzPaiWeiRankMailDefault();
            }
            if (kfzDfzRankMail == null) {
                kfzDfzRankMail = kfzDfzRankMailDefault();
            }
            if (jjcChallengeMaxAbove <= 0) {
                jjcChallengeMaxAbove = 50;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JjcRankMail {
        public boolean enabled = true;
        public int hour = 21;
        public int minute;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JjcWeeklyReset {
        public boolean enabled = true;
        /** 1=周一 … 7=周日 */
        public int weekday = 1;
        public int hour;
        public int minute;
    }
}
