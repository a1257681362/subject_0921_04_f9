package com.evops.common;

/**
 * 业务规则冲突，但需向调用方回传结构化数据（典型场景：档期冲突时附带系统推荐的最近空档）。
 * 与 {@link BizException} 同为 409，区别仅在于响应体多一个 data 负载与明确错误码。
 */
public class PayloadBizException extends BizException {

    private final String code;
    private final transient Object payload;

    public PayloadBizException(String code, String message, Object payload) {
        super(message);
        this.code = code;
        this.payload = payload;
    }

    public String getCode() { return code; }
    public Object getPayload() { return payload; }
}
