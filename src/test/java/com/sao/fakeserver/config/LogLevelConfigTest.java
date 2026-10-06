package com.sao.fakeserver.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 日志级别开关 {@code sao.log-level} 的落地校验。
 *
 * <p>为什么值得钉：上云要靠这一个键把日志整体关掉（{@code WARN}/{@code OFF}），
 * 排错时才临时打开（{@code DEBUG}）。如果 {@code application.yml} 里
 * {@code logging.level.*} 的占位符没解析出来，Spring Boot 只会打一行 warning 然后
 * <b>悄悄退回默认 INFO</b> —— 表现就是「云上照样刷日志」，很难发现。所以这里断言两件事：
 * <ol>
 *   <li>占位符确实解析成了 {@code sao.log-level} 的值（没解析出来会留成字面量 {@code ${sao.log-level:INFO}}）；</li>
 *   <li>Logback 里 {@code com.sao.fakeserver} 的<b>生效级别</b>等于该值（说明 Spring 真的应用到了日志系统）。</li>
 * </ol>
 *
 * <p>方案与运维命令见 {@code docs/CLOUD_RELEASE.md}「日志级别：上云默认关，排错才开」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // 与其它 Spring 测试同样的隔离：别碰 live 的 data/ 与 12345 端口。
        "sao.data-dir=target/test-data/players-log-level",
        "sao.world-dir=target/test-data/world-log-level",
        "sao.game-port=0",
        "sao.enforce-login=false",
        // KfzNpcBootstrap 的下限是 20（见该类 :78），本用例只验日志级别，取最小即可。
        "sao.kfz-npc-count=20"
})
class LogLevelConfigTest {

    @Autowired
    private Environment env;

    @Test
    void logLevelKnobIsWiredAndApplied() {
        String configured = env.getProperty("sao.log-level");
        assertNotNull(configured, "application.yml 必须定义 sao.log-level");
        assertFalse(configured.startsWith("$"),
                "sao.log-level 不该是未解析的占位符：" + configured);

        assertEquals(configured, env.getProperty("logging.level.root"),
                "logging.level.root 必须跟随 sao.log-level");
        assertEquals(configured, env.getProperty("logging.level.com.sao.fakeserver"),
                "logging.level.com.sao.fakeserver 必须跟随 sao.log-level");

        Level expected = Level.toLevel(configured, null);
        assertNotNull(expected, "sao.log-level 必须是合法日志级别：" + configured);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertEquals(expected, ctx.getLogger("com.sao.fakeserver").getEffectiveLevel(),
                "Logback 生效级别必须等于 sao.log-level（不等于说明 Spring 没应用上去）");
    }
}
