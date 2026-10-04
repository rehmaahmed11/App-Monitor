# Keep the reflective/system-API entry points used by the root shell.
-keepclassmembers class com.applens.monitor.** {
    public *;
}
-dontwarn com.applens.monitor.**
