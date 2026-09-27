package com.evops.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String message;
    /** 失败错误码：如 TOKEN_EXPIRED（访问令牌过期，前端凭此无感刷新）/UNAUTHORIZED/FORBIDDEN */
    private String code;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = "OK";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.code = code;
        response.message = message;
        return response;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getCode() { return code; }
    public T getData() { return data; }
}
