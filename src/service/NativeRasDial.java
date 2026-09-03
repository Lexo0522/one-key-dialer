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
 * caller can surface a clear error. The returned HRASCONN is intentionally never
 * hung up: doing so would terminate the PPP session, while a dangling handle
 * after {@code rasdial /disconnect} is harmless.
 * <p>
 * Layout contract, pinned by {@code NativeRasDialTest} against native memory:
 * <ul>
 *   <li>Pre-24H2 builds take the documented x64 SDK layout (1968): szUserName@888,
 *       szPassword@1402, dwIfIndex and guidId trailing.</li>
 *   <li>Windows 11 24H2 (build 26100+) rejects every pre-24H2 size with 632 and
 *       accepts 2096 only — and its 2096 layout is <em>not</em> the SDK layout
 *       with a tail: rasapi32 inserts 146 opaque bytes between dwCallbackId and
 *       szUserName (szUserName@1034, szPassword@1548, szDomain@2062; dwIfIndex
 *       and guidId are gone). Writing the SDK offsets against dwSize=2096 makes
 *       the OS read a truncated username suffixed with password bytes and every
 *       dial fails with 691.</li>
 * </ul>
 * Both layouts live in one dial implementation; the OS build number only picks
 * the struct class. There is deliberately no probe ladder.
 */
public final class NativeRasDial {
    private NativeRasDial() {
    }

    /** SDK sizeof(RASDIALPARAMSW), x64, WINVER &ge; 0x600 (dwIfIndex + guidId). */
    static final int SDK_STRUCT_SIZE = 1968;
    /** First Windows 11 24H2 build; restructured RASDIALPARAMSW. */
    static final int BUILD_24H2 = 26100;
    /** dwSize required by 24H2+ (SDK fields + 146-byte inserted extension). */
    static final int STRUCT_SIZE_24H2 = 2096;
    /** Opaque bytes 24H2 inserted between dwCallbackId and szUserName; sent zeroed. */
    static final int EXT_BYTES_24H2 = 1034 - 888; // 24H2 szUserName minus SDK szUserName

    /** Common write/wipe surface over the two layout generations. */
    interface DialParams {
        Structure self();

        /** Sets dwSize to this struct's JNA-computed size; call before write/dial. */
        void initSize();

        void entry(String name);

        void credentials(char[] username, char[] password);

        void wipe();
    }

    private interface RasApi32 extends Library {
        RasApi32 INSTANCE = Native.load("rasapi32", RasApi32.class);

        int RasDialW(WString reserved, WString phonebook, Structure params,
                     int notifierType, Pointer notifier, PointerByReference rasConn);

        int RasGetErrorStringW(int resourceId, char[] buffer, int bufferSize);
    }

    private interface Ntdll extends Library {
        Ntdll INSTANCE = Native.load("ntdll", Ntdll.class);

        int RtlGetVersion(OSVERSIONINFOW info);
    }

    /**
     * SDK RASDIALPARAMSW (RAS_Max* + 1 terminators, WINVER &ge; 0x401/0x600
     * fields). Must be public with public fields — JNA reads them reflectively.
     */
    @Structure.FieldOrder({"dwSize", "szEntryName", "szPhoneNumber", "szCallbackNumber",
        "dwSubEntry", "dwCallbackId", "szUserName", "szPassword", "szDomain",
        "dwIfIndex", "guidId"})
    public static final class ParamsSdk extends Structure implements DialParams {
        public int dwSize;
        public char[] szEntryName = new char[257];
        public char[] szPhoneNumber = new char[129];
        public char[] szCallbackNumber = new char[49];
        public int dwSubEntry;
        public Pointer dwCallbackId; // ULONG_PTR
        public char[] szUserName = new char[257];
        public char[] szPassword = new char[257];
        public char[] szDomain = new char[16];
        public int dwIfIndex;
        public byte[] guidId = new byte[16];

        @Override public Structure self() { return this; }

        @Override public void initSize() { dwSize = size(); }

        @Override public void entry(String name) { copyInto(szEntryName, name); }

        @Override public void credentials(char[] username, char[] password) {
            copyInto(szUserName, username);
            copyInto(szPassword, password);
        }

