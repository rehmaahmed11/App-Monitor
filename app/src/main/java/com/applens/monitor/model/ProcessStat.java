package com.applens.monitor.model;

import com.applens.monitor.core.Fmt;

/** Snapshot of one process belonging to the monitored application. */
public class ProcessStat {

    public int pid;
    public int uid = -1;
    public String name = "";
    public String state = "";
    public long rssKb;
    public long pssKb;
    public long vszKb;
    public int threads;
    public long utimeTicks;
    public long stimeTicks;
    public long readBytes;
    public long writeBytes;
    public long rchar;
    public long wchar;
    public int openFiles;
    public int sockets;
    public int nativeLibraries;
    public String wchan = "";
    public boolean foreground;
    public long startTimeTicks;
    public boolean service = true;

    public long cpuPercent() {
        long total = utimeTicks + stimeTicks;
        return total;
    }

    public String memText() {
        if (pssKb > 0) {
            return Fmt.bytes(pssKb * 1024);
        }
        if (rssKb > 0) {
            return Fmt.bytes(rssKb * 1024);
        }
        return "—";
    }

    public String stateLabel() {
        switch (state == null ? "" : state) {
            case "R":
                return "running";
            case "S":
                return "sleeping";
            case "D":
                return "uninterruptible";
            case "Z":
                return "zombie";
            case "T":
                return "stopped";
            case "I":
                return "idle";
            default:
                return state == null || state.isEmpty() ? "?" : state;
        }
    }
}
