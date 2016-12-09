package com.android.internal.util.arsenic;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// convenience import for quick referencing of this method

public final class CMDProcessor {
    private static final String TAG = "CMDProcessor";

    private CMDProcessor() {
        // Cannot instantiate this class
        throw new AssertionError();
    }

    /* Run a system command with full redirection */
    public static ChildProcess startSysCmd(String[] cmdarray, String childStdin) {
        return new ChildProcess(cmdarray, childStdin);
    }

    public static CommandResult runSysCmd(String[] cmdarray, String childStdin) {
        ChildProcess proc = startSysCmd(cmdarray, childStdin);
        proc.waitFinished();
        return proc.getResult();
    }

    public static ChildProcess startShellCommand(String cmd) {
        String[] cmdarray = new String[3];
        cmdarray[0] = "sh";
        cmdarray[1] = "-c";
        cmdarray[2] = cmd;
        return startSysCmd(cmdarray, null);
    }

    public static CommandResult runShellCommand(String cmd) {
        ChildProcess proc = startShellCommand(cmd);
        proc.waitFinished();
        return proc.getResult();
    }

    public static ChildProcess startSuCommand(String cmd) {
        String[] cmdarray = new String[3];
        cmdarray[0] = "su";
        cmdarray[1] = "-c";
        cmdarray[2] = cmd;
        return startSysCmd(cmdarray, null);
    }

    public static CommandResult runSuCommand(String cmd) {
        ChildProcess proc = startSuCommand(cmd);
        proc.waitFinished();
        return proc.getResult();
    }

    public static boolean canSU() {
        CommandResult r = runShellCommand("id");
        StringBuilder out = new StringBuilder(0);
        out.append(r.getStdout());
        out.append(" ; ");
        out.append(r.getStderr());
        Log.d(TAG, "canSU() su[" + r.getExitValue() + "]: " + out);
        return r.success();
    }
}
