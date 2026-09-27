package com.evops.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 车辆档期配置（schedule-1）。
 *
 * - buffer-minutes：预约占用前后各留的缓冲分钟数，冲突判定把占用段扩成
 *   [start-buffer, end+buffer)；默认 12。
 * - hold-ttl-minutes：锁位有效期。创建占位即 HOLD，超过该分钟数仍未确认
 *   由周期作业自动置 EXPIRED 释放；默认 25。
 * - release-scan-interval-ms：超时释放扫描周期；enabled=false 为应急总开关（不影响手工触发释放）。
 */
@Component
@ConfigurationProperties(prefix = "evops.schedule")
public class ScheduleProperties {

    /** 前后缓冲分钟数（默认 12） */
    private int bufferMinutes = 12;

    /** 锁位有效期分钟数（默认 25，超时未确认自动释放） */
    private int holdTtlMinutes = 25;

    /** 周期扫描总开关（false 不影响手工触发释放/确认入口的到期判定） */
    private boolean enabled = true;

    /** 超时释放扫描间隔（毫秒），默认每分钟跑一次 */
    private long releaseScanIntervalMs = 60_000L;

    /** 启动即校验配置合法，避免上线后才发现参数错误 */
    @PostConstruct
    public void validate() {
        if (bufferMinutes < 0) {
            throw new IllegalStateException("evops.schedule.buffer-minutes 不能为负：" + bufferMinutes);
        }
        if (holdTtlMinutes <= 0) {
            throw new IllegalStateException("evops.schedule.hold-ttl-minutes 必须为正数：" + holdTtlMinutes);
        }
        if (releaseScanIntervalMs <= 0) {
            throw new IllegalStateException(
                    "evops.schedule.release-scan-interval-ms 必须为正数：" + releaseScanIntervalMs);
        }
    }

    public int getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(int bufferMinutes) { this.bufferMinutes = bufferMinutes; }
    public int getHoldTtlMinutes() { return holdTtlMinutes; }
    public void setHoldTtlMinutes(int holdTtlMinutes) { this.holdTtlMinutes = holdTtlMinutes; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getReleaseScanIntervalMs() { return releaseScanIntervalMs; }
    public void setReleaseScanIntervalMs(long releaseScanIntervalMs) {
        this.releaseScanIntervalMs = releaseScanIntervalMs;
    }
}
