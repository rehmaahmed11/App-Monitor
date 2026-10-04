package com.applens.monitor.model;

import com.applens.monitor.core.Fmt;

/** One storage location attributed to an application. */
public class StorageItem {

    public String name = "";
    public String path = "";
    public long bytes = -1;
    public int colorIndex;
    public String note = "";

    public StorageItem() {
    }

    public StorageItem(String name, String path, long bytes) {
        this.name = name;
        this.path = path;
        this.bytes = bytes;
    }

    public String sizeText() {
        return bytes < 0 ? "…" : Fmt.bytes(bytes);
    }
}
