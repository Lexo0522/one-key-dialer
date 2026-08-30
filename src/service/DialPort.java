package service;

import model.DialCredentials;

/**
 * RAS boundary used by the orchestrator. Implemented by {@link WindowsRasModule};
 * tests substitute fakes. Blocking calls — invoke off the EDT.
 */
public interface DialPort {
    /** RAS entry / connection name this port manages. */
    String connectionName();

    /** Blocking dial with one-shot credentials. */
    DialResult connect(DialCredentials credentials) throws Exception;

    /** Blocking disconnect of the active connection (tracked inside the port). */
    int disconnect() throws Exception;

    /** rasdial outcome. */
    final class DialResult {
        public final int code;
        public final String output;

        public DialResult(int code, String output) {
            this.code = code;
            this.output = output != null ? output : "";
        }

        public boolean isSuccess() {
            return code == 0;
        }
    }
}
