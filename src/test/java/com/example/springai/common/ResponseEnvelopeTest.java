package com.example.springai.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一信封的契约测试。
 *
 * <p>这个类里最重要的一个用例是 {@link #jsonKeyIsLiterallySuccess()} ——
 * 它把"JSON 里的 key 到底是 success 还是 isSuccess"锁死。
 * 一旦这个 key 变了，9 个模板里每一处 {@code data.success} 判断都会<b>静默失效</b>
 * （页面不报错，只是永远走失败分支），所以必须有测试守住。
 */
class ResponseEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jsonKeyIsLiterallySuccess() throws Exception {
        String json = objectMapper.writeValueAsString(Response.success("payload"));

        assertTrue(json.contains("\"success\":true"),
                "success 字段的 JSON key 必须是字面的 success，实际: " + json);
        assertFalse(json.contains("isSuccess"),
                "不应该出现 isSuccess —— 前端所有页面判断的都是 data.success。实际: " + json);
    }

    @Test
    void failureSerializesWithErrorFields() throws Exception {
        String json = objectMapper.writeValueAsString(
                Response.fail(ErrorCode.BAD_REQUEST, "用户名已被占用"));

        assertTrue(json.contains("\"success\":false"), json);
        assertTrue(json.contains("\"errCode\":\"400\""), json);
        assertTrue(json.contains("\"errMessage\":\"用户名已被占用\""), json);
    }

    @Test
    void successCarriesData() {
        Response<String> response = Response.success("answer");
        assertTrue(response.isSuccess());
        assertEquals("answer", response.getData());
        assertNull(response.getErrCode());
        assertNull(response.getErrMessage());
    }

    @Test
    void noArgSuccessHasNullDataButIsStillSuccessful() {
        Response<Void> response = Response.success();
        assertTrue(response.isSuccess());
        assertNull(response.getData());
    }

    @Test
    void failFromErrorCodeUsesItsDescription() {
        Response<Void> response = Response.fail(ErrorCode.FORBIDDEN);
        assertFalse(response.isSuccess());
        assertEquals("403", response.getErrCode());
        assertEquals(ErrorCode.FORBIDDEN.getErrDesc(), response.getErrMessage());
    }

    @Test
    void failWithCustomMessageOverridesDescription() {
        Response<Void> response = Response.fail(ErrorCode.UNAUTHORIZED, "该手机号已被注册");
        assertEquals("401", response.getErrCode());
        assertEquals("该手机号已被注册", response.getErrMessage());
    }

    @Test
    void pageResultDefaultsRecordsToEmptyList() {
        PageResult<String> page = PageResult.of(null, 0, 1, 10);
        assertEquals(0, page.getRecords().size());
        assertEquals(0, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(10, page.getSize());
    }
}
