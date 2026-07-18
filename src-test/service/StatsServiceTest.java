package service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StatsServiceTest {

    @Test
    void summarizeCountsDialSuccessAndFail() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"t", "拨号", "a", "成功", "--", "--"});
        rows.add(new String[]{"t", "自动拨号", "a", "失败:691", "--", "--"});
        rows.add(new String[]{"t", "拨号", "a", "RAS成功无外网", "--", "--"});
        rows.add(new String[]{"t", "断开", "a", "成功", "--", "--"});

        StatsService.Summary s = StatsService.summarize(rows);
        assertEquals(4, s.totalOps);
        assertEquals(3, s.dialAttempts);
        assertEquals(1, s.dialSuccess);
        assertEquals(2, s.dialFail);
        assertEquals(1, s.disconnects);
        assertTrue(s.reportText.contains("拨号次数"));
    }

    @Test
    void isSuccessResultRejectsNoInternet() {
        assertTrue(StatsService.isSuccessResult("成功"));
        assertFalse(StatsService.isSuccessResult("失败:691"));
        assertFalse(StatsService.isSuccessResult("RAS成功无外网"));
    }
}
