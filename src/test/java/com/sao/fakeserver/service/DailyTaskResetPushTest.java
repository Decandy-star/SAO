package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.TaskTables;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

/**
 * 跨日日常重置必须推 S2C 1102（整份日常）。
 *
 * 背景：APK 的 {@code OnFirstUpdateDailyTask}（1101）被
 * {@code mAllDailyTaskInfoNeedSyncServer} 门控——该标志只在冷启为 true、收到 1101 后置 false，
 * 全工程无处置回 true，所以在线跨日的号收不到 1101；而服端此前跨日只清库不推包，
 * 客户端面板会一直停在昨天（显示已完成/已领，点领取被 {@code onPrizeDaily} 静默 return）。
 * 本测试钉住：跨日 {@link ProgressService#ensureDaily(PlayerRecord)} 必须发出 1102，
 * 且条目里 finishTimes=0、prized=false，条数等于日常表行数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // 相对路径按 surefire fork 的 CWD 解析（实测 = 模块根/target/classes），
        // 所以实际落在 target/classes/target/test-data/…：仍在 target 下，mvn clean 即清。
        // 注意：不能写 file: URL —— PlayerStore 里是 Paths.get(...)，带冒号会 InvalidPathException。
        "sao.data-dir=target/test-data/players-daily-reset",
        "sao.world-dir=target/test-data/world-daily-reset",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class DailyTaskResetPushTest {
    private static final String ACCOUNT = "test-daily-reset";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private ProgressService progress;
    @Autowired
    private TaskTables taskTables;
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

    @Test
    public void crossDayResetPushesFullDailyList() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.economy.dailyKey = LocalDate.now().minusDays(1).toString();
        rec.tasks.ensure();
        // 造「昨天已做完且已领完」的假象
        TaskTables.DailyCfg first = taskTables.dailyAll().iterator().next();
        PlayerRecord.DailyTask t0 = rec.tasks.daily(first.id);
        t0.finishTimes = first.finishCount;
        t0.prized = true;
        Assertions.assertTrue(first.id > 0);

        channel = new EmbeddedChannel();
        GameSession session = new GameSession(channel);
        session.setAccount(ACCOUNT);
        session.setPlayer(rec);
        hub.bind(session);

        boolean rolled = progress.ensureDaily(rec);
        Assertions.assertTrue(rolled, "dailyKey 是昨天，ensureDaily 必须判定跨日");
        Assertions.assertEquals(LocalDate.now().toString(), rec.economy.dailyKey);
        Assertions.assertEquals(0, t0.finishTimes, "跨日后日常完成次数必须清零");
        Assertions.assertFalse(t0.prized, "跨日后日常领取状态必须清零");

        GamePacket pkt = channel.readOutbound();
        int guard = 0;
        while (pkt != null && pkt.msgId != MsgIds.S2C_DAILY_TASK_UPDATE && guard++ < 8) {
            pkt = channel.readOutbound();
        }
        Assertions.assertNotNull(pkt, "跨日必须推包，不能静默清库");
        Assertions.assertEquals(MsgIds.S2C_DAILY_TASK_UPDATE, pkt.msgId, "跨日日常必须走 S2C 1102（1101 会被 APK 门控丢弃）");

        Pb.Fields f = Pb.read(pkt.body);
        List<byte[]> entries = f.getBytesList(1);
        Assertions.assertEquals(taskTables.dailyAll().size(), entries.size(), "1102 要重播整份日常，条数=表行数");
        for (byte[] one : entries) {
            Pb.Fields e = Pb.read(one);
            Assertions.assertTrue(e.getInt(1, 0) > 0, "日常条目必须带 taskId");
            Assertions.assertEquals(0, e.getInt(2, 0), "跨日条目 finishTimes 必须是 0");
            Assertions.assertFalse(e.getBool(3), "跨日条目 prized 必须是 false");
        }

        hub.unbind(session);
        store.save(rec);
    }
}
