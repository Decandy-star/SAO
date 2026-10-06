package com.sao.fakeserver.http;

import com.sao.fakeserver.config.SaoDirs;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.service.GameTextBuildService;
import com.sao.fakeserver.service.KfzService;
import com.sao.fakeserver.service.MailService;
import com.sao.fakeserver.service.PayService;
import com.sao.fakeserver.service.PlayerDumpService;
import com.sao.fakeserver.service.ProgressService;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.SignInMonthStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.CultivateTables;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
public class AdminController {
    private final PlayerStore store;
    private final WorldStore world;
    private final ProgressService progress;
    private final CultivateTables cultivate;
    private final PlayerDumpService dump;
    private final SessionHub sessions;
    private final MailService mail;
    private final PayService pay;
    private final SignInMonthStore signInMonth;
    private final GameTextBuildService gameTextBuild;
    private final KfzService kfz;
    private final SaoProperties props;

    public AdminController(PlayerStore store, WorldStore world, ProgressService progress,
                           CultivateTables cultivate, PlayerDumpService dump, SessionHub sessions,
                           MailService mail, PayService pay, SignInMonthStore signInMonth,
                           GameTextBuildService gameTextBuild, KfzService kfz, SaoProperties props) {
        this.store = store;
        this.world = world;
        this.progress = progress;
        this.cultivate = cultivate;
        this.dump = dump;
        this.sessions = sessions;
        this.mail = mail;
        this.pay = pay;
        this.signInMonth = signInMonth;
        this.gameTextBuild = gameTextBuild;
        this.kfz = kfz;
        this.props = props;
    }

    @GetMapping("/admin/players")
    public Collection<PlayerRecord> players() {
        return store.all();
    }

    @GetMapping("/admin/unions")
    public Collection<WorldStore.UnionRecord> unions() {
        return world.unions();
    }

    @GetMapping("/admin/jjc")
    public WorldStore.JjcWorld jjc() {
        return world.jjc();
    }

