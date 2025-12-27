package io.eventdriven.batchkafka.api.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * API 공통 응답 형식
 * - 모든 API 응답을 통일된 구조로 제공
 * - success, message, data 필드로 구성
 */
@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    /**
     * 성공 응답 (데이터 포함)
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "요청이 성공했습니다.", data);
    }

    /**
     * 성공 응답 (메시지 + 데이터)
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /**
     * 성공 응답 (메시지만)
     */
    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(true, message, null);
    }

    /**
     * 실패 응답
     */
    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }

    /**
     * 실패 응답 (데이터 포함)
     */
    public static <T> ApiResponse<T> fail(String message, T data) {
        return new ApiResponse<>(false, message, data);
    }
}
