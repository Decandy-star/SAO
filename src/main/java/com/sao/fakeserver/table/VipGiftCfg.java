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
import java.util.Collections;
import java.util.List;

/**
 * VIP 礼包档位表（看板 type5「VIP特权礼包」）——{@code vip-gift.json}。
 *
 * <p>协议两端都在包里，缺的只是「真服卖什么」：
 * <ul>
 *   <li>协议：C2S 2309 查档位 / 2310 查已购 / 2311 购买；S2C 2617 / 2609 / 2610
 *       （{@code pyfoot\tmp_msgdll\NetProto\CCMsgQueryVIPGiftItem.cs}：1 mID / 2 mVipLevel /
 *       3 mOriName / 4 mCount / 5 mNeedRMB）。</li>
 *   <li>客户端：{@code ActivityPropertyMgr.UpdateVIPGiftCfg}（{@code :313-332}）把 2617 的 items
 *       填进 {@code mVipBuy} 后 {@code RefreshVipBuyGiftAll()}；{@code VipBuyItem.OnBuyClicked}
 *       （{@code :133-165}）先查 {@code mCurRMB < mNeedRMB}（→ 弹充值框）与
 *       {@code VipManager.GetVIPLevel() < mVipLevel}（→ 提示 100947），再发 2311。</li>
 *   <li>APK 里**没有任何表**给出这些档位（{@code tables\VipCfg.txt} 是等级特权/等级奖励，
 *       {@code TeQuanCard.txt} 是月卡/至尊卡，都不含 VIP 礼包）→ 由本表提供；
 *       文件缺失 / {@code enabled=false} / {@code items} 为空 = 面板无礼包（协议照常回空表）。</li>
 * </ul>
 *
 * <p>字段口径：{@code needRmb} 与客户端 {@code mCurRMB} 比较，而 {@code mCurRMB} 在假服就是
 * 钻石余额（登录 detail field7 = {@code rec.diamond}，改名花费 100「钻石」同源），故购买时扣
 * {@code rec.diamond}；{@code vipLevel} 与 {@code VipManager.GetVIPLevel()} 比较，后者由
 * {@code Attribute.mCurBuyZuanShi}（attri 10 = {@code rec.economy.chargedDiamond}）按
 * {@code VipCfg.txt}「累计钻石数量」现算 → 假服用 {@link EconomyTables#vipLevel(int)} 同口径。
 */
@Component
public class VipGiftCfg {
    private static final Logger log = LoggerFactory.getLogger(VipGiftCfg.class);
    private static final String FILE = "vip-gift.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;

    private boolean enabled;
    private List<Item> items = Collections.emptyList();

    public VipGiftCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        reload(Paths.get(props.getTablesDir()).resolve(FILE));
    }

    /** 重新读取配置（启动、热更、测试）；文件缺失或解析失败 = 空表（不猜档位）。 */
    public void reload(Path file) {
        if (!Files.isRegularFile(file)) {
            enabled = false;
            items = Collections.emptyList();
            log.warn("missing {} -> VIP 礼包档位为空（协议本身可用）", file);
            return;
        }
        try {
            VipGiftFile f = mapper.readValue(file.toFile(), VipGiftFile.class);
            enabled = f != null && f.enabled;
            List<Item> list = new ArrayList<>();
            if (f != null && f.items != null) {
                for (Item it : f.items) {
                    if (it == null || it.id <= 0 || it.ori == null || it.ori.isEmpty()
                            || "0".equals(it.ori) || it.count <= 0) {
                        continue;
                    }
                    list.add(it);
                }
            }
            items = list;
            log.info("vip-gift enabled={} items={}", Boolean.valueOf(enabled), Integer.valueOf(items.size()));
        } catch (IOException e) {
            enabled = false;
            items = Collections.emptyList();
            log.warn("load {} failed: {}", file, e.toString());
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 全部档位（只读）；{@code enabled=false} 时为空。 */
    public List<Item> items() {
        return enabled ? Collections.unmodifiableList(items) : Collections.<Item>emptyList();
    }

    /** 按 mID 找档位；没配返回 null。 */
    public Item item(int id) {
        if (!enabled || id <= 0) {
            return null;
        }
        for (Item it : items) {
            if (it.id == id) {
                return it;
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VipGiftFile {
        public boolean enabled;
        public List<Item> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        /** mID：档位 id，购买包与已购串都用它。 */
        public int id;
        /** mVipLevel：可购买所需 VIP 等级。 */
        public int vipLevel;
        /** mOriName：道具 ori（装备或普通道具）。 */
        public String ori;
        /** mCount：数量。 */
        public int count;
        /** mNeedRMB：价格（与客户端 mCurRMB = 钻石余额比较）。 */
        public int needRmb;
    }
}