        @Override public void wipe() {
            Arrays.fill(szUserName, '\0');
            Arrays.fill(szPassword, '\0');
            try {
                write();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * The Windows 11 24H2 RASDIALPARAMSW: the SDK header fields, then 146 opaque
     * bytes inserted before the credentials, and dwIfIndex/guidId removed.
     * Struct size 2096, szUserName@1034, szPassword@1548, szDomain@2062.
     */
    @Structure.FieldOrder({"dwSize", "szEntryName", "szPhoneNumber", "szCallbackNumber",
        "dwSubEntry", "dwCallbackId", "ext", "szUserName", "szPassword", "szDomain"})
    public static final class Params24H2 extends Structure implements DialParams {
        public int dwSize;
        public char[] szEntryName = new char[257];
        public char[] szPhoneNumber = new char[129];
        public char[] szCallbackNumber = new char[49];
        public int dwSubEntry;
        public Pointer dwCallbackId; // ULONG_PTR
        /** Opaque OS extension; never written as data. */
        public byte[] ext = new byte[EXT_BYTES_24H2];
        public char[] szUserName = new char[257];
        public char[] szPassword = new char[257];
        public char[] szDomain = new char[16];

        @Override public Structure self() { return this; }

        @Override public void initSize() { dwSize = size(); }

        @Override public void entry(String name) { copyInto(szEntryName, name); }

        @Override public void credentials(char[] username, char[] password) {
            copyInto(szUserName, username);
            copyInto(szPassword, password);
        }

        @Override public void wipe() {
            Arrays.fill(szUserName, '\0');
            Arrays.fill(szPassword, '\0');
            try {
                write();
            } catch (Throwable ignored) {
            }
        }
    }

    /** OSVERSIONINFOW for {@code ntdll!RtlGetVersion} (the only non-deprecated version probe). */
    @Structure.FieldOrder({"dwOSVersionInfoSize", "dwMajorVersion", "dwMinorVersion",
        "dwBuildNumber", "dwPlatformId", "szCSDVersion"})
    public static final class OSVERSIONINFOW extends Structure {
        public int dwOSVersionInfoSize;
        public int dwMajorVersion;
        public int dwMinorVersion;
        public int dwBuildNumber;
        public int dwPlatformId;
        public char[] szCSDVersion = new char[128];
    }

    /**
     * Dial synchronously. @return the RAS result code (0 = success), or {@code null}
     * when the native binding is unavailable — the caller reports a clear error.
     * Never throws.
     */
    static Integer dial(String entryName, File phonebookFile, DialCredentials credentials) {
        char[] username = null;
        char[] password = null;
        DialParams params = null;
        try {
            final RasApi32 api;
            try {
                api = RasApi32.INSTANCE;
            } catch (Throwable loadFailure) {
                return null;
            }
            username = credentials.username().toCharArray();
            password = credentials.copyPassword();
            params = newParams(entryName, username, password);
            PointerByReference conn = new PointerByReference();
            WString phonebook = phonebookFile != null
                ? new WString(phonebookFile.getAbsolutePath()) : null;
            return api.RasDialW(null, phonebook, params.self(), 0, null, conn);
        } catch (Throwable unexpected) {
            return null;
        } finally {
            if (params != null) params.wipe();
            if (username != null) PasswordChars.clear(username);
            if (password != null) PasswordChars.clear(password);
        }
    }

    /** OS-provided text for a RAS error code, or "" when the code has no RAS message. */
    static String errorText(int code) {
        if (code <= 0) return "";
        try {
            char[] buf = new char[512];
            if (RasApi32.INSTANCE.RasGetErrorStringW(code, buf, buf.length) == 0) {
                int end = 0;
                while (end < buf.length && buf[end] != '\0') end++;
                return new String(buf, 0, end).trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Visible for tests: which struct generation each Windows build accepts. */
    static boolean uses24H2Layout(int buildNumber) {
        return buildNumber >= BUILD_24H2;
    }

    /** Visible for tests: builds and fills the struct for the running OS. */
    static DialParams newParams(String entryName, char[] username, char[] password) {
        DialParams p = uses24H2Layout(osBuildNumber()) ? new Params24H2() : new ParamsSdk();
        p.initSize();
        p.entry(entryName);
        p.credentials(username, password);
        return p;
    }

    /** 0 when the version probe fails — the documented SDK layout is then used. */
    private static int osBuildNumber() {
        try {
            OSVERSIONINFOW info = new OSVERSIONINFOW();
            info.dwOSVersionInfoSize = info.size();
            if (Ntdll.INSTANCE.RtlGetVersion(info) == 0) {
                return info.dwBuildNumber;
            }
        } catch (Throwable ignored) {
        }
        return 0;
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
