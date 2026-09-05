package util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrafficSamplerTest {

    // Captured from real `netstat -e` on a Chinese Windows 11: label first, numbers after.
    private static final String ZH_OUTPUT = """
            接口统计

                           接收的            发送的

            字节                    4009839052      4020416852
            单播数据包            62099469        59893265
            非单播数据包         2304021           89759
            丢弃                          0          206693
            错误                            0               0
            未知协议                 0
            """;

    private static final String EN_OUTPUT = """
            Interface Statistics

                           Received            Sent

            Bytes                    4009839052      4020416852
              Unicast Packets        62099469        59893265
              Non-unicast Packets    2304021           89759
              Discards                          0          206693
              Errors                            0               0
              Unknown protocols                 0
            """;

    @Test
    void parsesChineseBytesRow() {
        assertArrayEquals(new long[]{4009839052L, 4020416852L}, TrafficSampler.parse(ZH_OUTPUT));
    }

    @Test
    void parsesEnglishBytesRow() {
        assertArrayEquals(new long[]{4009839052L, 4020416852L}, TrafficSampler.parse(EN_OUTPUT));
    }

    @Test
    void returnsNullWhenNoCounterRow() {
        assertNull(TrafficSampler.parse(""));
        assertNull(TrafficSampler.parse("接口统计\n\n                           接收的            发送的\n"));
    }

    @Test
    void singleCounterLineIsSkipped() {
        assertNull(TrafficSampler.parse("未知协议                 0\n"));
    }

    @Test
    void firstCounterRowWins() {
        String output = """
                接口统计
                           接收的            发送的
                单播数据包            62099469        59893265
                错误                            0               0
                """;
        assertArrayEquals(new long[]{62099469L, 59893265L}, TrafficSampler.parse(output));
    }

    @Test
    void overflowingNumberSkipsRow() {
        String output = """
                字节                    99999999999999999999999      4020416852
                字节                    123              456
                """;
        assertArrayEquals(new long[]{123L, 456L}, TrafficSampler.parse(output));
    }
}
