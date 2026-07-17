package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DialServiceTest {
    @Test
    void connectionNameValidation() {
        assertTrue(DialService.isValidConnectionName("pppoe_native_java"));
        assertFalse(DialService.isValidConnectionName("bad name"));
        assertFalse(DialService.isValidConnectionName("a[b]"));
    }

    @Test
    void describeFailureCodes() {
        String m691 = DialService.describeFailure(new DialService.DialResult(691, ""));
        assertNotNull(m691);
        assertTrue(m691.contains("账号或密码") || m691.contains("691"));

        String m678 = DialService.describeFailure(new DialService.DialResult(0, "error 678"));
        assertNotNull(m678);
        assertTrue(m678.contains("服务器") || m678.contains("678"));

        String timeout = DialService.describeFailure(new DialService.DialResult(-1, ""));
        assertNotNull(timeout);
        assertTrue(timeout.contains("超时") || timeout.contains("异常"));

        String other = DialService.describeFailure(new DialService.DialResult(42, "other"));
        assertNotNull(other);
        assertTrue(other.contains("42"));
    }
}
