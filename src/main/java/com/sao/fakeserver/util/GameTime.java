package com.sao.fakeserver.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 全服统一时钟：上海时区。
 *
 * <p>日界、日期键（dateKey/today）、活动档期、冷却时间戳一律走这里，避免依赖主机时区 ——
 * 主机若不是 +08，登录补扫/0 点任务/跨日重置的日界会与客户端（+08）不一致。
 *
 * <p>注意：{@code System.currentTimeMillis()} 是绝对时刻、与时区无关，不要改。
 */
public final class GameTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private GameTime() {
    }

    /** 上海时区的当天日期。 */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 上海时区的当前本地时间（不含时区偏移，与存档里存的本地时间戳同一口径）。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 上海时区的当前时刻（时分秒，用于时段闸门/活动钟点判定）。 */
    public static LocalTime localTime() {
        return LocalTime.now(ZONE);
    }

    /** 上海时区的当前月份（用于月键，如充值月卡扩展键）。 */
    public static YearMonth month() {
        return YearMonth.now(ZONE);
    }
}
