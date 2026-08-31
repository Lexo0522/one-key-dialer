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
 * <p>
 * Windows 11 24H2 extended {@code RASDIALPARAMSW} by 144 bytes past the public
 * SDK layout and began rejecting the legacy sizes with error 632
 * (ERROR_INVALID_STRUCT_SIZE), so the struct carries a zeroed tail large enough
 * for the newest variant and the dial walks a size ladder until one is accepted.
 */
public final class NativeRasDial {
    private NativeRasDial() {
    }

    /** Extra bytes Windows 11 24H2 appended to RASDIALPARAMSW (rejected if missing). */
    static final int TAIL_BYTES_24H2 = 144;
    /** RAS error 632 — the passed dwSize was not accepted. */
    static final int ERROR_INVALID_STRUCT_SIZE = 632;

    /** dwSize accepted by the last successful dial, so later dials skip the ladder. */
    private static volatile int acceptedSize;

    private interface RasApi32 extends Library {
        RasApi32 INSTANCE = Native.load("rasapi32", RasApi32.class);

        int RasDialW(WString reserved, WString phonebook, RASDIALPARAMSW params,
                     int notifierType, Pointer notifier, PointerByReference rasConn);
    }

    /**
     * Mirrors the SDK RASDIALPARAMSW layout (RAS_Max* + 1 for the terminator)
     * plus the fields guarded by WINVER >= 0x401 and the 24H2 tail. Must be
     * public with public fields — JNA reads them reflectively, and strict
     * encapsulation (JDK 17+) denies access to package-private members.
     */
    @Structure.FieldOrder({"dwSize", "szEntryName", "szPhoneNumber", "szCallbackNumber",
        "szUserName", "szPassword", "szDomain", "dwSubEntry", "dwCallbackId", "osTail"})
    public static final class RASDIALPARAMSW extends Structure {
        public int dwSize;
        public char[] szEntryName = new char[257];
        public char[] szPhoneNumber = new char[129];
        public char[] szCallbackNumber = new char[49];
        public char[] szUserName = new char[257];
        public char[] szPassword = new char[257];
        public char[] szDomain = new char[16];
        public int dwSubEntry;
        public Pointer dwCallbackId; // ULONG_PTR
        /** Zeroed reserve covering the undocumented 24H2 extension; never sent as data. */
        public byte[] osTail = new byte[TAIL_BYTES_24H2];
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
            int code = dialWithLadder(api, params, phonebook, conn);
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

    /**
     * Calls RasDialW with the dwSize the OS last accepted, or walks the size
     * ladder until one passes validation. Every rejected size returns 632 without
     * touching the network, so retrying is safe; the first non-632 result wins.
     */
    private static int dialWithLadder(RasApi32 api, RASDIALPARAMSW params,
                                      WString phonebook, PointerByReference conn) {
        int preferred = acceptedSize;
        if (preferred > 0 && preferred <= params.size()) {
            params.dwSize = preferred;
            int code = api.RasDialW(null, phonebook, params, 0, null, conn);
            if (code != ERROR_INVALID_STRUCT_SIZE) return code;
            acceptedSize = 0; // OS update changed the layout — re-probe below
        }
        for (int size : candidateSizes(params.size(), Native.POINTER_SIZE)) {
            params.dwSize = size;
            int code = api.RasDialW(null, phonebook, params, 0, null, conn);
            if (code != ERROR_INVALID_STRUCT_SIZE) {
                acceptedSize = size;
                return code;
            }
        }
        return ERROR_INVALID_STRUCT_SIZE;
    }

    /** Visible for tests: dwSize candidates from newest OS layout to oldest. */
    static int[] candidateSizes(int structSize, int pointerSize) {
        int classic = structSize - TAIL_BYTES_24H2; // SDK layout incl. WINVER 0x401 fields
        int legacyGap = pointerSize >= 8 ? 16 : 8;  // dwSubEntry + padding + dwCallbackId
        return new int[] {structSize, classic, classic - legacyGap};
    }

    /** Visible for tests: asserts the struct layout fields without calling the API. */
    static RASDIALPARAMSW buildParams(String entryName, char[] username, char[] password) {
        RASDIALPARAMSW p = new RASDIALPARAMSW();
        p.dwSize = p.size();
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
