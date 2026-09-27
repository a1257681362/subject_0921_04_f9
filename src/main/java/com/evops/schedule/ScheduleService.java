package com.evops.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.common.PayloadBizException;
import com.evops.schedule.dto.FreeSlot;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 车辆档期/预约占位（schedule-1）。
 *
 * 冲突判定（区间重叠 + 前后缓冲）：
 * <ul>
 *   <li>把每条<b>既有占用</b>（HOLD/CONFIRMED）扩成阻断段
 *       {@code [start - buffer, end + buffer)}，与新预约的<b>原始</b>区间做半开区间严格相交：
 *       {@code newStart < blockedEnd && newEnd > blockedStart} 即冲突。</li>
 *   <li>缓冲只施加一次（在既有占用一侧），因此相邻两单间隔正好一个缓冲（12 分钟）即放行，
 *       不会因双方各算一次缓冲而被误判成需要 24 分钟；落在 12 分钟内（间隔不足）拒绝。</li>
 *   <li>仅 HOLD/CONFIRMED 计占用；CANCELLED/EXPIRED 行保留留痕但不参与判定；
 *       已过 hold_expires_at 但尚未被扫描清理的 HOLD 也即时视为已释放。</li>
 * </ul>
 *
 * 锁位与释放：创建即 HOLD，{@code hold_expires_at = now + 25min}；确认时二次校验未超时；
 * 取消（HOLD/CONFIRMED 均可）或超时未确认（周期扫描/确认入口）置释放态。
 *
 * 并发：同车创建在事务内先 SELECT ... FOR UPDATE 锁住车辆行串行化，并行预约不会双占。
 *
 * 本期不涉及审批流（相关需求进下期）。
 */
@Service
public class ScheduleService {

    /** 档期冲突错误码（前端凭此读取响应 data 中的推荐空档） */
    public static final String CODE_CONFLICT = "SCHEDULE_CONFLICT";
    /** 系统作业操作人 */
    public static final String SYS_OPERATOR = "SYS_SCHEDULE";

    private static final DateTimeFormatter BOOKING_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VehicleScheduleMapper scheduleMapper;
    private final VehicleMapper vehicleMapper;
    private final ScheduleProperties properties;
    private final ScheduleExpiryMarker expiryMarker;

    public ScheduleService(VehicleScheduleMapper scheduleMapper,
                           VehicleMapper vehicleMapper,
                           ScheduleProperties properties,
                           ScheduleExpiryMarker expiryMarker) {
        this.scheduleMapper = scheduleMapper;
        this.vehicleMapper = vehicleMapper;
        this.properties = properties;
        this.expiryMarker = expiryMarker;
    }

