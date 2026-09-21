package walsandbox.log;

import java.io.IOException;

/** Simulated power loss: the process stops with only the durable prefix on disk. */
public class CrashException extends IOException {

    public CrashException(String message) {
        super(message);
    }
}
