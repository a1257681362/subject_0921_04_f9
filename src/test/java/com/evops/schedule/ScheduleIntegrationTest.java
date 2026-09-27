package com.evops.schedule;

import com.evops.common.RequestContext;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.common.PayloadBizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 车辆档期集成测试（schedule-1）：区间重叠 + 前后缓冲 12 分钟边界、锁位 25 分钟超时自动释放、
 * 取消释放、冲突推荐最近空档、FOR UPDATE 并发只占一个、参数校验与入口幂等。
 *
 * LOCK_TIMEOUT 提高到 15s：并发用例中后到事务需等待先到事务释放车辆行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.sql.init.mode=always"
})
class ScheduleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private VehicleScheduleMapper scheduleMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle() throws Exception {
        String body = "{\"plateNo\":\"京S-" + reqNo().substring(0, 8) + "\",\"model\":\"Model-S\"}";
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), reqNo()).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    /** 创建占位，返回占位 id。 */
    private long book(long vehicleId, String start, String end) throws Exception {
        String body = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"张三\","
                + "\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/schedule/bookings"), reqNo()).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private int bookStatus(long vehicleId, String start, String end) throws Exception {
        String body = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"李四\","
                + "\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
        return mockMvc.perform(write(post("/api/schedule/bookings"), reqNo()).content(body))
                .andReturn().getResponse().getStatus();
    }

    // ---- 缓冲 12 分钟边界 ----

    @Test
    void bufferBoundaryExactly12MinutesPassesLessRejected() throws Exception {
        long vid = createVehicle();
        // 既有占用 [10:00, 11:00)，阻断段 [09:48, 11:12)
        book(vid, "2026-10-01T10:00:00", "2026-10-01T11:00:00");

        // 后界：起点正好 11:12（相隔 12 分钟）放行
        assertEquals(200, bookStatus(vid, "2026-10-01T11:12:00", "2026-10-01T12:00:00"));
        // 后界：11:11（仅隔 11 分钟，落在 12 分钟缓冲内）拒绝
        assertEquals(409, bookStatus(vid, "2026-10-01T11:11:00", "2026-10-01T12:00:00"));

        long vid2 = createVehicle();
        book(vid2, "2026-10-01T10:00:00", "2026-10-01T11:00:00");
        // 前界：上一单终点正好 09:48（相隔 12 分钟）放行
        assertEquals(200, bookStatus(vid2, "2026-10-01T08:00:00", "2026-10-01T09:48:00"));
        // 前界：终点 09:49（间隔 11 分钟）拒绝
        assertEquals(409, bookStatus(vid2, "2026-10-01T08:00:00", "2026-10-01T09:49:00"));
    }

    @Test
    void overlapAllShapesRejected() throws Exception {
        long vid = createVehicle();
        book(vid, "2026-10-02T10:00:00", "2026-10-02T11:00:00");
        // 完全重叠 / 部分重叠 / 包含 / 被包含，均拒绝
        assertEquals(409, bookStatus(vid, "2026-10-02T10:00:00", "2026-10-02T11:00:00"));
        assertEquals(409, bookStatus(vid, "2026-10-02T10:30:00", "2026-10-02T11:30:00"));
        assertEquals(409, bookStatus(vid, "2026-10-02T09:00:00", "2026-10-02T12:00:00"));
        assertEquals(409, bookStatus(vid, "2026-10-02T10:15:00", "2026-10-02T10:45:00"));
    }

    // ---- 锁位、确认、取消 ----

    @Test
    void createdHoldExpirySetConfirmTransitionsAndConfirmedBlocks() throws Exception {
        long vid = createVehicle();
        long id = book(vid, "2026-10-03T10:00:00", "2026-10-03T11:00:00");

        mockMvc.perform(get("/api/schedule/bookings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.holdExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.bufferMinutes").value(12));

        // 确认 -> CONFIRMED
        mockMvc.perform(write(post("/api/schedule/bookings/" + id + "/confirm"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());

        // 重复确认幂等
        mockMvc.perform(write(post("/api/schedule/bookings/" + id + "/confirm"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // CONFIRMED 仍计占用：重叠时段拒绝
        assertEquals(409, bookStatus(vid, "2026-10-03T10:30:00", "2026-10-03T11:30:00"));

        // 取消 -> CANCELLED
        mockMvc.perform(write(post("/api/schedule/bookings/" + id + "/cancel"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());

        // 释放后原冲突时段立即可再约
        assertEquals(200, bookStatus(vid, "2026-10-03T10:30:00", "2026-10-03T11:30:00"));
    }

    @Test
    void cancelReleasesSlotForRebooking() throws Exception {
        long vid = createVehicle();
        long id = book(vid, "2026-10-04T09:00:00", "2026-10-04T10:00:00");
        mockMvc.perform(write(post("/api/schedule/bookings/" + id + "/cancel"), reqNo()))
                .andExpect(status().isOk());
        // 取消后完全同一时段可再约（CANCELLED 不参与占用）
        assertEquals(200, bookStatus(vid, "2026-10-04T09:00:00", "2026-10-04T10:00:00"));
    }

    // ---- 锁位 25 分钟超时 ----

    /** 直接造一个锁位已到期的 HOLD（hold_expires_at 在过去）。 */
    private long insertExpiredHold(long vehicleId, String start, String end) {
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            VehicleSchedule b = new VehicleSchedule();
            b.setBookingNo("BK-EXP-" + reqNo().substring(0, 10));
            b.setVehicleId(vehicleId);
            b.setCustomerName("超时客户");
            b.setStartAt(LocalDateTime.parse(start));
            b.setEndAt(LocalDateTime.parse(end));
            b.setBufferMinutes(12);
            b.setStatus(VehicleSchedule.STATUS_HOLD);
            b.setHoldExpiresAt(LocalDateTime.now().minusMinutes(1));
            scheduleMapper.insert(b);
            return b.getId();
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void confirmAfterHoldExpiredRejectsAndReleases() throws Exception {
        long vid = createVehicle();
        long id = insertExpiredHold(vid, "2026-10-05T14:00:00", "2026-10-05T15:00:00");

        // 确认已超时占位：拒绝且即时置 EXPIRED
        mockMvc.perform(write(post("/api/schedule/bookings/" + id + "/confirm"), reqNo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("自动释放")));
        mockMvc.perform(get("/api/schedule/bookings/" + id))
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.expiredAt").isNotEmpty());

        // 释放后该时段可再约
        assertEquals(200, bookStatus(vid, "2026-10-05T14:00:00", "2026-10-05T15:00:00"));
    }

    @Test
    void scheduledScanReleasesExpiredHolds() throws Exception {
        long vid = createVehicle();
        long id = insertExpiredHold(vid, "2026-10-06T16:00:00", "2026-10-06T17:00:00");

        // 手工触发超时释放作业（与周期扫描同一服务方法）
        mockMvc.perform(write(post("/api/schedule/release-expired"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.released").isNumber());

        mockMvc.perform(get("/api/schedule/bookings/" + id))
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));
        // 释放后档期可被新预约使用
        assertEquals(200, bookStatus(vid, "2026-10-06T16:00:00", "2026-10-06T17:00:00"));
    }

    // ---- 冲突推荐最近空档 ----

    @Test
    void conflictReturnsRecommendedNearestSlot() throws Exception {
        long vid = createVehicle();
        long occupied = book(vid, "2026-10-07T10:00:00", "2026-10-07T11:00:00");

        // 期望 [10:30,11:30) 与既有占用冲突，阻断段 [09:48,11:12)：最近空档 [11:12,12:12)
        String body = "{\"vehicleId\":" + vid + ",\"customerName\":\"王五\","
                + "\"startAt\":\"2026-10-07T10:30:00\",\"endAt\":\"2026-10-07T11:30:00\"}";
        MvcResult result = mockMvc.perform(write(post("/api/schedule/bookings"), reqNo()).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.data.recommendedSlot.startAt").value("2026-10-07T11:12:00"))
                .andExpect(jsonPath("$.data.recommendedSlot.endAt").value("2026-10-07T12:12:00"))
                .andExpect(jsonPath("$.data.bufferMinutes").value(12))
                .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(occupied, payload.get("conflictBookingId").asLong());

        // 试算接口给出同样的最近空档（仅读，不写入）
        mockMvc.perform(get("/api/schedule/vehicles/" + vid + "/free-slot")
                        .param("startAt", "2026-10-07T10:30:00")
                        .param("endAt", "2026-10-07T11:30:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startAt").value("2026-10-07T11:12:00"))
                .andExpect(jsonPath("$.data.endAt").value("2026-10-07T12:12:00"));
    }

    @Test
    void recommendSlotThreadsBetweenMultipleOccupancies() throws Exception {
        long vid = createVehicle();
        // 占用 A [09:00,10:00) 阻断 [08:48,10:12)；占用 B [11:00,12:00) 阻断 [10:48,12:12)
        book(vid, "2026-10-08T09:00:00", "2026-10-08T10:00:00");
        book(vid, "2026-10-08T11:00:00", "2026-10-08T12:00:00");

        // 期望 30 分钟 [10:00,10:30)：A 后推到 10:12，窗口 [10:12,10:42) 落在 B 阻断起点 10:48 之前
        mockMvc.perform(get("/api/schedule/vehicles/" + vid + "/free-slot")
                        .param("startAt", "2026-10-08T10:00:00")
                        .param("endAt", "2026-10-08T10:30:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startAt").value("2026-10-08T10:12:00"))
                .andExpect(jsonPath("$.data.endAt").value("2026-10-08T10:42:00"));
    }

    // ---- 并发防双占 ----

    @Test
    void parallelBookingsForSameVehicleOnlyOneWins() throws Exception {
        long vid = createVehicle();
        ScheduleCreateRequest a = buildRequest(vid,
                "2026-10-09T10:00:00", "2026-10-09T11:00:00", reqNo());
        ScheduleCreateRequest b = buildRequest(vid,
                "2026-10-09T10:00:00", "2026-10-09T11:00:00", reqNo());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        Runnable task = () -> {
            ready.countDown();
            try {
                go.await();
                scheduleService.create(
                        Thread.currentThread().getName().equals("T1") ? a : b);
                success.incrementAndGet();
            } catch (PayloadBizException ex) {
                conflict.incrementAndGet();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };

        Thread t1 = new Thread(() -> runWithContext(task), "T1");
        Thread t2 = new Thread(() -> runWithContext(task), "T2");
        t1.start();
        t2.start();
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        t1.join(10000);
        t2.join(10000);

        assertEquals(1, success.get(), "同一车辆同一时段并行预约必须只成功一个");
        assertEquals(1, conflict.get(), "败者应收到档期冲突");
        // 库内该时段仅有一个生效占位
        long count = scheduleMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getVehicleId, vid)
                        .in(VehicleSchedule::getStatus,
                                VehicleSchedule.STATUS_HOLD, VehicleSchedule.STATUS_CONFIRMED));
        assertEquals(1L, count);
    }

    private void runWithContext(Runnable task) {
        RequestContext.set("para-tester", LocalDateTime.now(), "PARA-" + reqNo());
        try {
            task.run();
        } finally {
            RequestContext.clear();
        }
    }

    private ScheduleCreateRequest buildRequest(long vehicleId, String start, String end, String bookingNo) {
        ScheduleCreateRequest r = new ScheduleCreateRequest();
        r.setBookingNo("BK-" + bookingNo.substring(0, 12));
        r.setVehicleId(vehicleId);
        r.setCustomerName("并发客户");
        r.setStartAt(LocalDateTime.parse(start));
        r.setEndAt(LocalDateTime.parse(end));
        return r;
    }

    // ---- 校验 / 幂等 / 车辆状态 ----

    @Test
    void invalidWindowMissingFieldsAndHeadersRejected() throws Exception {
        long vid = createVehicle();
        // 结束不晚于开始：业务冲突 409
        assertEquals(409, bookStatus(vid, "2026-10-10T11:00:00", "2026-10-10T11:00:00"));

        // 缺客户姓名：参数校验 400
        String badBody = "{\"vehicleId\":" + vid + ",\"startAt\":\"2026-10-10T08:00:00\","
                + "\"endAt\":\"2026-10-10T09:00:00\"}";
        mockMvc.perform(post("/api/schedule/bookings")
                        .header("X-Operator", "tester").header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isBadRequest());

        // 缺写请求头：400
        mockMvc.perform(post("/api/schedule/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":" + vid + ",\"customerName\":\"x\","
                                + "\"startAt\":\"2026-10-10T08:00:00\",\"endAt\":\"2026-10-10T09:00:00\"}"))
                .andExpect(status().isBadRequest());

        // 车辆不存在
        String body = "{\"vehicleId\":999999,\"customerName\":\"张三\","
                + "\"startAt\":\"2026-10-10T08:00:00\",\"endAt\":\"2026-10-10T09:00:00\"}";
        mockMvc.perform(write(post("/api/schedule/bookings"), reqNo()).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateSubmitSameRequestNoRejected() throws Exception {
        long vid = createVehicle();
        String sameRequestNo = reqNo();
        String body = "{\"vehicleId\":" + vid + ",\"customerName\":\"赵六\","
                + "\"startAt\":\"2026-10-11T10:00:00\",\"endAt\":\"2026-10-11T11:00:00\"}";
        mockMvc.perform(write(post("/api/schedule/bookings"), sameRequestNo).content(body))
                .andExpect(status().isOk());
        // 同请求号重复提交：入口幂等 409
        mockMvc.perform(write(post("/api/schedule/bookings"), sameRequestNo).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void scheduleListAndStatusFilterWorks() throws Exception {
        long vid = createVehicle();
        long kept = book(vid, "2026-10-12T10:00:00", "2026-10-12T11:00:00");
        long cancelled = book(vid, "2026-10-12T13:00:00", "2026-10-12T14:00:00");
        mockMvc.perform(write(post("/api/schedule/bookings/" + cancelled + "/cancel"), reqNo()))
                .andExpect(status().isOk());

        // 仅 HOLD/CONFIRMED
        mockMvc.perform(get("/api/schedule/bookings").param("vehicleId", String.valueOf(vid))
                        .param("status", "HOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(kept));

        // 不传状态返回全部（含 CANCELLED），按开始时间升序
        mockMvc.perform(get("/api/schedule/bookings").param("vehicleId", String.valueOf(vid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(kept));
        assertNotNull(kept);
    }
}
