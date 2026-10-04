# AppLens

**Native, on-device application monitor for rooted Android phones.**

AppLens scans every installed application, lets you pick one, and then records
everything Android is willing to expose about that application's behaviour:
permissions and AppOps, a live activity feed, network connections, DNS lookups,
storage usage, device/location/identifier fingerprints and a full timeline.
Every record is written to a plain-text file on shared storage so it can be read,
grepped or shared with any other tool.

Built with **zero third-party dependencies** — the Android framework only.

```
┌─────────────┐   select app    ┌──────────────────────────────────────┐
│  AppLens    │ ──────────────► │ Facebook  com.facebook.katana         │
│  SCANNER    │                 │ [ START MONITORING ]                 │
│             │                 │ OVERVIEW PERMISSIONS ACTIVITY NETWORK │
│  Facebook   │                 │ DNS DATA ACCESS TIMELINE             │
│  Instagram  │                 └──────────────────────────────────────┘
│  TikTok …   │                                  │
└─────────────┘                                  ▼
                                   /sdcard/AppLens/<pkg>/activity_*.txt
```

---

## 1. Install

Download the APK from the [Actions artifacts](https://github.com/rehmaahmed11/App-Monitor/actions)
(or from a tagged release), then:

```bash
adb install -r AppLens-release-*.apk
```

Requirements:

| | |
|---|---|
| Android | 8.0 Oreo (API 26) → 14 (API 34) |
| Root | **Required.** Magisk, KernelSU or APatch, with AppLens granted the superuser prompt once |
| Optional | `tcpdump` installed via Magisk → per-connection byte counters |
| Permissions | `QUERY_ALL_PACKAGES` (automatic), optional all-files access for the text records |

The superuser prompt appears once. Magisk/KernelSU remember the decision, so from
then on AppLens starts with root already granted — the header chip on the dashboard
shows `ROOT ✓` and every deeper reading is available without further interaction.

---

## 2. Using AppLens

### 2.1 Dashboard

Opens straight onto the **previously scanned** application list.

* **SCAN APPS** re-scans `/data/system/packages.xml` through the package manager,
  then resolves per-app size (`du`/`stat` over the APK and the private data dir),
  installer package, running state and total footprint.
* **Search** matches app name and package.
* **Sort** by name, package, installation date, update date or size.
* **Filter chips**: all · user · system · running · monitored.
* **PREVIOUSLY SCANNED** chips jump back to the apps you already looked at.
* The header chip shows root state — tap it to re-request root.
* **DIAG** opens saved crash/error reports. If Android reports that AppLens crashed
  or stopped unexpectedly, the report opens automatically the next time AppLens starts.
* **TXT** continues to browse and share the per-app activity record files.

### 2.2 Application detail

```
Facebook
com.facebook.katana          v420.0.0.100 · uid 10234 · target SDK 34
[ START MONITORING ]  [GO]

   ↑ UPLOAD      ↓ DOWNLOAD      DNS      EVENTS
  24 KB          184 KB          17       143
```

Eight tabs, each backed by a different data source:

| Tab | What it shows | Source |
|---|---|---|
| **OVERVIEW** | identity, uid, version, installer, ABI, SE info, flags, install/update dates, components, live process table (RSS, threads, fd count, native libs, per-process I/O), actions (open, app info, force stop) | `dumpsys package`, `PackageManager`, `/proc/<pid>` |
| **PERMISSIONS** | every permission grouped as LOCATION / CAMERA / MICROPHONE / CONTACTS / PHONE / STORAGE / SMS / … with ✓/✕, protection level, flags and the matching **AppOps** mode; `GRANT ALL` and `RESET` via root | `dumpsys package`, `appops get`, `pm grant` |
| **ACTIVITY** | real-time feed of privacy-relevant events with timestamp, type, app, details and the exact source API; filter chips per category and a search box | root `logcat` matched by pid + package, `/proc` sampling, `inotifyd` |
| **NETWORK** | live throughput sparklines, totals, connection table with destination host/IP, port, protocol, state, per-connection ↑/↓ bytes, duration and connection count | `/proc/net/{tcp,tcp6,udp,udp6}` + inode matching, `iptables`/`xt_qtaguid` counters, optional `tcpdump` |
| **DNS** | every domain the app resolved: time, domain, query type, returned addresses, resolver, transport and how many times it was queried; searchable | capture `VpnService` (TUN), `tcpdump` fallback |
| **DATA** | storage donut, per-directory breakdown (databases, files, cache, no_backup, lib, …), file/database counts, largest file, native libraries, external storage | root `du`, `find` |
| **ACCESS** | device fingerprint: build identity, root manager, SELinux, kernel, IP/MAC/DNS/route/Wi-Fi, Android ID, serial, IMEI, last known location, sensors, battery, user profiles | `Build`, framework APIs, root `getprop`/`ip`/`dumpsys location` |
| **TIMELINE** | every observation merged into one chronological record | all of the above |

### 2.3 Activity records on the SD card

Every event is appended to a plain-text file:

```
/sdcard/AppLens/com.facebook.katana/activity_2026-10-04_11-59-08.txt
```

```
=====================================================================
 AppLens activity record
=====================================================================
Session start   : 04 Oct 2026, 11:59:08
Application     : Facebook
Package         : com.facebook.katana
Device          : Google Pixel 7
Android         : 14 (API 34)
Build           : google/panther.ap3a.240705.005/...
Root            : granted via su
---------------------------------------------------------------------
10:32:14.221  [LOCATION] Location request — 37.7749, -122.4194  <source: logcat:GnssLocationProvider>  <app: com.facebook.katana>
10:32:17.884  [NETWORK] Connection opened — TCP 443 (established)  <source: /proc/net>  <app: com.facebook.katana>
10:32:18.010  [DNS] graph.facebook.com  type=A  transport=UDP  answers=31.13.24.12  queried=1x  server=8.8.8.8  <app: com.facebook.katana>  <source: VPN>
10:32:19.443  [CAMERA] Camera service access — CameraService: ...  <source: logcat:CameraService>  <app: com.facebook.katana>
10:32:24.901  [MICROPHONE] Audio HAL client — AudioFlinger: ...  <source: logcat:AudioFlinger>  <app: com.facebook.katana>
```

Open the **TXT** button on the dashboard to browse, open or share the records.
Files are written through root into the shared volume; without root AppLens falls
back to its own external directory (and offers all-files access on Android 11+).

### 2.4 Crash and error diagnostics

Tap **DIAG** on the dashboard to open the diagnostic window; new reports appear
there while it is open. Four independent producers fill it, so a report exists
even when no Java exception was ever thrown:

| Producer | Catches |
|---|---|
| Uncaught-exception handler | fatal Java crashes, written synchronously *before* Android's own handler runs |
| OS process history (Android 11+) | ANRs, native crashes, low-memory kills, "the app just vanished" |
| **Session breadcrumb** | a monitoring session that started and never finished — written before the samplers start, cleared on a clean stop, turned into a report on the next launch. This is what covers an ANR kill on Android 10 and anything the OS did not classify |
| `recordProblem` / `recordThrottledProblem` | handled failures: a sampler that would not start, a refused VPN, a root command that died, a DNS upstream that could not be reached. Repeats of the same problem are rate-limited to one per five minutes |

Every entry carries the app/Android/device identity plus the live root state (su
path, manager, whether the shared session is alive, how many `su` processes were
spawned) and which package was being monitored.

After a crash/problem, AppLens opens the diagnostic window on the next launch. Use
**COPY ALL** to put the saved text on the clipboard and paste it into a support
message; **CLEAR** deletes it. Reports stay in AppLens's private app storage, are
bounded to 512 KiB and are never uploaded automatically. They include device/app
version and stack details, so review the text before sharing. Clearing reports does
not affect the separate activity records under `/sdcard/AppLens`.

---

## 3. How the monitoring works

All of it runs through **one** `su` session (see *The root session* below).

| Layer | Mechanism | Cadence |
|---|---|---|
| Activity | root `logcat -v threadtime -T 1` on main/system/events, lines matched by pid (tracked via `ps`) **and** by package name, classified against ~40 privacy-relevant signatures | streaming |
| Processes | one root round-trip per poll reading `/proc/<pid>/{stat,status,io,wchan}` plus fd/socket/native-library counts | 1.5 s |
| File writes | root `inotifyd` on the private data dir and external app dirs | streaming |
| Connections | `/proc/net/*` socket table, sockets attributed by inode (`/proc/<pid>/fd`) and uid | 1 s |
| Bytes | `xt_qtaguid` when present, otherwise root `iptables` per-uid chains (v4 + v6), otherwise `/proc/<pid>/io`; the active source is always displayed | 4 s |
| Per-flow bytes | root `tcpdump` stream matched against the attributed sockets | streaming |
| DNS | capture `VpnService` (below), `tcpdump` fallback | per packet |
| Memory | `dumpsys meminfo` total PSS | 12 s |
| Device profile | on-demand, cached per ACCESS visit | on demand |

### The root session

Magisk, KernelSU and APatch raise a **"AppLens was granted superuser rights"**
toast for every single `su` invocation. AppLens therefore negotiates root exactly
once and keeps that shell open:

* `RootShell` owns one interactive `su` process. Commands are written to its
  stdin and terminated by a unique marker that also carries `$?`, so stdout and
  the exit status are read back without starting anything new;
* a watchdog kills a shell whose command outlives its timeout, and the next call
  transparently opens a fresh one — a hung `su` can never wedge the UI;
* the only other processes that need their own `su` are the long-lived streams
  (`logcat`, `inotifyd`, `tcpdump`), and they are started **only when the tool
  really exists** on the device;
* devices whose `su` cannot host an interactive shell fall back to classic
  one-shot `su -c` calls automatically;
* `RootShell.suInvocations()` counts every `su` process started since launch and
  is printed in the diagnostics, so the toast behaviour is verifiable rather than
  assumed. A full monitoring session is one session plus at most three streams.

### Starting a session

**START MONITORING** brings the samplers up first and *then* launches the target
application, so its start-up is part of the record:

1. `MonitorService` posts its foreground notification immediately — on every
   `onStartCommand` path, before anything can fail. Android 14 kills a process
   that does not, and the declared `specialUse` service type must match the
   notification;
2. the session is bootstrapped on a worker thread inside a `try/catch`: a sampler
   that cannot start is a diagnostics entry and a missing data source, never a
   crash and never a dead session;
3. a breadcrumb is written before the samplers start and cleared on a clean stop,
   so a session killed without a Java exception (ANR, low memory, force stop)
   still produces a report on the next launch;
4. the target is launched through its launcher intent; if the activity manager
   refuses a start from a service, or the app has no launcher activity, root
   `monkey`/`am start` is used instead.

### The DNS capture VPN

`DnsVpnService` is a real `VpnService`, but it is scoped so nothing else on the
device is affected:

* it assigns `10.111.222.1/30` and advertises `10.111.222.2` as the DNS server;
* **only the monitored application is routed into the TUN**
  (`Builder.addAllowedApplication`). Every other app — and AppLens itself — keeps
  resolving over the normal network path, so a capture problem can never take the
  phone offline;
* it installs **/32 routes for the device's real resolvers only**, so all other
  traffic keeps flowing through Wi-Fi/cellular untouched;
* the real resolvers come from `ConnectivityManager.getLinkProperties()`, with the
  root `getprop`/`ip` probes only as a fallback;
* queries arriving on the TUN are forwarded to a **real** resolver over a
  `protect()`ed socket — never back to the synthetic `10.111.222.2`, which exists
  only inside the TUN — and the answer is synthesised back (IP/UDP checksums
  recomputed, TC bit set when a reply would exceed the MTU so the client falls
  back to TCP). If the upstream cannot be reached the client gets a SERVFAIL
  immediately instead of waiting out its resolver timeout;
* DNS over **TCP :53** and **DNS-over-TLS :853** are DNAT-ed to a loopback relay
  that pumps the stream and decodes the DNS framing for the log — the transport is
  encrypted and stays encrypted. The `iptables` rules are installed **only after
  the relay is listening**, are restricted to the monitored uid (`--uid-owner`),
  and are tagged `applens-dns` / `applens-dot` so that
  `DnsVpnService.purgeStaleRules()` can remove anything an interrupted session left
  behind — it runs on every stop and once at application start;
* the client uid is resolved exactly, by looking the packet's source port up in
  `/proc/net`, so a query is attributed to the right application;
* the service posts its foreground notification on **every** `onStartCommand` path
  (declared `specialUse` for Android 14), and the whole bring-up — resolver
  discovery, `establish()`, firewall rules — happens off the main thread.

If the VPN is declined, monitoring continues and the DNS tab falls back to
`tcpdump` (when installed) and to the DNS servers observed in `/proc/net`.

### Keeping the UI alive

A monitored application can emit thousands of log lines per second, and every one
of them used to become a main-thread message and a dashboard repaint. Three
back-pressure stages keep the app responsive while the session runs:

| Stage | Limit | Effect |
|---|---|---|
| `MonitorEngine.publish` | 60 events/s into the in-memory feed | the text record on `/sdcard` still gets **everything**; a "feed throttled" notice makes any gap visible |
| `MonitorHub` | one observer wake-up per 200 ms (events) / 400 ms (state) | observers always receive the newest value, never a backlog |
| `DetailActivity` | one full tab rebuild per 500 ms | tab switches still repaint immediately |

`logcat` is also started with `-T 1`, so a session begins at the tail of the buffer
instead of replaying the tens of thousands of lines already in it.

---

## 4. Building from source

```bash
export ANDROID_HOME=/path/to/android-sdk     # needs platform 34 + build-tools
./gradlew assembleDebug                     # app/build/outputs/apk/debug/
./gradlew assembleRelease                   # app/build/outputs/apk/release/
```

Release signing is supplied through Gradle properties so CI can sign without
committing a key:

```bash
./gradlew assembleRelease \
  -PapplensStoreFile=keystore/release.jks \
  -PapplensStorePassword=… -PapplensKeyAlias=… -PapplensKeyPassword=…
```

### GitHub Actions

`.github/workflows/build-apk.yml` runs on every push and pull request:

1. JDK 17 + Gradle 8.7 + Android SDK 34,
2. an ephemeral release keystore is generated (or taken from the
   `APPLENS_KEYSTORE_BASE64` / `APPLENS_KEYSTORE_PASSWORD` secrets),
3. `packageApkDebug` + `packageApkRelease` are built,
4. the APKs plus `SHA256SUMS.txt` are uploaded as the `AppLens-APK` artifact,
5. pushing a `v*` tag publishes a GitHub Release with the APK attached,
6. every compiler error is re-emitted as a check annotation so failures are
   visible without opening the raw log.

Local convenience: `node tools/jsyntax.mjs` parses every Java file for syntax
errors, and `./tools/ci-status.sh` waits for the current commit's run and prints
the error annotations.

---

## 5. Project layout

```
app/src/main/java/com/applens/monitor/
├── AppLensApp.java              application entry, boots the root negotiation
├── core/       RootShell        su session, command execution, streams, file I/O
│               Fmt              bytes / duration / clock formatting
│               Bus              tiny main-thread observable (no AndroidX)
│               Prefs            typed SharedPreferences
├── model/      AppItem AppFacts PermissionItem EventItem ConnectionItem
│               DnsRecord ProcessStat StorageItem DeviceProfile EventCategory
├── repo/       AppRepository    scanning, sorting, sizes, scan history
│               PackageFactsParser  dumpsys package + appops parsing
│               ProcessRepository   ps + /proc harvesting
│               StorageRepository  du breakdown
│               DeviceRepository   device / network / location / identifier profile
├── monitor/    MonitorEngine    the orchestrator
│               MonitorService   foreground service
│               MonitorHub       shared live state between service and UI
│               LogcatMonitor ProcessMonitor-equivalent samplers SocketMonitor
│               ByteCounter FileWatchMonitor TcpdumpMonitor TcpdumpFlowMonitor
│               DnsHostCache
├── net/        DnsVpnService    capture VPN + TCP/DoT loopback relay
│               DnsMessage       DNS wire format parser/builder
├── log/        ActivityLogWriter   plain-text records on shared storage
│               DiagnosticLog      bounded local crash/error reports + crash hook
├── ui/         MainActivity DetailActivity LogsActivity DiagnosticsActivity
│               tabs/            Overview Permissions Activity Network Dns Data
│                              Access Timeline
│               widget/          SparklineView RingView
└── util/       ShareProvider    dependency-free content provider for sharing
```

---

## 6. Design notes

* **No third-party libraries.** The whole app is framework Java: `Activity`,
  `ListView`, `ScrollView`, `PopupMenu`, `VpnService`, custom `View`s. That keeps
  the release APK around 300 KB, the build reproducible, and removes every
  dependency-resolution failure mode. `ShareProvider` replaces AndroidX's
  `FileProvider` in ~80 lines.
* **Honest attribution.** Every counter tells you where the number came from:
  `xt_qtaguid`, `iptables counters`, `process I/O (partial)`, `tcpdump flow
  accounting`, `VpnService TUN` or `/proc + tcpdump fallback`.
* **Everything is local.** AppLens contains no analytics, no network calls of its
  own and no cloud component. The only traffic it generates is forwarding DNS
  queries for the device you are monitoring, to the resolver the device already
  uses.
* **Defensive by default.** Every root call is timeout-bounded, every observer is
  exception-isolated, and a hung `su` can never take the UI down. Nothing that
  touches root, the firewall or the filesystem runs on the main thread.
* **Degrade, never crash.** A missing binary, a denied permission or a refused
  VPN removes a data source and writes a diagnostics entry; it never ends the
  session and never takes the application down.

---

## 7. Legal

AppLens only reports what the operating system exposes to a privileged process on
a device you own and have rooted. Use it on your own devices, and respect local
law and the privacy of others when analysing an application.
