package com.evops.schedule;

import com.evops.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 锁位超时自动释放作业（schedule-1）。
 *
 * 按固定间隔（evops.schedule.release-scan-interval-ms，默认每分钟）扫描所有到期未确认的
 * HOLD 占位，置 EXPIRED 释放档期。确认入口也会即时判定超时，本扫描是兜底，确保即便
 * 客户不再操作、占位也不会长期挂住车辆档期。
 *
 * evops.schedule.enabled=false 为应急总开关（不影响手工 POST /api/schedule/release-expired）。
 */
@Component
public class ScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleScheduler.class);

    private final ScheduleService scheduleService;
    private final ScheduleProperties properties;

    public ScheduleScheduler(ScheduleService scheduleService, ScheduleProperties properties) {
        this.scheduleService = scheduleService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${evops.schedule.release-scan-interval-ms:60000}",
            initialDelayString = "${evops.schedule.release-scan-interval-ms:60000}")
    public void releaseExpiredHolds() {
        if (!properties.isEnabled()) {
            return;
        }
        RequestContext.Ctx previous = currentContext();
        String requestNo = "JOB-SCHEDULE-RELEASE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RequestContext.set(ScheduleService.SYS_OPERATOR, LocalDateTime.now(), requestNo);
        try {
            int released = scheduleService.releaseExpiredHolds();
            if (released > 0) {
                log.info("档期锁位超时释放完成，本次释放 {} 个占位", released);
            }
        } catch (Exception ex) {
            log.error("档期锁位超时释放失败，下轮重试", ex);
        } finally {
            restore(previous);
        }
    }

    private static RequestContext.Ctx currentContext() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static void restore(RequestContext.Ctx previous) {
        if (previous == null) {
            RequestContext.clear();
        } else {
            RequestContext.set(previous.getOperator(), previous.getBizTime(), previous.getRequestNo());
        }
    }
}
