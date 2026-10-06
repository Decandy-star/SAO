package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.table.EconomyTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final SaoProperties props;
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final EconomyTables tables;
    private final MailService mail;
    private final PayService pay;
    private final SessionHub sessions;
    private final TaskService task;
    private final CityPosService cityPos;
    private final ActivityService activity;
    private final LtsjService ltsj;
    private final ZbzService zbz;
    private final KfzService kfz;
    private final BobService bob;
    private final DungeonService dungeon;
    private final SignInService signIn;
    private final UnionService union;
    private final GiftCodeService giftCode;
    private final ZhuanPanService zhuanPan;
    private final LtExchangeService ltExchange;

    public LoginService(SaoProperties props, PlayerStore store, PlayerDumpService dump, ProgressService progress,
                        EconomyTables tables,
                        MailService mail, PayService pay, SessionHub sessions, TaskService task, CityPosService cityPos,
                        ActivityService activity, LtsjService ltsj, ZbzService zbz, KfzService kfz, BobService bob,
                        DungeonService dungeon, SignInService signIn, UnionService union,
                        GiftCodeService giftCode, ZhuanPanService zhuanPan, LtExchangeService ltExchange) {
        this.props = props;
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.mail = mail;
        this.pay = pay;
        this.sessions = sessions;
        this.task = task;
        this.cityPos = cityPos;
        this.activity = activity;
        this.ltsj = ltsj;
        this.zbz = zbz;
        this.kfz = kfz;
        this.bob = bob;
        this.dungeon = dungeon;
        this.signIn = signIn;
        this.union = union;
        this.giftCode = giftCode;
        this.zhuanPan = zhuanPan;
        this.ltExchange = ltExchange;
    }

    public void onInit(GameSession session, GamePacket pkt) {
        session.send(MsgIds.S2C_INIT, pkt, dump.init(props.getInitSerial()));
    }

    /** 心跳顺带 tick 征战水晶恢复，并在跨日时推矿战 2125 清买复活次数。 */
    public void onHeartbeat(GameSession session, GamePacket pkt) {
        session.send(MsgIds.S2C_HEARTBEAT, pkt, new byte[0]);
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (progress.ensureDaily(rec)) {
            store.save(rec);
            session.send(MsgIds.S2C_KUANG_RESET_RELIVE, pkt, new byte[0]);
            // 日清后同步 attri13/14（DefenseKuangCnt / CoDefenseKuangCnt），避免大厅仍显示旧日计
            progress.pushDefenseKuang(session, pkt, rec);
            progress.pushCoDefenseKuang(session, pkt, rec);
            session.send(MsgIds.S2C_RESOURCE_FB_UPDATE, pkt, dump.resourceFbUpdate(rec));
            // 同属跨日态、且客户端没有查询包的两块必须一起补推：
            // 3702（限时神将期数/免费次数/宝箱态）与 2601（活动看板 7 日签到态/今日体力档/已领串）。
            // 否则心跳这条在线跨日路径只刷了 attri13/14 与 450，玩家面板一半是新一天、一半是昨天。
            ltsj.pushTo(session, pkt);
            activity.pushStatus(session, pkt);
            log.info("{} heartbeat daily reset → S2C 2125 + attri13/14 + 450 + 3702 + 2601", rec.account);
            signIn.pushTipsIfNeed(session, pkt, rec);
        }
        if (dungeon.ensureTowerWeeklyReset(rec)) {
            store.save(rec);
        }
        if (progress.tickZzRestore(rec)) {
            store.save(rec);
            progress.pushZz(session, pkt, rec);
        }
    }

    /**
     * 账号白名单：{@code sao.login-account} 可写多个账号（逗号分隔），{@code *} 或留空 = 不限账号。
     *
     * <p>改前这里是「只允许 {@code login-account} 一个账号」（默认 admin），第二个账号一律被
     * {@code channel().close()} 断线 —— 多人联调时这是硬阻塞。密码校验（{@code login-password}）保留：
     * 客户端 {@code LoginSystem.cs:369-375} 会把登录界面里输入的 account/password 一起发上来。
     */
    private boolean loginAccountAllowed(String account) {
        String allow = props.getLoginAccount();
        if (allow == null || allow.trim().isEmpty() || "*".equals(allow.trim())) {
            return true;
        }
        for (String one : allow.split(",")) {
            if (one.trim().equals(account)) {
                return true;
            }
        }
        return false;
    }

    public void onAccountEnter(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        String account = f.getString(1);
        String password = f.getString(2);
        if (props.isEnforceLogin()) {
            String expectPwd = props.getLoginPassword() == null ? "1" : props.getLoginPassword();
            if (account == null || password == null || !expectPwd.equals(password)) {
                log.warn("login rejected account={} (password mismatch)", account);
                session.channel().close();
                return;
            }
            if (!loginAccountAllowed(account)) {
                log.warn("login rejected account={} (not in sao.login-account whitelist)", account);
                session.channel().close();
                return;
            }
        }
        if (account == null || account.trim().isEmpty()) {
            account = "guest";
        }
        session.setAccount(account);
        PlayerRecord rec = store.get(account);
        if (rec == null && !props.isAutoCreateRole()) {
            log.info("new account {}, notify create role", account);
            session.send(MsgIds.S2C_NOTIFY_CREATE_ROLE, pkt, new byte[0]);
            return;
        }
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), props.getDefaultWujiangIndex(), 1, account);
            store.save(rec);
        }
        session.setPlayer(rec);
        progress.ensureDaily(rec);
        dungeon.ensureTowerWeeklyReset(rec);
        progress.ensureSkillPointRecoveryAnchor(rec);
        // 公会：刷新成员列表的「最后在线时刻」，并补推被让位前会长的 1968（见 UnionService.touchLogin）。
        union.touchLogin(rec);
        // 副本未正常回城时 currentRegionId 会停在关卡（如 1009），重登先拉回主城
        int mainCity = props.getMainCityRegionId();
        if (rec.currentRegionId != mainCity && rec.currentRegionId != 99) {
            log.info("{} login recover region {} -> {}", account, rec.currentRegionId, mainCity);
            rec.currentRegionId = mainCity;
        }
        // 不清 lastResource*：进本已扣次，重连后 Result 仍须走 applyResourceWin。
        // 若这里清掉，321/326/… 胜包会当成主线 applyWin（城市场景 + 难度 1–7）。
        // 旧档：装备误进 bag（1-9 EQ0012 等）→ 拆成装备实例，避免登录包把 EQ 当道具推爆客户端
        if (progress.sanitizeEquipInBag(rec)) {
            log.info("{} login sanitized equip-in-bag", account);
        }
        // 旧档：钻石池曾含 index≥1000 怪/NPC（同名「幸」、机枪等）→ 登录剔除
        if (progress.sanitizeInvalidHeroes(rec)) {
            log.info("{} login sanitized invalid heroes", account);
        }
        task.ensure(rec);
        pay.ensurePayExtMonth(rec);
        mail.grantDue(rec);
        store.save(rec);
        sessions.bind(session);
        session.send(MsgIds.S2C_ACCOUNT_ENTER_RET, pkt, dump.accountEnterRet(rec));
        if (rec.cards.zhiZun || progress.monthlyCardActive(rec)) {
            session.send(MsgIds.S2C_TEQUAN_INFO, pkt, dump.teQuanInfo(rec));
        }
        if (mail.hasOpen(rec)) {
            mail.notifyNewMail(rec.account);
        }
        signIn.pushTipsIfNeed(session, pkt, rec);
        // 系统公告（S2C 30 走马灯）+ 首次登录弹激活码面板（S2C 21）
        giftCode.onLogin(session, pkt, rec);
        // 转盘：大厅入口显隐由 S2C 5102 控制（ZhuanPanStatus == null 时客户端强制隐藏），
        // 另推 5106 大赏跑马灯初始列表。
        zhuanPan.onLogin(session, pkt, rec);
        // 龙腾限时兑换：S2C 4101 必须登录主动推，客户端 IsOpenExchange 默认 false，
        // 不推就会弹 100858 关闭面板（ExchangeInATimeInfo.cs:15）。
        ltExchange.onLogin(session, pkt, rec);
        log.info("account enter {}", account);
    }

    public void onCreateRole(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        String roleName = f.getString(1);
        // 客户端 selectID=true 表示右侧角色（变量名 mIsLeftSelected 实际反着）
        boolean selectRight = f.getBool(2);
        String account = session.account();
        if (account == null || account.trim().isEmpty()) {
            account = (roleName == null || roleName.trim().isEmpty()) ? "guest" : roleName;
            session.setAccount(account);
        }
        if (store.get(account) != null) {
            session.send(MsgIds.S2C_CREATE_ROLE_RET, pkt, dump.createRoleRet(1));
            log.info("create role {} already exists", account);
            return;
        }
        int roleIndex = selectRight ? 2 : 1;
        int index = selectRight ? props.getAltWujiangIndex() : props.getDefaultWujiangIndex();
        PlayerRecord rec = dump.newPlayer(account, store.nextPlayerId(), index, roleIndex, roleName);
        store.save(rec);
        session.setPlayer(rec);
        progress.ensureDaily(rec);
        progress.ensureSkillPointRecoveryAnchor(rec);
        task.ensure(rec);
        pay.ensurePayExtMonth(rec);
        mail.grantDue(rec);
        store.save(rec);
        sessions.bind(session);
        session.send(MsgIds.S2C_CREATE_ROLE_RET, pkt, dump.createRoleRet(0));
        // 创角后客户端只播 ShowEnterAni，不会再发 C2S 101；进 Play/新手本必须靠服务端推 S2C 101。
        // accountEnterRet 里 alreadyNewUserGuideFB=false → 客户端进 region 99 新手本（不是等教程完再发）。
        // 教程打完是 C2S 304 回城时置 true。原服 1302→101 有间隔；同帧连发会踩 TweenAlpha 闪退。
        final int serial = pkt.serial;
        final String acc = account;
        session.channel().eventLoop().schedule(() -> {
            if (!session.channel().isActive()) {
                return;
            }
            PlayerRecord latest = store.get(acc);
            if (latest == null) {
                return;
            }
            session.setPlayer(latest);
            session.send(MsgIds.S2C_ACCOUNT_ENTER_RET, serial, dump.accountEnterRet(latest));
            if (latest.cards.zhiZun || progress.monthlyCardActive(latest)) {
                session.send(MsgIds.S2C_TEQUAN_INFO, serial, dump.teQuanInfo(latest));
            }
            if (mail.hasOpen(latest)) {
                mail.notifyNewMail(latest.account);
            }
            signIn.pushTipsIfNeed(session, null, latest);
            log.info("create role enter-ret delayed {}", acc);
        }, 2, java.util.concurrent.TimeUnit.SECONDS);
        log.info("create role {} index={} roleIndex={} selectRight={}", account, index, roleIndex, selectRight);
    }

    /**
     * C2S 2101 改昵称（{@code CCMsgPlayerName.PlayerName}，客户端 {@code MainPlayerSystem.cs:623-625}）。
     * 客户端只做长度 ≤15 / 非法字符 / 钻石三项前置校验，判重与扣费必须服务端做，回 S2C 2301
     * {@code CCMsgModifyRoleName_Ret}：retCode 0 = 成功（客户端冒字 100935 并覆盖 mName），
     * 1 = 昵称重复（冒字 100936）；两者都会发 EN_NICKNAME_RET 让确认按钮复位。
     * 扣费 = GlobalSetup「攻略组改名钻石花费」（{@code EconomyTables.changeNameCostRmb}，表值 100 钻）。
     * 钻石不足回 retCode=2：客户端没有该分支（只判 0/1），因此不冒字、不改名、按钮复位 —— 比谎报「昵称重复」准确；
     * 正常玩法里客户端已按 100338 拦下，服务端这道只是防绕过。
     * 成功后广播 S2C 2302 {@code CCMsgUpdatePlayerRoleName{dynID=playerId,newName}} 刷新别人视野里的名字。
     */
    public void onModifyRoleName(GameSession session, GamePacket pkt) {
        PlayerRecord rec = requirePlayer(session);
        if (rec == null) {
            return;
        }
        String name = Pb.read(pkt.body).getString(1);
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            session.send(MsgIds.S2C_MODIFY_ROLE_NAME_RET, pkt, dump.modifyRoleNameRet(1, ""));
            return;
        }
        if (name.equals(rec.roleName)) {
            // 没改动：不扣费，按成功回（客户端据此关面板）
            session.send(MsgIds.S2C_MODIFY_ROLE_NAME_RET, pkt, dump.modifyRoleNameRet(0, name));
            return;
        }
        PlayerRecord other = store.findByRoleName(name);
        if (other != null && !other.account.equals(rec.account)) {
            session.send(MsgIds.S2C_MODIFY_ROLE_NAME_RET, pkt, dump.modifyRoleNameRet(1, name));
            log.info("{} modify role name deny dup name={}", rec.account, name);
            return;
        }
        int cost = tables.changeNameCostRmb;
        if (rec.diamond < cost) {
            session.send(MsgIds.S2C_MODIFY_ROLE_NAME_RET, pkt, dump.modifyRoleNameRet(2, name));
            log.info("{} modify role name deny diamond={} cost={}", rec.account, rec.diamond, cost);
            return;
        }
        rec.diamond -= cost;
        rec.roleName = name;
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        session.send(MsgIds.S2C_MODIFY_ROLE_NAME_RET, pkt, dump.modifyRoleNameRet(0, name));
        byte[] broadcast = dump.updatePlayerRoleName(rec.playerId, name);
        for (GameSession s : sessions.onlineSnapshot()) {
            PlayerRecord p = s.player();
            if (p == null || rec.account.equals(p.account)) {
                continue;
            }
            s.send(MsgIds.S2C_UPDATE_PLAYER_ROLE_NAME, 0, broadcast);
        }
        log.info("{} modify role name ok -> {} cost={}", rec.account, name, cost);
    }

    public void onBackMainCity(GameSession session, GamePacket pkt) {
        PlayerRecord rec = requirePlayer(session);
        if (rec == null) {
            return;
        }
        // 新手本结束回大厅：标记已打过，下次登录直接进主城
        boolean alreadyReady = rec.alreadyNewUserGuideFB;
        if (!rec.alreadyNewUserGuideFB) {
            rec.alreadyNewUserGuideFB = true;
            log.info("{} finished new-user guide fb", rec.account);
        }
        rec.currentRegionId = props.getMainCityRegionId();
        store.save(rec);
        session.send(MsgIds.S2C_SET_MAIN_CITY_BORN, pkt, dump.vector3(props.getBornX(), props.getBornY(), props.getBornZ()));
        cityPos.put(rec, props.getBornX(), props.getBornY(), props.getBornZ(), 0f);
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(rec.currentRegionId));
        // 登录关新手本时客户端会先 C2S 304 再创角：activity/KFZ 无空判会 NRE → 首次教程回城不推。
        // 已过新手本后的回城（含 ZBZ 退图）补推 4602。
        if (alreadyReady) {
            zbz.pushInfo(session, pkt);
            kfz.pushCityStatues(session, pkt);
            // 限时神将客户端没有查询包（全量 grep 只有 3304 发送点），全靠服务端推 3702；
            // 跨日重开一期/轮换当期神将后，回大厅必须重新推一次，否则面板一直是旧期数据。
            ltsj.pushTo(session, pkt);
        }
    }

    public void onFirstEnterRegion(GameSession session, GamePacket pkt) {
        PlayerRecord rec = requirePlayer(session);
        if (rec == null) {
            return;
        }
        // 原协议：先 S2C 103 出生点，再 S2C 102 创建主玩家。
        // 客户端首次进大厅用 msBornPos，不是 Region.BornBos。
        session.send(MsgIds.S2C_SET_MAIN_CITY_BORN, pkt, dump.vector3(props.getBornX(), props.getBornY(), props.getBornZ()));
        cityPos.put(rec, props.getBornX(), props.getBornY(), props.getBornZ(), 0f);
        session.send(MsgIds.S2C_FIRST_ENTER_REGION_RET, pkt, dump.mainPlayer(rec));
        activity.pushStatus(session, pkt);
        ltsj.pushTo(session, pkt);
        zbz.pushInfo(session, pkt);
        // 仅推 4712（有空判）。4715 无空判，改到 KFZ 赛程请求时再推。
        kfz.pushPhaseOnly(session, pkt);
        bob.pushResetTimes(session, pkt);
    }

    public void onRequestFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = requirePlayer(session);
        if (rec == null) {
            return;
        }
        int type = Pb.read(pkt.body).getInt(1, 0);
        session.send(MsgIds.S2C_FORMATION, pkt, dump.formation(rec, type));
    }

    public void onReconnect(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        String account = f.getString(1);
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            session.send(MsgIds.S2C_RECONNECT_RET, pkt, dump.createRoleRet(2));
            return;
        }
        // 真服确实使用客户端上报的 regionResID + pos（用户裁决，m01963）。断线重连是「软重连」：
        // 玩家没离开当前场景，所以这里**不做** onAccountEnter 那条「不在主城就拉回主城」的兜底
        // （LoginService.java:139-143），而是把服务端记录的所在地刷成客户端权威值 ——
        // DungeonService.java:79/157/177/:399 都拿 rec.currentRegionId 当包内 region 缺失时的兜底，
        // 陈旧值会让结算/扫荡落到错的关卡；坐标进 CityPosService，与 C2S 103 同一份内存表。
        // CCMsgReConnect：1 account、2 sdkNo、3 pos(CCMsgVector3: 1 x/2 y/3 z，fixed32)、4 regionResID。
        Pb.Fields pos = Pb.read(f.getBytes(3));
        float px = pos.getFloat(1, 0f);
        float py = pos.getFloat(2, 0f);
        float pz = pos.getFloat(3, 0f);
        int regionResId = f.getInt(4, 0);
        if (regionResId > 0) {
            rec.currentRegionId = regionResId;
        }
        store.save(rec);
        cityPos.put(rec, px, py, pz, 0f);
        log.info("{} reconnect region={} pos=({}, {}, {})", account,
                Integer.valueOf(rec.currentRegionId), Float.valueOf(px), Float.valueOf(py), Float.valueOf(pz));
        session.setAccount(account);
        session.setPlayer(rec);
        sessions.bind(session);
        session.send(MsgIds.S2C_RECONNECT_RET, pkt, dump.reconnectOk(rec));
        // 客户端重连只发 C2S 601、不重走首次进区（NetworkManager.cs:464-491），而限时神将面板
        // 读的是静态 XianShiShenJiang.mInfo、活动看板读 ActivityStatusInfo——两者都只在
        // 102 路径/0 点被推过，且客户端没有 3302 查询包 → 断线重连后（尤其跨了 0 点或换了期数）
        // 面板一直是重连前的数据。这里补推，与 onFirstEnterRegion 保持一致。
        activity.pushStatus(session, pkt);
        ltsj.pushTo(session, pkt);
    }

    private PlayerRecord requirePlayer(GameSession session) {
        PlayerRecord rec = session.player();
        if (rec == null && session.account() != null) {
            rec = store.get(session.account());
            session.setPlayer(rec);
        }
        return rec;
    }
}
