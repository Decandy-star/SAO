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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 礼包码 / 激活码配置：{@code tables/gift-code.json}。缺文件 = 两个功能都不开放
 * （礼包码入口 {@code CMsgDetailPlayerInfo.EnableLiBaoMa(field 86)} 也就保持 false）。
 * <p>
 * 协议依据：
 * <ul>
 *   <li>C2S 20 {@code CMsgRequestGiftPack{key}} → S2C 20 {@code CMsgGiftPackRet{ret}}；
 *       {@code GIFT_PACK_RET}：0=OK 1=OutOfDate 2=KeyUsed 3=AlreadyGet。</li>
 *   <li>C2S 30 {@code CCMsg_Account_Check_JiHuoMa{account,jihuoma,deviceid}} → 失败走
 *       S2C 22 {@code CMsg_Account_Check_JiHuoMa_Ret{ret}}（1=无效 2=已被使用）；成功没有专用回包。</li>
 * </ul>
 * 「KeyUsed」需要全服次数上限；假服没有跨账号的全局次数存档，故本表只实现「每号一次」，
 * 不会回 2（见 docs/PROTOCOL_GAP_REPORT.md）。
 */
@Component
public class GiftCodeCfg {
    private static final Logger log = LoggerFactory.getLogger(GiftCodeCfg.class);
    private static final String FILE = "gift-code.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;

    /** 是否把 detail field 86(EnableLiBaoMa) 置真 —— 即是否在用户中心放出「礼包码」入口。 */
    private boolean enableLibaoMa = true;
    /** 登录时是否推送 S2C 21 让客户端弹一次激活码输入 UI（每号只弹一次）。 */
    private boolean promptActivationOnLogin = true;

    private final Map<String, Code> giftCodes = new LinkedHashMap<>();
    private final Map<String, Code> activationCodes = new LinkedHashMap<>();

    public GiftCodeCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.warn("missing {} — 礼包码/激活码不开放（detail field 86 保持 false）", file);
            return;
        }
        try {
            Root r = mapper.readValue(file.toFile(), Root.class);
            if (r == null) {
                return;
            }
            enableLibaoMa = r.enableLibaoMa;
            promptActivationOnLogin = r.promptActivationOnLogin;
            for (Code c : nullSafe(r.giftCodes)) {
                if (c != null && c.key != null && !c.key.trim().isEmpty()) {
                    c.key = c.key.trim();
                    c.reward = c.reward == null ? new Reward() : c.reward;
                    giftCodes.put(c.key, c);
                }
            }
            for (Code c : nullSafe(r.activationCodes)) {
                if (c != null && c.code != null && !c.code.trim().isEmpty()) {
                    c.code = c.code.trim();
                    c.reward = c.reward == null ? new Reward() : c.reward;
                    activationCodes.put(c.code, c);
                }
            }
            log.info("gift-code libaoMa={} promptActivation={} giftCodes={} activationCodes={}",
                    Boolean.valueOf(enableLibaoMa), Boolean.valueOf(promptActivationOnLogin),
                    Integer.valueOf(giftCodes.size()), Integer.valueOf(activationCodes.size()));
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    private static <T> List<T> nullSafe(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }

    /** 礼包码入口是否开放（{@code PlayerDumpService.detail} 写 field 86 用）。 */
    public boolean isEnableLibaoMa() {
        return enableLibaoMa && !giftCodes.isEmpty();
    }

    public boolean isPromptActivationOnLogin() {
        return promptActivationOnLogin && !activationCodes.isEmpty();
    }

    public Code gift(String key) {
        return key == null ? null : giftCodes.get(key.trim());
    }

    public Code activation(String code) {
        return code == null ? null : activationCodes.get(code.trim());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public boolean enableLibaoMa = true;
        public boolean promptActivationOnLogin = true;
        public List<Code> giftCodes = new ArrayList<>();
        public List<Code> activationCodes = new ArrayList<>();
    }

    /** 一条码。{@code key} 用于礼包码，{@code code} 用于激活码（同一结构两用）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Code {
        public String key;
        public String code;
        /** true（默认）= 每个账号只能兑一次；重复提交回 GIFT_PACK_RET_AlreadyGet(3) / 激活码 ret 2。 */
        public boolean oncePerAccount = true;
        /** 可选到期日 {@code yyyy-MM-dd}；当天仍可用，过期后按「无效」处理。空=不过期。 */
        public String expiresAt;
        public Reward reward = new Reward();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reward {
        public int gold;
        public int diamond;
        public int stamina;
        public int wannengFragments;
        /** 道具：{@code {"ori":"GOODS3","count":1}}。 */
        public List<Goods> goods = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Goods {
        public String ori;
        public int count = 1;
    }
}
