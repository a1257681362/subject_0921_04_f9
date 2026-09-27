package com.evops.schedule.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 车辆档期占位请求。startAt/endAt 为客户实际占用区间（不含缓冲），
 * 前后缓冲分钟数取系统配置（默认 12），由服务端在冲突判定时统一扩展。
 */
public class ScheduleCreateRequest {

    /** 占位号；不传由系统生成。库层唯一约束兜底。 */
    @Size(max = 40, message = "占位号长度不能超过 40")
    private String bookingNo;

    @NotNull(message = "车辆ID不能为空")
    private Long vehicleId;

    @NotBlank(message = "客户姓名不能为空")
    @Size(max = 64, message = "客户姓名长度不能超过 64")
    private String customerName;

    @NotNull(message = "预约开始时间不能为空")
    private LocalDateTime startAt;

    @NotNull(message = "预约结束时间不能为空")
    private LocalDateTime endAt;

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
}
