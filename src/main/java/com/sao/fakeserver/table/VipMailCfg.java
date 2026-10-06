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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * vip 邮件内容表：vip每日礼包（mailType 8）/ vip提升奖励（mailType 9）的附件。
 *
 * <p>协议与文案都在包里，缺的只是「真服发什么」：
 * <ul>
 *   <li>文案：{@code Sys_MailConfig.txt} 第 8 行「vip每日礼包」、第 9 行「vip提升奖励」，
 *       两行正文都带 {@code {0}} 占位符（vip 等级）。</li>
 *   <li>枚举：{@code ESysMailTypeID.EMTID_VIP_AWARD_EVERY_DAY=8}、{@code EMTID_VIP_AWARD_ADD=9}
 *       （{@code pyfoot\tmp_msgdll\NetProto\ESysMailTypeID.cs:24-27}）。</li>
 *   <li>APK 里没有任何表给出这两封邮件的奖励内容 → 由本表提供；文件缺失 / {@code enabled=false} /
 *       该等级没配 = 不发信（不猜内容）。</li>
 * </ul>
 *
 * <p>{@code vip-mail.json}：{@code levels.<vip等级>.daily} 是每天一封（type 8）的附件，
 * {@code .add} 是该等级提升时一封（type 9）的附件（按第 9 行文案「提升vip带来的每日奖励补充」
 * 应填增量）。附件字段与其它系统邮一致。
 */
@Component
public class VipMailCfg {
    private static final Logger log = LoggerFactory.getLogger(VipMailCfg.class);
    private static final String FILE = "vip-mail.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;

    private boolean enabled;
    private final Map<Integer, Level> levels = new HashMap<>();

    public VipMailCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        reload(Paths.get(props.getTablesDir()).resolve(FILE));
    }

    /** 重新读取配置（启动、热更、测试）；文件缺失或解析失败 = 回到「不发信」。 */
    public void reload(Path file) {
        if (!Files.isRegularFile(file)) {
            enabled = false;
            levels.clear();
            log.warn("missing {} -> vip 邮件不发（协议本身可用）", file);
            return;
        }
        try {
            VipMailFile f = mapper.readValue(file.toFile(), VipMailFile.class);
            enabled = f != null && f.enabled;
            levels.clear();
            if (f != null && f.levels != null) {
                for (Map.Entry<String, Level> e : f.levels.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    int lvl;
                    try {
                        lvl = Integer.parseInt(e.getKey().trim());
                    } catch (NumberFormatException ex) {
                        log.warn("{} 等级键非法: {}", file, e.getKey());
                        continue;
                    }
                    if (lvl > 0) {
                        levels.put(Integer.valueOf(lvl), e.getValue());
                    }
                }
            }
            log.info("vip-mail enabled={} levels={}", Boolean.valueOf(enabled), Integer.valueOf(levels.size()));
        } catch (IOException e) {
            log.warn("load {} failed: {}", file, e.toString());
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 该 vip 等级的配置；没配返回 null。 */
    public Level level(int vip) {
        return levels.get(Integer.valueOf(vip));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VipMailFile {
        public boolean enabled;
        public Map<String, Level> levels;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Level {
        /** 每日礼包（mailType 8）。 */
        public Reward daily;
        /** 提升奖励（mailType 9）。 */
        public Reward add;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reward {
        public int gold;
        public int diamond;
        public int stamina;
        public int exp;
        public int jjcScore;
        public int wannengFragments;
        public int yingPo;
        public int moFaChen;
        public int brotherCoin;
        public int courageCoin;
        public List<Item> items;

        /** 全 0 且无有效道具 = 空奖励，不发信。 */
        public boolean isEmpty() {
            if (gold > 0 || diamond > 0 || stamina > 0 || exp > 0 || jjcScore > 0
                    || wannengFragments > 0 || yingPo > 0 || moFaChen > 0
                    || brotherCoin > 0 || courageCoin > 0) {
                return false;
            }
            if (items == null) {
                return true;
            }
            for (Item it : items) {
                if (it != null && it.ori != null && !it.ori.isEmpty()
                        && !"0".equals(it.ori) && it.count > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        public String ori;
        public int count;
        public int stars;
    }
}
