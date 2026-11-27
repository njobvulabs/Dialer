package com.example.j2me;

public class J2meApp {
    public final String id;
    public final String name;
    public final String vendor;
    public final String version;
    public final String path;
    public final String iconPath;

    public J2meApp(String id, String name, String vendor, String version, String path, String iconPath) {
        this.id = id;
        this.name = name;
        this.vendor = vendor;
        this.version = version;
        this.path = path;
        this.iconPath = iconPath;
    }
}
