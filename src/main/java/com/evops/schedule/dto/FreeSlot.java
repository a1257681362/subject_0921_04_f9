package com.evops.schedule.dto;

import java.time.LocalDateTime;

/**
 * 一个可容纳指定租期的空档（客户实际占用区间，不含缓冲）。
 * 空档边界已扣除相邻占用的前后缓冲，直接可用。
 */
public class FreeSlot {

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    public FreeSlot() {}

    public FreeSlot(LocalDateTime startAt, LocalDateTime endAt) {
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
}
