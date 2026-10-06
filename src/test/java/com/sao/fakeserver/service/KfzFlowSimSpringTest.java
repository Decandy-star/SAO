package com.sao.fakeserver.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 需要时才跑：显式调 {@link KfzFlowSimRunner#verify()}（日志 kfz flow sim PASS/FAIL）。
 * 日常启服不执行冒烟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.kfz-npc-count=24",
        "sao.data-dir=./data/players-test-kfz-sim",
        "sao.world-dir=./data/world-test-kfz-sim",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class KfzFlowSimSpringTest {
    @Autowired
    private KfzFlowSimRunner runner;

    @Test
    public void kfzSmokeVerify() {
        org.junit.jupiter.api.Assertions.assertNotNull(runner);
        runner.verify();
    }
}
