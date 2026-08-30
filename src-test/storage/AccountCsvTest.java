package storage;

import model.AccountInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CSV import/export for the account manager dialog (interactive feature, not storage). */
class AccountCsvTest {
    @TempDir
    Path dir;

    @Test
    void detectHeaderLayouts() {
        assertEquals(AccountCsv.CsvLayout.WITH_PASSWORD_4,
            AccountCsv.detectCsvHeaderLayout("昵称,账号,密码,备注"));
        assertEquals(AccountCsv.CsvLayout.SAFE_3,
            AccountCsv.detectCsvHeaderLayout("昵称,账号,备注"));
        assertEquals(AccountCsv.CsvLayout.SAFE_3,
            AccountCsv.detectCsvHeaderLayout("name,username,remark"));
        assertEquals(AccountCsv.CsvLayout.UNKNOWN,
            AccountCsv.detectCsvHeaderLayout("a,b,c"));
        assertEquals(AccountCsv.CsvLayout.UNKNOWN,
            AccountCsv.detectCsvHeaderLayout(null));
    }

    @Test
    void safeLayoutNeverTreatsThirdColumnAsPassword() {
        AccountInfo a = AccountCsv.accountFromCsvParts(
            new String[]{"nick", "user", "remark"}, AccountCsv.CsvLayout.UNKNOWN);
        assertTrue(a.isPasswordEmpty());
        assertEquals("remark", a.remark);
    }

    @Test
    void passwordLayoutParsesFourthColumnAsRemark() {
        AccountInfo a = AccountCsv.accountFromCsvParts(
            new String[]{"nick", "user", "pw", "remark"}, AccountCsv.CsvLayout.WITH_PASSWORD_4);
        assertTrue(a.passwordEquals("pw".toCharArray()));
        assertEquals("remark", a.remark);
    }

    @Test
    void loadParsesBodyAndHeader() throws Exception {
        File csv = dir.resolve("a.csv").toFile();
        Files.write(csv.toPath(), (
            "昵称,账号,密码,备注\n"
                + "dorm,2023001,pw1,campus\n"
                + "safe,2023002,,note\n").getBytes(StandardCharsets.UTF_8));
        List<AccountInfo> loaded = AccountCsv.load(csv);
        assertEquals(2, loaded.size());
        assertTrue(loaded.get(0).passwordEquals("pw1".toCharArray()));
        assertEquals("campus", loaded.get(0).remark);
        assertTrue(loaded.get(1).isPasswordEmpty());
    }

    @Test
    void loadWithoutHeaderTreatsFourColumnsAsPassword() throws Exception {
        File csv = dir.resolve("b.csv").toFile();
        Files.write(csv.toPath(), "nick,user,pw,remark\n".getBytes(StandardCharsets.UTF_8));
        List<AccountInfo> loaded = AccountCsv.load(csv);
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).passwordEquals("pw".toCharArray()));
    }

    @Test
    void saveSafeExportOmitsPasswords() throws Exception {
        File csv = dir.resolve("c.csv").toFile();
        List<AccountInfo> accounts = List.of(new AccountInfo("n", "u", "secret", "r"));
        AccountCsv.save(csv, accounts, false);
        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        assertFalse(content.contains("secret"));
        assertTrue(content.contains("昵称,账号,备注"));
    }

    @Test
    void saveWithPasswordExportIncludesPasswords() throws Exception {
        File csv = dir.resolve("d.csv").toFile();
        List<AccountInfo> accounts = List.of(new AccountInfo("n", "u", "secret", "r"));
        AccountCsv.save(csv, accounts, true);
        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("secret"));
        assertTrue(content.contains("昵称,账号,密码,备注"));
    }

    @Test
    void roundTripEscapesCommas() throws Exception {
        File csv = dir.resolve("e.csv").toFile();
        List<AccountInfo> accounts = List.of(new AccountInfo("a,b", "u", "p", "c,\"d\""));
        AccountCsv.save(csv, accounts, true);
        List<AccountInfo> loaded = AccountCsv.load(csv);
        assertEquals("a,b", loaded.get(0).name);
        assertEquals("c,\"d\"", loaded.get(0).remark);
        assertTrue(loaded.get(0).passwordEquals("p".toCharArray()));
    }
}