    @PostMapping("/admin/players/{account}/gold")
    public PlayerRecord gold(@PathVariable String account, @RequestParam int value) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        rec.gold = value;
        store.save(rec);
        return rec;
    }

    /**
     * GM 重置竞技场挑战次数。默认拉回 jjc-daily-times（5）；在线则推 S2C 2008。
     * 不扣钻、不抬 payResetCount（与客户端 1707 付费重置区分）。
     */
    @PostMapping("/admin/players/{account}/jjc-reset-times")
    public JjcResetTimesResult jjcResetTimes(@PathVariable String account,
                                             @RequestParam(required = false) Integer times) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        int daily = props.getJjcDailyTimes();
        if (daily <= 0) {
            daily = 5;
        }
        int left = times == null ? daily : Math.max(0, times);
        rec.arena.challengesLeft = left;
        store.save(rec);
        GameSession session = sessions.get(account);
        if (session != null) {
            GamePacket pkt = new GamePacket(0, 0, new byte[0]);
            session.send(MsgIds.S2C_JJC_RESET_TIMES, pkt, dump.jjcTimes(rec.arena.challengesLeft, rec.arena.payResetCount));
        }
        JjcResetTimesResult result = new JjcResetTimesResult();
        result.account = account;
        result.challengesLeft = rec.arena.challengesLeft;
        result.payResetCount = rec.arena.payResetCount;
        result.pushedOnline = session != null;
        return result;
    }

    public static class JjcResetTimesResult {
        public String account;
        public int challengesLeft;
        public int payResetCount;
        public boolean pushedOnline;
    }

    /**
     * GM 切跨服战 phase。1=排位；8–13=巅峰；**-1=取消强制、跟 KuaFuZhanBase 周历日钟**。
     * 在线则推 4712/4715/Top3/膜拜状态。
     */
    @PostMapping("/admin/players/{account}/kfz-phase")
    public KfzPhaseResult kfzPhase(@PathVariable String account, @RequestParam int phase) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        kfz.setPhase(rec, phase);
        GameSession session = sessions.get(account);
        boolean pushed = false;
        if (session != null) {
            GamePacket pkt = new GamePacket(0, 0, new byte[0]);
            kfz.pushStatus(session, pkt);
            pushed = true;
        }
        KfzPhaseResult r = new KfzPhaseResult();
        r.account = account;
        r.phase = rec.kfz.phase;
        r.phaseOverride = rec.kfz.phaseOverride;
        r.hasFightingDfz = rec.kfz.hasFightingDfz;
        r.dfzFinalEight = rec.kfz.dfzFinalEight;
        r.pushedOnline = pushed;
        return r;
    }

    public static class KfzPhaseResult {
        public String account;
        public int phase;
        public int phaseOverride;
        public boolean hasFightingDfz;
        public boolean dfzFinalEight;
        public boolean pushedOnline;
    }

    /**
     * GM 强制本周 KFZ 入围（假服单机调试；true=override 入围，false=取消 override）。
     */
    @PostMapping("/admin/players/{account}/kfz-eligible")
    public KfzEligibleResult kfzEligible(@PathVariable String account,
                                         @RequestParam(defaultValue = "true") boolean eligible) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        kfz.setEligibleOverride(rec, eligible);
        KfzEligibleResult r = new KfzEligibleResult();
        r.account = account;
        r.eligibleOverride = rec.kfz.eligibleOverride;
        r.eligibleThisWeek = rec.kfz.eligibleThisWeek;
        r.eligibleWeekId = rec.kfz.eligibleWeekId;
        r.jjcRankAtSnapshot = rec.kfz.jjcRankAtSnapshot;
        return r;
    }

    public static class KfzEligibleResult {
        public String account;
        public boolean eligibleOverride;
        public boolean eligibleThisWeek;
        public String eligibleWeekId;
        public int jjcRankAtSnapshot;
    }

    /**
     * 清空全部假服存档：踢在线、删 data/players/*.json、重置 world（公会/竞技场/抢矿）。
     * 必须带 confirm=yes，防止误触。清完新号 alreadyNewUserGuideFB=false，可再进新手本。
     */
    @PostMapping("/admin/clear-data")
    public ClearDataResult clearData(@RequestParam String confirm) {
        if (!"yes".equalsIgnoreCase(confirm)) {
            throw new IllegalArgumentException("pass confirm=yes to clear all player/world data");
        }
        ClearDataResult result = new ClearDataResult();
        result.disconnected = sessions.disconnectAll();
        result.playersRemoved = store.clearAll();
        world.clearAll();
        result.worldCleared = true;
        result.ok = true;
        return result;
    }

    public static class ClearDataResult {
        public boolean ok;
        public int disconnected;
        public int playersRemoved;
        public boolean worldCleared;
    }

    /**
     * 单账号清档重置：踢该号、删 data/players/{账号}.json。
     * 下次登录走创角，新号 level=1、alreadyNewUserGuideFB=false（可再进新手本）。
     * 不清公会/竞技场世界态（要用全服清档走 /admin/clear-data）。
     * 必须 confirm=yes。
     */
    @PostMapping("/admin/players/{account}/reset")
    public ResetPlayerResult resetPlayer(@PathVariable String account, @RequestParam String confirm) {
        if (!"yes".equalsIgnoreCase(confirm)) {
            throw new IllegalArgumentException("pass confirm=yes to reset this account");
        }
        ResetPlayerResult result = new ResetPlayerResult();
        result.account = account;
        result.disconnected = sessions.disconnect(account);
        result.deleted = store.delete(account);
        if (!result.deleted) {
            throw new IllegalArgumentException("no player " + account);
        }
        result.ok = true;
        return result;
    }

    public static class ResetPlayerResult {
        public boolean ok;
        public String account;
        public int disconnected;
        public boolean deleted;
    }

    /**
     * 给账号发一封系统邮件（调用一次追加一封）。未领取会一直留在账号 mails；领取后会从存档删除，不留历史。
     * <p>
     * 物品/装备 {@code ori} 从哪拿：
     * <ul>
     *   <li>道具：{@code tables/GoodsList.txt} 第 2 列「原始名」（如 GOODS3=黑剑士碎片、ZBSX01=铸铁）</li>
     *   <li>装备：{@code tables/EquipmentList.txt} 原始名（如 EQ0023）</li>
     *   <li>武将碎片对照：{@code tables/README.md}、{@code WuJiangBaseAttri} 的 fragmentOri</li>
     * </ul>
     * 简写（单附件，title/text 自动进 TitleParm/Paras 的 {0}）：
     * {@code POST .../mail?title=xxx&text=yyy&ori=GOODS3&count=10&gold=1000&mailType=2}
     * <p>
     * JSON 体（推荐，支持多占位参数 titleParms/paras，对应模板的 {0}{1}{2}… 按下标替换）：
     * <pre>
     * {"mailType":2,"titleParms":["全英雄碎片大礼包"],"paras":["英雄碎片"],
     *  "items":[{"ori":"GOODS3","count":10}]}
     * </pre>
     * mailType 与各模板占位表见 docs/SYSTEM_MAIL.md。客户端不支持 GM 邮件，这里统一发系统邮件；
     * mailType 必须 &gt;0（默认 2=礼包管理员），禁止 0。
     */
    @PostMapping("/admin/players/{account}/mail")
    public MailSendResult sendMail(@PathVariable String account,
                                   @RequestBody(required = false) MailSendRequest body,
                                   @RequestParam(required = false) String title,
                                   @RequestParam(required = false) String text,
                                   @RequestParam(required = false) String ori,
                                   @RequestParam(defaultValue = "1") int count,
                                   @RequestParam(defaultValue = "0") int stars,
                                   @RequestParam(defaultValue = "0") int gold,
                                   @RequestParam(defaultValue = "0") int diamond,
                                   @RequestParam(defaultValue = "2") int mailType) {
        MailSendRequest req = body != null ? body : new MailSendRequest();
        if (mailType > 0) {
            req.mailType = mailType;
        }
        if (req.mailType <= 0) {
            req.mailType = MailService.MAIL_TYPE_GIFT_ADMIN;
        }
        if (title != null && !title.isEmpty()) {
            req.title = title;
        }
        if (text != null && !text.isEmpty()) {
            req.text = text;
        }
        if (gold > 0) {
            req.gold = gold;
        }
        if (diamond > 0) {
            req.diamond = diamond;
        }
        if (ori != null && !ori.isEmpty()) {
            if (req.items == null) {
                req.items = new ArrayList<>();
            }
            PlayerRecord.MailItem it = new PlayerRecord.MailItem();
            it.ori = ori;
            it.count = count;
            it.stars = stars;
            req.items.add(it);
        }
        boolean hasMoney = req.gold > 0 || req.diamond > 0 || req.stamina > 0 || req.exp > 0
                || req.jjcScore > 0 || req.wannengFragments > 0 || req.yingPo > 0 || req.moFaChen > 0
                || req.brotherCoin > 0 || req.courageCoin > 0;
        boolean hasItems = req.items != null && !req.items.isEmpty();
        if (!hasMoney && !hasItems) {
            throw new IllegalArgumentException("mail needs currency and/or items");
        }
        List<String> titleParms = req.titleParms;
        if ((titleParms == null || titleParms.isEmpty()) && !req.title.isEmpty()) {
            titleParms = java.util.Collections.singletonList(req.title);
        }
        List<String> paras = req.paras;
        if ((paras == null || paras.isEmpty()) && !req.text.isEmpty()) {
            paras = java.util.Collections.singletonList(req.text);
        }
        PlayerRecord.Mail created = mail.sendSystemMail(account, req.mailType, titleParms, paras,
                req.gold, req.diamond, req.stamina, req.exp, req.jjcScore,
                req.wannengFragments, req.yingPo, req.moFaChen,
                req.brotherCoin, req.courageCoin,
                req.items);
        MailSendResult result = new MailSendResult();
        result.account = account;
        result.mailDynId = created.mailDynId;
        result.mailType = created.mailType;
        result.titleParm = created.titleParm;
        result.itemCount = created.items == null ? 0 : created.items.size();
        result.online = sessions.get(account) != null;
        return result;
    }

    public static class MailSendRequest {
        /** ESysMailTypeID：必须 &gt;0；默认 2=礼包管理员。占位表见 docs/SYSTEM_MAIL.md。 */
        public int mailType = MailService.MAIL_TYPE_GIFT_ADMIN;
        /** 简写：标题模板含 {0} 时进 titleParms[0]（等价 titleParms=["title"]） */
        public String title = "";
        /** 简写：正文模板含 {0} 时进 paras[0]（等价 paras=["text"]） */
        public String text = "";
        /** 标题模板占位参数 {0}{1}… 按下标替换（titleParm 组包 field 13） */
        public List<String> titleParms;
        /** 正文模板占位参数 {0}{1}… 按下标替换（paras 组包 field 12） */
        public List<String> paras;
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
        public List<PlayerRecord.MailItem> items;
    }

    public static class MailSendResult {
        public String account;
        public int mailDynId;
        public int mailType;
        /** 回显实际写入的标题参数（titleParm 列表）。 */
        public List<String> titleParm;
        public int itemCount;
        public boolean online;
    }

    @PostMapping("/admin/players/{account}/reset-pay-ext")
    public ResetPayExtResult resetPayExt(@PathVariable String account) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        pay.resetPayExt(rec);
        store.save(rec);
        GameSession session = sessions.get(account);
        if (session != null) {
            GamePacket pkt = new GamePacket(0, 0, new byte[0]);
            session.send(MsgIds.S2C_USER_PAY_INFO, pkt, dump.userPayInfo(rec));
        }
        ResetPayExtResult r = new ResetPayExtResult();
        r.account = account;
        r.payExtMonthKey = rec.economy.payExtMonthKey;
        r.cleared = true;
        r.online = session != null;
        return r;
    }

    @PostMapping("/admin/reset-pay-ext-all")
    public ResetPayExtAllResult resetPayExtAll(@RequestParam String confirm) {
        if (!"yes".equalsIgnoreCase(confirm)) {
            throw new IllegalArgumentException("confirm=yes required");
        }
        int n = 0;
        for (PlayerRecord rec : store.all()) {
            pay.resetPayExt(rec);
            store.save(rec);
            n++;
        }
        ResetPayExtAllResult r = new ResetPayExtAllResult();
        r.players = n;
        return r;
    }

    public static class ResetPayExtResult {
        public String account;
        public String payExtMonthKey;
        public boolean cleared;
        public boolean online;
    }

    public static class ResetPayExtAllResult {
        public int players;
    }

    /**
     * GM 发道具/装备。OriName 见 tables/GoodsList.txt、EquipmentList.txt。
     * 玩家在线时推送 S2C 301 / 1406；离线则仅写存档，重登后可见。
     */
    @PostMapping("/admin/players/{account}/grant")
    public GrantResult grant(@PathVariable String account,
                             @RequestParam String ori,
                             @RequestParam(defaultValue = "1") int count,
                             @RequestParam(defaultValue = "0") int stars) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            throw new IllegalArgumentException("invalid ori or count");
        }
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        if (cultivate.equip(ori) != null) {
            for (int i = 0; i < count; i++) {
                PlayerRecord.Equipment eq = progress.grantEquip(rec, ori);
                eq.stars = stars;
                newEq.add(eq);
            }
        } else {
            progress.addGoods(rec, ori, count);
            progress.markGoods(changed, ori);
        }
        store.save(rec);
        GameSession session = sessions.get(account);
        if (session != null) {
            GamePacket pkt = new GamePacket(0, 0, new byte[0]);
            progress.pushGoods(session, pkt, rec, changed);
            for (PlayerRecord.Equipment eq : newEq) {
                session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
            }
        }
        GrantResult result = new GrantResult();
        result.account = account;
        result.ori = ori;
        result.count = count;
        result.stars = stars;
        result.equipment = cultivate.equip(ori) != null;
        result.pushedOnline = session != null;
        result.bagCount = rec.bag.getOrDefault(ori, 0);
        result.equipmentIds = new ArrayList<>();
        for (PlayerRecord.Equipment eq : newEq) {
            result.equipmentIds.add(eq.id);
        }
        return result;
    }

    public static class GrantResult {
        public String account;
        public String ori;
        public int count;
        public int stars;
        public boolean equipment;
        public boolean pushedOnline;
        public int bagCount;
        public List<String> equipmentIds;
    }

    /**
     * 查看当月签到英雄碎片（全服共用）。
     * 来源：data/world/signin-month.json；池子=三星及以上武将 fragmentOri（CultivateTables / WuJiangBaseAttri）。
     * 到账走假服 SignInService；客户端格子图标仍读本地 GameText 里的 QianDao。
     */
    @GetMapping("/admin/signin-month")
    public SignInMonthStore.MonthPick signInMonth() {
        return signInMonth.current();
    }

    /**
     * 强制重抽当月签到英雄碎片，立刻写 data/world/signin-month.json。
     * <p>
     * 一般用 {@code POST /admin/signin-month/publish} 一步到位（定档+patch+合并发布），
     * 此接口只保留重抽本身；{@code patchQianDao=true} 时顺带改 tables/QianDao.txt 的 SP* 列。
     */
    @PostMapping("/admin/signin-month/reroll")
    public SignInMonthStore.MonthPick rerollSignInMonth(
            @RequestParam(defaultValue = "false") boolean patchQianDao) {
        return signInMonth.reroll(patchQianDao);
    }

    /**
     * 签到一步到位：定档当月碎片 + patch tables/QianDao.txt + 合并 GameText(可选打 AB / 抬版本)。
     * <p>
     * 定档优先级：{@code ori=SPxxx} 直接指定 &gt; {@code force=true} 随机重抽 &gt; 沿用已定档。
     * 无 force/ori 时只做「把 tables/QianDao.txt 的 SP* 列刷成当前定档碎片 → 合并发布」，
     * 用于修复表与 signin-month.json 不一致（客户端签到格子与到账不同英雄）的情况。
     * <p>
     * 流程：resolveAndPatch(定档写档+改表) → rebuild.ps1(syncQianDao=true 覆盖进切分树再合并)
     * → 合并产物同步到 Unity 工程 Assets/Resources/GameText.txt（编辑器明文基线）。
     * {@code buildAb=true} 额外打热更 AB（Unity 4.6）。
     */
    @PostMapping("/admin/signin-month/publish")
    public SignInPublishResult publishSignInMonth(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) String ori,
            @RequestParam(defaultValue = "false") boolean buildAb,
            @RequestParam(defaultValue = "true") boolean bumpVersion) {
        SignInMonthStore.MonthPick pick = signInMonth.resolveAndPatch(force, ori);
        GameTextBuildService.Status gs = gameTextBuild.rebuild(buildAb, true, bumpVersion);
        copyMergedToAssets();
        SignInPublishResult r = new SignInPublishResult();
        r.year = pick.year;
        r.month = pick.month;
        r.heroIndex = pick.heroIndex;
        r.fragmentOri = pick.fragmentOri;
        r.gameText = gs;
        return r;
    }

    /** 把合并出的 GameText 同步到 Unity 工程 Assets/Resources/GameText.txt（编辑器明文走这里）。 */
    private void copyMergedToAssets() {
        String cfg = props.getGameText();
        if (cfg == null || cfg.trim().isEmpty()) {
            return;
        }
        Path assets = SaoDirs.resolve(cfg);
        Path merged = Paths.get(props.getGametextDir()).resolve("out").resolve("GameText.txt");
        if (!Files.isRegularFile(merged)) {
            throw new IllegalStateException("merged GameText missing: " + merged);
        }
        if (assets.getParent() == null || !Files.isDirectory(assets.getParent())) {
            // 无 Unity 工程环境（如云服务器跑假服）时跳过复制；打 AB 另走 out/IncrementPack
            return;
        }
        try {
            Files.copy(merged, assets, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("copy merged GameText -> " + assets + " fail: " + e, e);
        }
    }

    /** publish 返回值。 */
    public static class SignInPublishResult {
        public int year;
        public int month;
        public int heroIndex;
        public String fragmentOri = "";
        public GameTextBuildService.Status gameText;
    }

    /**
     * GameText 工作区状态：切分树 / 合并包 / 热更 AB 是否存在。
     * 固定目录：sao-fake-server/gametext/（见 gametext/README.md）。
     */
    @GetMapping("/admin/gametext/status")
    public GameTextBuildService.Status gameTextStatus() {
        return gameTextBuild.status();
    }

    /**
     * 合并已有切分树并可选打热更包（只调 rebuild.ps1，**不切分**）。
     * <ul>
     *   <li>{@code syncQianDao=true}：先把 tables/QianDao.txt 覆盖进 work/.../QianDao.txt</li>
     *   <li>{@code buildAb=true}：合并后打 GameText.txt.bytes + MD5File.txt.bytes 到 IncrementPack/{ver}/</li>
     *   <li>{@code bumpVersion=true}：热更版本最后一段 +1，并写入 Version.bytes 所用文件</li>
     * </ul>
     * 打 AB 只用 Unity 4.6（{@code build_ab.ps1}）。切分说明见 gametext/SPLIT_TREE.md；发布见 发布.md。
     */
    @PostMapping("/admin/gametext/rebuild")
    public GameTextBuildService.Status gameTextRebuild(
            @RequestParam(defaultValue = "true") boolean buildAb,
            @RequestParam(defaultValue = "false") boolean syncQianDao,
            @RequestParam(defaultValue = "true") boolean bumpVersion) {
        return gameTextBuild.rebuild(buildAb, syncQianDao, bumpVersion);
    }
}
