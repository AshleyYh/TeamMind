package com.teammind.web.exception;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTests.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTests.TestController.class})
class GlobalExceptionHandlerTests {

    private final MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    GlobalExceptionHandlerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsStableBusinessErrorResponse() throws Exception {
        mockMvc.perform(post("/test/business-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.BUSINESS_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("Example failure"))
                .andExpect(jsonPath("$.path").value("/test/business-error"));
    }

    @Test
    void returnsFieldViolations() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.getCode()))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingInternals() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.MALFORMED_REQUEST.getMessage()));
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @PostMapping("/business-error")
        void businessError() {
            throw new BusinessException("Example failure");
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody TestRequest request) {
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