    /**
     * 创建占位：校验车辆与区间 -> 锁车辆行 -> 区间重叠判定 -> 落 HOLD（25 分钟锁位）。
     * 冲突时抛 {@link PayloadBizException}，data 内含冲突占位与推荐最近空档。
     */
    @Transactional(rollbackFor = Exception.class)
    public VehicleSchedule create(ScheduleCreateRequest request) {
        LocalDateTime startAt = request.getStartAt();
        LocalDateTime endAt = request.getEndAt();
        if (!endAt.isAfter(startAt)) {
            throw new BizException("预约结束时间必须晚于开始时间");
        }

        // 锁住被预约车辆行：同车并发预约在此串行，防止一车两约
        Vehicle vehicle = scheduleMapper.lockVehicleById(request.getVehicleId());
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + request.getVehicleId());
        }
        if ("DISABLED".equals(vehicle.getStatus())) {
            throw new BizException("车辆已停用，不能预约：" + vehicle.getPlateNo());
        }

        LocalDateTime now = LocalDateTime.now();
        List<Interval> blocked = blockedIntervals(request.getVehicleId(), now);

        Interval target = new Interval(startAt, endAt);
        Interval collided = firstCollision(blocked, target);
        if (collided != null) {
            FreeSlot recommended = earliestFreeSlot(blocked, startAt, endAt);
            throw new PayloadBizException(CODE_CONFLICT,
                    "该车辆所选时段与既有预约间隔不足 " + properties.getBufferMinutes()
                            + " 分钟缓冲（或时间重叠），已为您推荐最近空档",
                    new ConflictPayload(vehicle.getId(), vehicle.getPlateNo(),
                            properties.getBufferMinutes(), collided.bookingId, recommended));
        }

        VehicleSchedule booking = new VehicleSchedule();
        booking.setBookingNo(request.getBookingNo() != null && !request.getBookingNo().trim().isEmpty()
                ? request.getBookingNo().trim() : generateBookingNo());
        booking.setVehicleId(vehicle.getId());
        booking.setCustomerName(request.getCustomerName().trim());
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setBufferMinutes(properties.getBufferMinutes());
        booking.setStatus(VehicleSchedule.STATUS_HOLD);
        booking.setHoldExpiresAt(now.plusMinutes(properties.getHoldTtlMinutes()));
        scheduleMapper.insert(booking);
        return booking;
    }

    /** 确认占位：仅 HOLD 可确认；锁位已到期则即时置 EXPIRED 并拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public VehicleSchedule confirm(Long bookingId) {
        VehicleSchedule booking = requireBooking(bookingId);
        LocalDateTime now = LocalDateTime.now();
        if (VehicleSchedule.STATUS_HOLD.equals(booking.getStatus())) {
            if (booking.getHoldExpiresAt() != null && !now.isBefore(booking.getHoldExpiresAt())) {
                // 已超时：独立事务即时置 EXPIRED 释放（不被随后抛出的业务异常回滚带走），
                // 不必等到周期扫描；再以业务异常拒绝本次确认。
                expiryMarker.markExpired(booking.getId(), now);
                throw new BizException("锁位已超过 " + properties.getHoldTtlMinutes()
                        + " 分钟未确认，占位已自动释放，请重新预约");
            }
            booking.setStatus(VehicleSchedule.STATUS_CONFIRMED);
            booking.setConfirmedAt(now);
            updateCas(booking);
            return booking;
        }
        if (VehicleSchedule.STATUS_CONFIRMED.equals(booking.getStatus())) {
            return booking; // 重复确认幂等
        }
        throw new BizException("当前占位状态为 " + booking.getStatus() + "，不能确认");
    }

    /** 取消占位（主动释放）：HOLD/CONFIRMED 可取消；已取消幂等；已超时拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public VehicleSchedule cancel(Long bookingId) {
        VehicleSchedule booking = requireBooking(bookingId);
        if (VehicleSchedule.STATUS_CANCELLED.equals(booking.getStatus())) {
            return booking; // 重复取消幂等
        }
        if (VehicleSchedule.STATUS_EXPIRED.equals(booking.getStatus())) {
            throw new BizException("占位已超时失效，无需取消");
        }
        if (!VehicleSchedule.STATUS_HOLD.equals(booking.getStatus())
                && !VehicleSchedule.STATUS_CONFIRMED.equals(booking.getStatus())) {
            throw new BizException("当前占位状态为 " + booking.getStatus() + "，不能取消");
        }
        booking.setStatus(VehicleSchedule.STATUS_CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        updateCas(booking);
        return booking;
    }

    /**
     * 周期释放：把所有到期未确认的 HOLD 置 EXPIRED（单事务，逐行 CAS 防与手工确认打架）。
     * 由 {@link ScheduleScheduler} 在系统上下文中按固定间隔触发；整批失败下轮再扫，不影响数据正确性。
     *
     * @return 本次释放的占位数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now();
        List<VehicleSchedule> expired = scheduleMapper.selectList(
                new LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getStatus, VehicleSchedule.STATUS_HOLD)
                        .le(VehicleSchedule::getHoldExpiresAt, now));
        int released = 0;
        for (VehicleSchedule booking : expired) {
            booking.setStatus(VehicleSchedule.STATUS_EXPIRED);
            booking.setExpiredAt(now);
            if (updateCasQuietly(booking)) {
                released++;
            }
        }
        return released;
    }

    /** 占位详情。 */
    public VehicleSchedule getById(Long bookingId) {
        return requireBooking(bookingId);
    }

    /** 档期查询：按车辆 + 状态 + 时间范围过滤，开始时间升序。 */
    public List<VehicleSchedule> list(Long vehicleId, String status,
                                      LocalDateTime from, LocalDateTime to) {
        String trimmedStatus = status == null ? null : status.trim();
        if (trimmedStatus != null && trimmedStatus.isEmpty()) {
            trimmedStatus = null;
        }
        return scheduleMapper.selectList(new LambdaQueryWrapper<VehicleSchedule>()
                .eq(vehicleId != null, VehicleSchedule::getVehicleId, vehicleId)
                .eq(trimmedStatus != null, VehicleSchedule::getStatus, trimmedStatus)
                .ge(from != null, VehicleSchedule::getStartAt, from)
                .le(to != null, VehicleSchedule::getStartAt, to)
                .orderByAsc(VehicleSchedule::getStartAt)
                .orderByAsc(VehicleSchedule::getId));
    }

    /**
     * 试算最近空档：在期望开始时间（含）之后，找第一个能容纳 [desiredStart, desiredEnd)
     * 时长、且与既有占用前后缓冲不冲突的空档。返回客户原始可用区间（已扣除缓冲）。
     */
    public FreeSlot recommendFreeSlot(Long vehicleId, LocalDateTime desiredStart, LocalDateTime desiredEnd) {
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + vehicleId);
        }
        if (!desiredEnd.isAfter(desiredStart)) {
            throw new BizException("预约结束时间必须晚于开始时间");
        }
        List<Interval> blocked = blockedIntervals(vehicleId, LocalDateTime.now());
        return earliestFreeSlot(blocked, desiredStart, desiredEnd);
    }

    // ---- 区间/空档核心 ----

    /**
     * 某车辆当前生效占用展开成的阻断段（每段已在既有占用两侧并入其缓冲），已合并重叠。
     * 新预约的原始区间只要落入任一阻断段即冲突；放进阻断段之间的缝隙即满足缓冲。
     */
    private List<Interval> blockedIntervals(Long vehicleId, LocalDateTime now) {
        List<VehicleSchedule> active = scheduleMapper.selectList(
                new LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getVehicleId, vehicleId)
                        .in(VehicleSchedule::getStatus,
                                VehicleSchedule.STATUS_HOLD, VehicleSchedule.STATUS_CONFIRMED)
                        .orderByAsc(VehicleSchedule::getStartAt));
        List<Interval> intervals = new ArrayList<>();
        for (VehicleSchedule b : active) {
            // 已到锁位期限但尚未被扫描清理的 HOLD，即时视为已释放，不再阻挡后续预约
            if (VehicleSchedule.STATUS_HOLD.equals(b.getStatus())
                    && b.getHoldExpiresAt() != null && !now.isBefore(b.getHoldExpiresAt())) {
                continue;
            }
            int buf = b.getBufferMinutes() == null ? 0 : b.getBufferMinutes();
            intervals.add(new Interval(
                    b.getStartAt().minusMinutes(buf),
                    b.getEndAt().plusMinutes(buf),
                    b.getId()));
        }
        return merge(intervals);
    }

    /** 半开区间严格相交：target.start < blocked.end && target.end > blocked.start。 */
    private Interval firstCollision(List<Interval> blocked, Interval target) {
        for (Interval b : blocked) {
            if (target.start.isBefore(b.end) && target.end.isAfter(b.start)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 在合并后的阻断段时间轴上，从 desiredStart 起找第一个能容纳时长 d 的原始窗口。
     * 阻断段端点已含缓冲，故原始窗口起点贴阻断段终点、终点贴下一段起点即满足 12 分钟间隔。
     * 时长保留秒级精度（缓冲按整分钟配置在阻断段端点体现，与此正交）。
     */
    private FreeSlot earliestFreeSlot(List<Interval> blocked,
                                      LocalDateTime desiredStart, LocalDateTime desiredEnd) {
        java.time.Duration duration = java.time.Duration.between(desiredStart, desiredEnd);
        LocalDateTime candidate = desiredStart;
        for (Interval b : blocked) {
            LocalDateTime windowEnd = candidate.plus(duration);
            if (!windowEnd.isAfter(b.start)) {
                break; // 窗口在本段之前即可容纳
            }
            if (!candidate.isBefore(b.end)) {
                continue; // 本段完全在窗口之前
            }
            candidate = b.end; // 与本段重叠/间隔不足，起点推到本段终点
        }
        return new FreeSlot(candidate, candidate.plus(duration));
    }

    /** 合并相互重叠/相接的阻断段（相接也合并，缝隙为 0 时无意义，且 bookingId 取最早一条）。 */
    private List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing((Interval i) -> i.start).thenComparing(i -> i.end));
        List<Interval> merged = new ArrayList<>();
        for (Interval cur : sorted) {
            if (merged.isEmpty()) {
                merged.add(cur);
                continue;
            }
            Interval last = merged.get(merged.size() - 1);
            if (!cur.start.isAfter(last.end)) {
                if (cur.end.isAfter(last.end)) {
                    last.end = cur.end;
                }
            } else {
                merged.add(cur);
            }
        }
        return merged;
    }

    // ---- 辅助 ----

    private VehicleSchedule requireBooking(Long bookingId) {
        VehicleSchedule booking = scheduleMapper.selectById(bookingId);
        if (booking == null) {
            throw new BizException("档期占位不存在：" + bookingId);
        }
        return booking;
    }

    /** CAS 更新：并行确认/取消只有一个生效，败者整体回滚报并发冲突。 */
    private void updateCas(VehicleSchedule booking) {
        if (!updateCasQuietly(booking)) {
            throw new BizException("占位状态已被其他操作变更（并发冲突），请刷新后重试：" + booking.getId());
        }
    }

    private boolean updateCasQuietly(VehicleSchedule booking) {
        return scheduleMapper.updateById(booking) > 0;
    }

    private String generateBookingNo() {
        return "BK" + LocalDateTime.now().format(BOOKING_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 冲突响应负载：车辆信息 + 缓冲分钟 + 冲突占位 id + 推荐最近空档。 */
    public static class ConflictPayload {
        private final Long vehicleId;
        private final String plateNo;
        private final int bufferMinutes;
        private final Long conflictBookingId;
        private final FreeSlot recommendedSlot;

        public ConflictPayload(Long vehicleId, String plateNo, int bufferMinutes,
                               Long conflictBookingId, FreeSlot recommendedSlot) {
            this.vehicleId = vehicleId;
            this.plateNo = plateNo;
            this.bufferMinutes = bufferMinutes;
            this.conflictBookingId = conflictBookingId;
            this.recommendedSlot = recommendedSlot;
        }

        public Long getVehicleId() { return vehicleId; }
        public String getPlateNo() { return plateNo; }
        public int getBufferMinutes() { return bufferMinutes; }
        public Long getConflictBookingId() { return conflictBookingId; }
        public FreeSlot getRecommendedSlot() { return recommendedSlot; }
    }

    /** 内部半开区间 [start, end)；bookingId 标识产生该阻断段的占用（合并后取首条）。 */
    private static final class Interval {
        private final LocalDateTime start;
        private LocalDateTime end;
        private final Long bookingId;

        Interval(LocalDateTime start, LocalDateTime end) {
            this(start, end, null);
        }

        Interval(LocalDateTime start, LocalDateTime end, Long bookingId) {
            this.start = start;
            this.end = end;
            this.bookingId = bookingId;
        }
    }
}
