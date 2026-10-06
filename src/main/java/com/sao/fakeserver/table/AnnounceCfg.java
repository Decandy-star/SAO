package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 系统公告（走马灯）配置：{@code tables/announcements.json}。
 * <p>
 * 对 S2C 30 {@code CCMsg_SysAnnouncement{type(1),regionType(2),content(3),level(4),contentkey(5)}}。
 * 客户端 {@code PlayGameState.OnNewSysAnnouncement}（out2 {@code ᝁ.cs:5253-5278}）的分派：
 * <ul>
 *   <li>{@code type} 缺省或 1 → 走马灯 {@code EN_ADD_SYSTEM_ANNOUNCEMENT}；</li>
 *   <li>{@code type == 5} → 喇叭（大厅滚动大字）；</li>
 *   <li>{@code type} 缺省或 2/3/4/5 → 聊天面板系统行 {@code EN_SYS_CHAT_ANNOUNCEMENT}。</li>
 * </ul>
 * 客户端 {@code SystemAnnouncement.cs:103} 只在 {@code regionType != 1} 或玩家在主城时入队，
 * 所以 {@code regionType} 建议 0(ALL)。
 */
@Component
public class AnnounceCfg {
    private static final Logger log = LoggerFactory.getLogger(AnnounceCfg.class);
    private static final String FILE = "announcements.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;

    /** 每次登录最多推几条，避免刷屏（走马灯 1.5s 一条）。0=不推。 */
    private int maxPerLogin = 3;
    private List<Item> items = new ArrayList<>();

    public AnnounceCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.info("missing {} — 不推系统公告", file);
            return;
        }
        try {
            Root r = mapper.readValue(file.toFile(), Root.class);
            if (r == null) {
                return;
            }
            maxPerLogin = r.maxPerLogin;
            List<Item> out = new ArrayList<>();
            if (r.announcements != null) {
                for (Item it : r.announcements) {
                    if (it != null && it.content != null && !it.content.trim().isEmpty()) {
                        out.add(it);
                    }
                }
            }
            items = out;
            log.info("announcements={} maxPerLogin={}", Integer.valueOf(items.size()), Integer.valueOf(maxPerLogin));
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    /** 登录时要推送的公告（已按 maxPerLogin 截断）。 */
    public List<Item> loginItems() {
        if (maxPerLogin <= 0 || items.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(items.subList(0, Math.min(maxPerLogin, items.size())));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public int maxPerLogin = 3;
        public List<Item> announcements = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        public String content = "";
        /** 0=ALL 1=Sys 2=Chat 3=Chat_Union 4=Chat_Whisper 5=Chat_Laba。 */
        public int type = 1;
        /** 0=ALL 1=MainCity 2=FB 3=KUAFU。 */
        public int regionType;
        public int level;
        public int contentkey;
    }
}
