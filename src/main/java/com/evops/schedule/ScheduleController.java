package com.evops.schedule;

import com.evops.common.ApiResponse;
import com.evops.schedule.dto.FreeSlot;
import com.evops.schedule.dto.ScheduleCreateRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 车辆档期/预约占位接口（schedule-1）。
 *
 * 写请求同样遵循三件套（X-Operator / X-Request-No / 可选 X-Biz-Time）与入口幂等。
 * 创建占位冲突（缓冲不足或时间重叠）返回 409，code=SCHEDULE_CONFLICT，
 * data 内带系统推荐的最近空档；前端可直接据此回填时间重试。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 创建占位（HOLD，锁位 25 分钟）；冲突 409 并推荐最近空档。 */
    @PostMapping("/bookings")
    public ApiResponse<VehicleSchedule> create(@Valid @RequestBody ScheduleCreateRequest request) {
        return ApiResponse.ok(scheduleService.create(request));
    }

    /** 确认占位：锁位到期未确认会被拒绝（占位已自动释放）。 */
    @PostMapping("/bookings/{id}/confirm")
    public ApiResponse<VehicleSchedule> confirm(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.confirm(id));
    }

    /** 取消占位：立即释放档期，可被新预约使用。 */
    @PostMapping("/bookings/{id}/cancel")
    public ApiResponse<VehicleSchedule> cancel(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.cancel(id));
    }

    /** 占位详情。 */
    @GetMapping("/bookings/{id}")
    public ApiResponse<VehicleSchedule> detail(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.getById(id));
    }

    /** 档期查询：车辆 + 状态 + 开始时间范围，按开始时间升序。 */
    @GetMapping("/bookings")
    public ApiResponse<List<VehicleSchedule>> list(
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to) {
        return ApiResponse.ok(scheduleService.list(vehicleId, status, from, to));
    }

    /** 试算最近空档：给定期望占用区间，返回该车辆可容纳的最近可用区间（已扣除前后缓冲）。 */
    @GetMapping("/vehicles/{vehicleId}/free-slot")
    public ApiResponse<FreeSlot> freeSlot(
            @PathVariable Long vehicleId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
        return ApiResponse.ok(scheduleService.recommendFreeSlot(vehicleId, startAt, endAt));
    }

    /** 手工触发超时释放（应急/排障）：返回本次释放数量。 */
    @PostMapping("/release-expired")
    public ApiResponse<java.util.Map<String, Integer>> releaseExpired() {
        return ApiResponse.ok(Collections.singletonMap("released", scheduleService.releaseExpiredHolds()));
    }
}
