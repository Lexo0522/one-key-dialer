package storage;

import model.AccountInfo;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreCsvTest {

    @Test
    void detectHeaderLayouts() {
        assertEquals(AccountStore.CsvLayout.SAFE_3,
            AccountStore.detectCsvHeaderLayout("昵称,账号,备注"));
        assertEquals(AccountStore.CsvLayout.WITH_PASSWORD_4,
            AccountStore.detectCsvHeaderLayout("昵称,账号,密码,备注"));
        assertEquals(AccountStore.CsvLayout.WITH_PASSWORD_4,
            AccountStore.detectCsvHeaderLayout("name,username,password,remark"));
        assertEquals(AccountStore.CsvLayout.UNKNOWN,
            AccountStore.detectCsvHeaderLayout("alice,user1,hello"));
    }

    @Test
    void threeColWithoutHeaderIsRemarkNotPassword() {
        AccountInfo a = AccountStore.accountFromCsvParts(
            new String[]{"n", "u", "maybe-pass-or-remark"}, AccountStore.CsvLayout.UNKNOWN);
        assertEquals("", a.getPassword());
        assertEquals("maybe-pass-or-remark", a.remark);
    }

    @Test
    void fourColWithPasswordHeader() throws Exception {
        File tmp = Files.createTempFile("acc", ".csv").toFile();
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(),
            "昵称,账号,密码,备注\nn1,u1,secret,r1\n", StandardCharsets.UTF_8);
        List<AccountInfo> list = AccountStore.loadCsv(tmp);
        assertEquals(1, list.size());
        assertEquals("secret", list.get(0).getPassword());
        assertEquals("r1", list.get(0).remark);
    }

    @Test
    void threeColSafeHeader() throws Exception {
        File tmp = Files.createTempFile("acc2", ".csv").toFile();
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(),
            "昵称,账号,备注\nn1,u1,only-remark\n", StandardCharsets.UTF_8);
        List<AccountInfo> list = AccountStore.loadCsv(tmp);
        assertEquals(1, list.size());
        assertEquals("", list.get(0).getPassword());
        assertEquals("only-remark", list.get(0).remark);
    }
}
