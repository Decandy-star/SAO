package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.SignInTables;
import com.sao.fakeserver.table.TaskTables;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * D4（邮件 / 任务 / 签到）按 APK 真实序列走一遍的回归测试。
 *
 * <p>这里钉的不是「服务端能不能跑」，而是「客户端按 APK 的调用序列发过来时，服务端回的包
 * 是不是客户端期待的那个包、字段是不是那个字段」。每段断言都在注释里写清 APK 侧的对应代码位置，
 * 改动 D4 行为时这个测试必须一起动。
 *
 * <p>对照的 APK 源码：{@code F:\workspace\daojian\SAO\decompiled\client-src\}
 * （邮件 {@code MobileGameDemo\MailSystem.cs}、任务 {@code MobileGameDemo\TaskSystem.cs}
 * 与 {@code PlayGameState.cs} 的 On{First}Update*、签到 {@code MobileGameDemo\SignInSystem.cs}）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // 相对路径按 surefire fork 的 CWD 解析（实测 = 模块根/target/classes），落在 target 下，mvn clean 即清。
        "sao.data-dir=target/test-data/players-d4-flow",
        "sao.world-dir=target/test-data/world-d4-flow",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class D4FlowSimTest {
    private static final String ACCOUNT = "test-d4-flow";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private MailService mail;
    @Autowired
    private TaskService task;
    @Autowired
    private SignInService signIn;
    @Autowired
    private TaskTables taskTables;
    @Autowired
    private SignInTables signInTables;
    @Autowired
    private SessionHub hub;

    private EmbeddedChannel channel;

    @AfterEach
    public void cleanup() {
        if (channel != null) {
            channel.finishAndReleaseAll();
            channel = null;
        }
    }

    /**
     * 邮件线：801 → 1201 / 1205 → 1202（单封）→ 802 → 1203。
     *
     * <p>APK：登录 detail 落地后 {@code MainPlayer.cs} 发 801；{@code MailSystem.OnClickReceive}
     * 对「有附件」的邮件发 802（{@code CMsgMailDynID.mailDynID}），收到 1203 才把条目从列表里删掉。
     */
    @Test
    public void mailRequestClaimAndDelete() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        // 收到新邮件：服务端应当同时推 1205（红点）和 1202（单封），APK 的 UpdateMail 靠 1202 增量入列
        PlayerRecord.Mail m = new PlayerRecord.Mail();
        m.kind = "sys";
        m.mailType = MailService.MAIL_TYPE_JJC_RANK;
        m.titleParm.add("测试排名");
        m.paras.add("第1名");
        m.gold = 777;
        m.mailDynId = 99001;
        m.sendTime = PlayerDumpService.now();
        rec.mails.add(m);
        store.save(rec);

        int goldBefore = rec.gold;
        mail.notifyNewMail(ACCOUNT);
        List<GamePacket> pushed = drain();
        Assertions.assertTrue(has(pushed, MsgIds.S2C_MAIL_NOTIFY),
                "新邮件必须推 1205（APK 收到后置 mNewMail 红点）");
        Assertions.assertEquals(0, bodyOf(pushed, MsgIds.S2C_MAIL_NOTIFY).length,
                "1205 是空包：APK 的 OnNotifyMailUpdate 不解析 body");

        // 客户端请求整份邮件列表（801）
        GamePacket req = new GamePacket(MsgIds.C2S_REQUEST_MAIL, 11, new byte[0]);
        // 先热身一次 801：MailService.onRequest 会调 grantDue 补发「今天已到点」的系统邮
        // （jjc/zbz/kfz 排名邮 + vip 每日邮）并把 key 写进 mailGrantKeys，key 是
        // 「日期/类型」防重的（MailService.java:271-273、:418）。不热身的话，下面清空
        // rec.mails 之后这次 801 会当场补发当天的 JJC + 跨服排位邮，field2 自然不为空——
        // 那是补发的正常行为，不是「列表没跟着存档刷新」。热身把当天该补的补完、防重 key
        // 记上，再清空存档，这一次 801 才真正只反映当前 rec.mails。
        mail.onRequest(session, req);
        drain();
        rec.mails.clear();
        store.save(rec);
        mail.onRequest(session, req);
        GamePacket list = firstOf(channel, MsgIds.S2C_MAIL_LIST);
        Assertions.assertNotNull(list, "801 必须回 1201");
        Assertions.assertEquals(11, list.serial, "回包 serial 必须回显请求 serial（客户端靠它对回调）");
        Pb.Fields listBody = Pb.read(list.body);
        Assertions.assertEquals(0, listBody.getBytesList(1).size(), "本轮没发 GM 邮，field1 gmMail 应为空");
        Assertions.assertEquals(0, listBody.getBytesList(2).size(), "已清空邮件，field2 sysMail 应为空");

        // 重新发一封并走列表 → 领取 → 删除
        mail.sendSystemMail(ACCOUNT, MailService.MAIL_TYPE_JJC_RANK,
                java.util.Collections.singletonList("测试排名"),
                java.util.Collections.singletonList("第1名"),
                777, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new ArrayList<PlayerRecord.MailItem>());
        drain();
        mail.onRequest(session, req);
        GamePacket list2 = firstOf(channel, MsgIds.S2C_MAIL_LIST);
        Assertions.assertNotNull(list2, "有邮件时 801 必须回 1201");
        List<byte[]> sysMails = Pb.read(list2.body).getBytesList(2);
        Assertions.assertEquals(1, sysMails.size(), "list 里应有 1 封 sys 邮");
        Pb.Fields one = Pb.read(sysMails.get(0));
        int dynId = one.getInt(1, 0);
        Assertions.assertTrue(dynId > 0, "sys 邮必须带 mailDynID(field1)，802 要拿它来领");
        Assertions.assertEquals(MailService.MAIL_TYPE_JJC_RANK, one.getInt(2, 0),
                "field2 mailType 必须是 Sys_MailConfig 里的模板 ID（0 会渲染成空标题）");
        Assertions.assertFalse(one.getString(3).isEmpty(),
                "field3 sendTime 必须非空（sysMail 用 stringAlways 下发）");
        Assertions.assertEquals(777, one.getInt(4, 0), "field4 是金币，APK 按它渲染附件与领取判定");

        GamePacket handle = new GamePacket(MsgIds.C2S_HANDLE_MAIL, 12, Pb.write(o -> Pb.int32(o, 1, dynId)));
        mail.onHandle(session, handle);
        GamePacket del = firstOf(channel, MsgIds.S2C_MAIL_DEL);
        Assertions.assertNotNull(del, "802 必须回 1203");
        Assertions.assertEquals(12, del.serial, "1203 的 serial 必须回显 802 的 serial");
        Assertions.assertEquals(dynId, Pb.read(del.body).getInt(1, 0),
                "1203 只带 mailDynID(field1)：APK 按它从列表里删这一封");
        Assertions.assertEquals(goldBefore + 777, rec.gold, "领取必须真发金币");
        Assertions.assertTrue(rec.mails.isEmpty(), "领取后服务端必须把这封邮件删掉（重登列表才不会再出现）");
    }

    /**
     * 任务线：703 → 1103 / 701 → 1101 / 702 → 1102 / 704 → 1104。
     *
     * <p>APK：{@code PlayGameState.OnFirstUpdateDailyTask/OnFirstUpdateOnceTask} 只吃第一份全量，
     * 之后全靠 1102/1104 增量；702/704 带 taskId，服务端回 1102/1104 时「已领」状态要带上。
     */
    @Test
    public void taskListPrizeAndAccept() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        GamePacket onceReq = new GamePacket(MsgIds.C2S_ONCE_TASK, 21, new byte[0]);
        task.onOnceList(session, onceReq);
        GamePacket once = firstOf(channel, MsgIds.S2C_ONCE_TASK_RET);
        Assertions.assertNotNull(once, "703 必须回 1103");
        List<byte[]> onceItems = Pb.read(once.body).getBytesList(1);
        Assertions.assertFalse(onceItems.isEmpty(), "新号至少有一条已解锁的主线任务");
        // 接任务邮（mailType=18）由 TaskService.ensure → unlockOnce 的 `t == null` 分支发
        // （TaskService.java:303-308）。freshPlayer 里的 rec.tasks.ensure() 只是建集合
        // （PlayerRecord.Tasks.ensure :1485），真正解锁发生在这次 703；
        // 表里 notifyMail!=0 且 preTaskId==0 的有 3 条（218/225/…），所以新号至少 1 封。
        int acceptMails = 0;
        for (PlayerRecord.Mail mm : rec.mails) {
            if (mm.mailType == MailService.MAIL_TYPE_TASK) {
                acceptMails++;
            }
        }
        Assertions.assertTrue(acceptMails >= 1,
                "解锁主线必须发 mailType=18 的接任务邮（否则「接任务通知邮」整条链路是死的）");
        for (byte[] raw : onceItems) {
            Pb.Fields f = Pb.read(raw);
            Assertions.assertTrue(f.getInt(1, 0) > 0, "1103 条目必须带 taskId(field1)");
            Assertions.assertFalse(f.getBool(3), "1103 下发的是未删除的条目");
        }

        GamePacket dailyReq = new GamePacket(MsgIds.C2S_DAILY_TASK, 22, new byte[0]);
        task.onDailyList(session, dailyReq);
        GamePacket daily = firstOf(channel, MsgIds.S2C_DAILY_TASK_RET);
        Assertions.assertNotNull(daily, "701 必须回 1101");
        Assertions.assertEquals(22, daily.serial, "1101 的 serial 必须回显 701");
        List<byte[]> dailyItems = Pb.read(daily.body).getBytesList(1);
        Assertions.assertEquals(taskTables.dailyAll().size(), dailyItems.size(),
                "1101 必须是整份日常（APK 收到第一份全量后 mAllDailyTaskInfoNeedSyncServer 置 false）");

        // 打一次普通副本：日常 type1 涨次数（APK 侧由 1102 增量刷新面板）
        TaskTables.DailyCfg normal = null;
        for (TaskTables.DailyCfg cfg : taskTables.dailyAll()) {
            if (cfg.type == TaskService.DAILY_NORMAL_FB) {
                normal = cfg;
                break;
            }
        }
        Assertions.assertNotNull(normal, "日常表必须有 type=1（普通副本）");
        task.onDailyAction(session, new GamePacket(MsgIds.C2S_DAILY_TASK, 23, new byte[0]),
                rec, TaskService.DAILY_NORMAL_FB, 1);
        GamePacket upd = firstOf(channel, MsgIds.S2C_DAILY_TASK_UPDATE);
        Assertions.assertNotNull(upd, "日常有变化必须推 1102");
        Pb.Fields updOne = Pb.read(Pb.read(upd.body).getBytesList(1).get(0));
        Assertions.assertEquals(normal.id, updOne.getInt(1, 0), "1102 增量要带改动的 taskId");
        Assertions.assertEquals(1, updOne.getInt(2, 0), "1102 增量要带新的 finishTimes");

        // 直接推到完成 → 702 领奖 → 1102 回包里该条目 prized=true
        PlayerRecord.DailyTask dt = rec.tasks.daily(normal.id);
        dt.finishTimes = normal.finishCount;
        int goldBefore = rec.gold;
        final int dailyTaskId = normal.id;
        GamePacket prize = new GamePacket(MsgIds.C2S_PRIZE_DAILY_TASK, 24, Pb.write(o -> Pb.int32(o, 1, dailyTaskId)));
        task.onPrizeDaily(session, prize);
        GamePacket prized = firstOf(channel, MsgIds.S2C_DAILY_TASK_UPDATE);
        Assertions.assertNotNull(prized, "702 必须回 1102");
        Assertions.assertEquals(24, prized.serial, "1102 的 serial 必须回显 702");
        Pb.Fields prizedOne = Pb.read(Pb.read(prized.body).getBytesList(1).get(0));
        Assertions.assertEquals(normal.id, prizedOne.getInt(1, 0));
        Assertions.assertTrue(prizedOne.getBool(3), "领取后必须下发 prized=true，否则面板一直显示可领");
        Assertions.assertTrue(dt.prized, "服务端要记住已领，防止重复领");
        Assertions.assertEquals(goldBefore + normal.gold, rec.gold, "日常奖励金币必须真发");

        // 704 领一条主线（客户端只在「达成且未领」时才发）→ 1104 回该条 delete=true
        boolean accepted = false;
        for (TaskTables.OnceCfg cfg : taskTables.onceAll()) {
            PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(cfg.id));
            if (t == null || t.deleted) {
                continue;
            }
            t.finishTimes = cfg.finishCount;
            GamePacket oncePrize = new GamePacket(MsgIds.C2S_PRIZE_ONCE_TASK, 25,
                    Pb.write(o -> Pb.int32(o, 1, cfg.id)));
            task.onPrizeOnce(session, oncePrize);
            GamePacket onceUpd = firstOf(channel, MsgIds.S2C_ONCE_TASK_UPDATE);
            Assertions.assertNotNull(onceUpd, "704 必须回 1104");
            boolean sawDelete = false;
            for (byte[] raw : Pb.read(onceUpd.body).getBytesList(1)) {
                Pb.Fields f = Pb.read(raw);
                if (f.getInt(1, 0) == cfg.id && f.getBool(3)) {
                    sawDelete = true;
                }
            }
            Assertions.assertTrue(sawDelete, "1104 必须回 delete=true（APK 的 OnUpdateOnceTask 据此删除条目）");
            accepted = true;
            break;
        }
        Assertions.assertTrue(accepted, "新号应有一条可领的主线任务用来验证 704 回路");
    }

    /**
     * 签到线：1401 → 1801 / 1802 / 1402 → 1803。
     *
     * <p>APK：{@code SignInInfo.Update} 直接吃 1801 的四个字段（长度=今天日号），
     * {@code OnSignInItemPressed} 对今天这一格发 1402（{@code CCMsgPrizeOneTimeQianDao.DayNumber}）。
     */
    @Test
    public void signInInfoAndPrizeToday() {
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);

        int today = LocalDate.now().getDayOfMonth();
        GamePacket info = new GamePacket(MsgIds.C2S_QIANDAO_INFO, 31, new byte[0]);
        signIn.onInfo(session, info);
        GamePacket dump1801 = firstOf(channel, MsgIds.S2C_QIANDAO_INFO);
        Assertions.assertNotNull(dump1801, "1401 必须回 1801");
        Pb.Fields f = Pb.read(dump1801.body);
        Assertions.assertEquals(LocalDate.now().getYear(), f.getInt(1, 0), "field1 CurYear");
        Assertions.assertEquals(LocalDate.now().getMonthValue(), f.getInt(2, 0), "field2 CurMonth");
        Assertions.assertEquals(0, f.getInt(3, 0), "field3 TotalQianDaoDays 新号是 0");
        List<Integer> status = f.getInts(4);
        Assertions.assertEquals(today, status.size(),
                "field4 长度必须等于今天日号（APK 用 mPrizeStatus.Count 判断哪个格子是今天）");
        // 新号：过去的日子（1..today-1）下发 2（已领）——不接补签，不能让客户端画成可领；
        // 今天那格必须是 1（未领取）；0 会被 APK 画成「已领取」图章。
        for (int i = 0; i < status.size(); i++) {
            int expect = (i + 1 < today) ? 2 : 1;
            Assertions.assertEquals(expect, status.get(i).intValue(),
                    "第 " + (i + 1) + " 天应下发 " + expect);
        }
        // 1401 的回包里**不能**带 1802：APK 在面板打开时收到 1802 会立刻重发 1401
        // （SignInSystem.cs:258-263），而 1401 又回 1801+1802 → 无限请求循环。
        Assertions.assertFalse(has(drain(), MsgIds.S2C_QIANDAO_TIPS),
                "打开日历（1401）时不能再推 1802，否则和 APK 的面板重发 1401 形成自激循环");

        // 1802 只在「面板基本关着」的时机推：登录进场 / 跨日心跳 / 创角。
        // 这里直接验「有可领奖励时该方法确实会推」，登录链路由各 LoginService 调用点负责。
        signIn.pushTipsIfNeed(session, new GamePacket(MsgIds.C2S_QIANDAO_INFO, 33, new byte[0]), rec);
        Assertions.assertTrue(has(drain(), MsgIds.S2C_QIANDAO_TIPS),
                "登录/跨日时机必须推 1802，否则大厅签到红点不亮");

        // 1402 领今天
        int rmbBefore = rec.diamond;
        int goldBefore = rec.gold;
        SignInTables.DayRow row = signInTables.day(today);
        Assertions.assertNotNull(row, "QianDao 表必须有第 " + today + " 天");
        GamePacket prize = new GamePacket(MsgIds.C2S_QIANDAO_PRIZE, 32,
                Pb.write(o -> Pb.int32(o, 1, today)));
        signIn.onPrize(session, prize);
        GamePacket p1803 = firstOf(channel, MsgIds.S2C_QIANDAO_PRIZE);
        Assertions.assertNotNull(p1803, "1402 必须回 1803");
        Assertions.assertEquals(32, p1803.serial, "1803 的 serial 必须回显 1402");
        Assertions.assertEquals(today, Pb.read(p1803.body).getInt(1, 0),
                "1803 回的是领掉的日号（APK 按它更新那一格）");
        Assertions.assertEquals(1, rec.signIn.totalDays, "累计签到天数 +1");
        int st = rec.signIn.prizeStatus.get(today - 1).intValue();
        Assertions.assertTrue(st == 2 || st == 3, "今天那格必须是已领（2=普通 3=VIP 双倍），不能留在 1");
        Assertions.assertEquals(rmbBefore + row.rmb, rec.diamond, "签到钻石必须真发");
        Assertions.assertEquals(goldBefore + row.gold, rec.gold, "签到金币必须真发");

        // 再点一次：非 VIP 不许重复发奖（APK 只在「已领 + VIP 达标」时才二次 1402）
        signIn.onPrize(session, prize);
        if (st == 2) {
            Assertions.assertEquals(rmbBefore + row.rmb, rec.diamond, "非 VIP 重复领不能刷钻石");
            Assertions.assertEquals(1, rec.signIn.totalDays, "非 VIP 重复领不能刷累计天数");
        }
    }

    /** 跨日：昨天未领也不能补（ALLOW_MAKEUP=false），且不报错、不回包。 */
    @Test
    public void makeupSignInIsRefusedBeforeToday() {
        int today = LocalDate.now().getDayOfMonth();
        if (today <= 1) {
            return; // 每月 1 号没有「过去」可测
        }
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        signIn.onInfo(session, new GamePacket(MsgIds.C2S_QIANDAO_INFO, 41, new byte[0]));
        drain();

        int rmbBefore = rec.diamond;
        GamePacket makeup = new GamePacket(MsgIds.C2S_QIANDAO_PRIZE, 42,
                Pb.write(o -> Pb.int32(o, 1, today - 1)));
        signIn.onPrize(session, makeup);
        Assertions.assertEquals(rmbBefore, rec.diamond, "补签关着，不能白发奖");
        Assertions.assertEquals(1, rec.signIn.prizeStatus.get(today - 2).intValue(),
                "昨天那格必须仍是未领取(1)");
        Assertions.assertNull(firstOf(channel, MsgIds.S2C_QIANDAO_PRIZE), "补签被拒不应回 1803");
    }

    /**
     * 不接补签的配套：过去未领的格子下发成「已领」(2)。
     * 否则客户端把它画成可领（SignInSystem.cs:189-197）并真发 1402，而假服必拒 → 死点击。
     */
    @Test
    public void pastUnclaimedDaysAreSentAsClaimed() {
        int today = LocalDate.now().getDayOfMonth();
        if (today <= 1) {
            return; // 每月 1 号没有「过去」的格子
        }
        PlayerRecord rec = freshPlayer();
        GameSession session = bind(rec);
        signIn.onInfo(session, new GamePacket(MsgIds.C2S_QIANDAO_INFO, 41, new byte[0]));
        drain();

        List<Integer> st = Pb.read(dump.qianDaoInfo(rec)).getInts(4);
        Assertions.assertEquals(today, st.size(), "1801 的 list 长度=当天日号");
        for (int i = 0; i < today - 1; i++) {
            Assertions.assertEquals(2, st.get(i).intValue(),
                    "第 " + (i + 1) + " 天是过去未领 → 必须下发 2，不能让客户端显示成可领");
        }
        Assertions.assertEquals(1, st.get(today - 1).intValue(), "今天那格仍是未领(1)，可以领");
        Assertions.assertEquals(1, rec.signIn.prizeStatus.get(0).intValue(),
                "翻译只发生下发，存档里过去未领仍留 1");
    }

    /* ---------------- 工具 ---------------- */
    private PlayerRecord freshPlayer() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        rec.mails.clear();
        // mailGrantKeys 是「这封信只发一次」的防重表，落在存档里；不清掉的话第二次跑测试
        // 因为 key 已在档里，unlockOnce 不会再发接任务邮（onOnceList 后 mails 仍为空）。
        rec.economy.mailGrantKeys.clear();
        rec.signIn = new PlayerRecord.SignIn();
        rec.economy.chargedDiamond = 0;
        // 日常/主线两张表都要清成「全新账号」：测试数据落在 target/ 下会跨次运行残留。
        // 日常不清的话 finishTimes 还停在上次的完成值，addDaily 被封顶后不再变化 → 不推 1102。
        rec.tasks.daily.clear();
        // once 同理：unlockOnce 只在 `t == null` 分支发接任务邮（TaskService.java:303-308）。
        rec.tasks.once.clear();
        // 注意：rec.tasks.ensure()（PlayerRecord.Tasks.ensure :1485）只建集合，不会解锁主线、
        // 也不发接任务邮；真正跑 unlockOnce 的是 TaskService.ensure（703/701/登录时调）。
        rec.tasks.ensure();
        store.save(rec);
        return rec;
    }

    private GameSession bind(PlayerRecord rec) {
        channel = new EmbeddedChannel();
        GameSession session = new GameSession(channel);
        session.setAccount(ACCOUNT);
        session.setPlayer(rec);
        hub.bind(session);
        return session;
    }

    private List<GamePacket> drain() {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = channel.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
    }

    private static boolean has(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return true;
            }
        }
        return false;
    }

    private static byte[] bodyOf(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return p.body;
            }
        }
        return null;
    }

    private GamePacket firstOf(EmbeddedChannel ch, int msgId) {
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
            if (p.msgId == msgId) {
                return p;
            }
        }
        return null;
    }
}
