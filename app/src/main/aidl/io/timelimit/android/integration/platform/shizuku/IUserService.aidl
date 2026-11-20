package io.timelimit.android.integration.platform.shizuku;

/**
 * Runs inside the Shizuku server process (uid 2000 / shell when Shizuku was started over adb,
 * uid 0 when it runs on root), so it can start real shell processes without the app needing any
 * privilege of its own.
 *
 * This is the documented replacement for reflecting into Shizuku's private `newProcess`, which the
 * Shizuku developers have announced they will remove.
 */
interface IUserService {

    /**
     * Reserved by Shizuku: called when the service is replaced or unbound with remove=true.
     * The service process does NOT die on its own, so this has to System.exit().
     */
    void destroy() = 16777114;

    /**
     * Runs `sh -c <command>` with the service's identity.
     * Returns trimmed stdout when the exit code is 0, null otherwise (including on timeout).
     */
    String runCommand(String command) = 1;
}
