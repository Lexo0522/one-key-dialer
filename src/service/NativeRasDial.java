package service;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import model.DialCredentials;
import model.PasswordChars;

import java.io.File;
import java.util.Arrays;

/**
 * Direct Win32 {@code RasDialW} binding (JNA). Keeps the password inside a
 * process-local struct instead of the {@code rasdial.exe} command line, where it
 * is readable in the local process list for as long as the child process runs.
 * <p>
 * The binding loads lazily; any load or call failure returns {@code null} so the
 * caller can fall back to the argv path. The returned {@link com.sun.jna.Pointer}
 * HRASCONN is intentionally never hung up: doing so would terminate the PPP
 * session, while a dangling handle after {@code rasdial /disconnect} is harmless.
 */
public final class NativeRasDial {
    private NativeRasDial() {
    }

    private interface RasApi32 extends Library {
        RasApi32 INSTANCE = Native.load("rasapi32", RasApi32.class);

        int RasDialW(WString reserved, WString phonebook, RASDIALPARAMSW params,
                     int notifierType, Pointer notifier, PointerByReference rasConn);
    }

    /**
     * Mirrors the SDK RASDIALPARAMSW layout (RAS_Max* + 1 for the terminator).
     * Must be public with public fields — JNA reads them reflectively, and strict
     * encapsulation (JDK 17+) denies access to package-private members.
     */
    @Structure.FieldOrder({"dwSize", "szEntryName", "szPhoneNumber", "szCallbackNumber",
        "szUserName", "szPassword", "szDomain"})
    public static final class RASDIALPARAMSW extends Structure {
        public int dwSize;
        public char[] szEntryName = new char[257];
        public char[] szPhoneNumber = new char[129];
        public char[] szCallbackNumber = new char[49];
        public char[] szUserName = new char[257];
        public char[] szPassword = new char[257];
        public char[] szDomain = new char[16];
    }

    /**
     * Dial synchronously. @return the RAS result code (0 = success), or {@code null}
     * when the native binding is unavailable / failed — the caller then falls back
     * to {@code rasdial.exe}. Never throws.
     */
    static Integer dial(String entryName, File phonebookFile, DialCredentials credentials) {
        char[] username = null;
        char[] password = null;
        RASDIALPARAMSW params = null;
        try {
            final RasApi32 api;
            try {
                api = RasApi32.INSTANCE;
            } catch (Throwable loadFailure) {
                return null;
            }
            username = credentials.username().toCharArray();
            password = credentials.copyPassword();
            params = buildParams(entryName, username, password);
            PointerByReference conn = new PointerByReference();
            WString phonebook = phonebookFile != null
                ? new WString(phonebookFile.getAbsolutePath()) : null;
            int code = api.RasDialW(null, phonebook, params, 0, null, conn);
            return code;
        } catch (Throwable unexpected) {
            return null;
        } finally {
            if (params != null) {
                Arrays.fill(params.szPassword, '\0');
                Arrays.fill(params.szUserName, '\0');
                try {
                    params.write(); // push the zeros into native memory
                } catch (Throwable ignored) {
                }
            }
            if (username != null) PasswordChars.clear(username);
            if (password != null) PasswordChars.clear(password);
        }
    }

    /** Visible for tests: asserts the struct layout fields without calling the API. */
    static RASDIALPARAMSW buildParams(String entryName, char[] username, char[] password) {
        RASDIALPARAMSW p = new RASDIALPARAMSW();
        if (p.dwSize != p.size()) {
            p.dwSize = p.size();
        }
        copyInto(p.szEntryName, entryName);
        copyInto(p.szUserName, username);
        copyInto(p.szPassword, password);
        return p;
    }

    private static void copyInto(char[] target, String source) {
        if (source == null) return;
        copyInto(target, source.toCharArray());
    }

    private static void copyInto(char[] target, char[] source) {
        if (source == null || source.length == 0) return;
        System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
    }
}
