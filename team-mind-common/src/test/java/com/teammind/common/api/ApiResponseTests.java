package com.teammind.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTests {

    @Test
    void wrapsSuccessfulData() {
        ApiResponse<String> response = ApiResponse.success("ready");

        assertEquals("0", response.code());
        assertEquals("ready", response.data());
        assertNull(response.errors());
    }
}
