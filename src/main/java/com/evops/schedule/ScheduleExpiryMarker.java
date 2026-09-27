package com.evops.schedule;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 锁位到期失效标记：独立事务（REQUIRES_NEW）提交。
 *
 * 确认接口在业务事务内发现锁位已超时，需要先把占位置 EXPIRED 释放档期、再以业务异常拒绝本次确认；
 * 若在同一事务里更新后抛异常，EXPIRED 会随业务事务一起回滚。故此处用独立事务落库，
 * 与 recon 的批次失败标记（markBatchFailed）同一套路。
 */
@Component
public class ScheduleExpiryMarker {

    private final VehicleScheduleMapper scheduleMapper;

    public ScheduleExpiryMarker(VehicleScheduleMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    /** 在独立事务中把到期 HOLD 置 EXPIRED；并发已变更（非 HOLD）则不覆盖，返回是否实际标记。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean markExpired(Long bookingId, LocalDateTime now) {
        VehicleSchedule booking = scheduleMapper.selectById(bookingId);
        if (booking == null || !VehicleSchedule.STATUS_HOLD.equals(booking.getStatus())) {
            return false;
        }
        booking.setStatus(VehicleSchedule.STATUS_EXPIRED);
        booking.setExpiredAt(now);
        return scheduleMapper.updateById(booking) > 0;
    }
}
