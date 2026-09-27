package com.evops.schedule;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * 车辆档期/预约占位（schedule-1）。
 *
 * 生命周期：
 * <pre>
 *   创建 HOLD（锁位 25 分钟） --确认--> CONFIRMED
 *   HOLD --主动取消--> CANCELLED（释放，留痕）
 *   HOLD --超时未确认--> EXPIRED（自动释放，留痕）
 * </pre>
 * 仅 {@link #STATUS_HOLD} / {@link #STATUS_CONFIRMED} 计档期占用；
 * CANCELLED / EXPIRED 为释放态，行保留但不参与重叠判定与空档推荐。
 *
 * 冲突按区间重叠判定，占用段前后各扩 {@link #bufferMinutes} 分钟缓冲，
 * 取半开区间 [start-buffer, end+buffer)，端点相隔正好一个缓冲放行、不足即拒。
 */
@TableName("t_vehicle_schedule")
public class VehicleSchedule extends BizEntity {

    /** 锁位中：已占位、等待确认（默认 25 分钟内须确认，否则自动释放） */
    public static final String STATUS_HOLD = "HOLD";
    /** 已确认：档期正式锁定 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 已取消：主动释放，不参与占用 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 超时失效：锁位到期未确认，自动释放，不参与占用 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 预约占位号（库层唯一，幂等/追溯用） */
    private String bookingNo;

    /** 被预约车辆 */
    private Long vehicleId;

    /** 预约客户（现场联系人） */
    private String customerName;

    /** 预约占用开始（业务时间，不含缓冲） */
    private LocalDateTime startAt;

    /** 预约占用结束（业务时间，不含缓冲） */
    private LocalDateTime endAt;

    /** 前后缓冲分钟数（创建时按配置快照） */
    private Integer bufferMinutes;

    /** HOLD / CONFIRMED / CANCELLED / EXPIRED */
    private String status;

    /** 锁位到期时刻：HOLD 超过此刻仍未确认即自动释放 */
    private LocalDateTime holdExpiresAt;

    /** 确认时刻 */
    private LocalDateTime confirmedAt;
    /** 取消时刻（主动释放） */
    private LocalDateTime cancelledAt;
    /** 超时失效时刻（自动释放） */
    private LocalDateTime expiredAt;

    public String getBookingNo() { return bookingNo; }
    public void setBookingNo(String bookingNo) { this.bookingNo = bookingNo; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public Integer getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(Integer bufferMinutes) { this.bufferMinutes = bufferMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public void setHoldExpiresAt(LocalDateTime holdExpiresAt) { this.holdExpiresAt = holdExpiresAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime getExpiredAt() { return expiredAt; }
    public void setExpiredAt(LocalDateTime expiredAt) { this.expiredAt = expiredAt; }
}
