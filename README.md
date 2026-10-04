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

---

## 3. How the monitoring works

| Layer | Mechanism | Cadence |
|---|---|---|
| Activity | root `logcat -v threadtime` on main/system/events, lines matched by pid (tracked via `ps`) **and** by package name, classified against ~40 privacy-relevant signatures | streaming |
| Processes | one root round-trip per poll reading `/proc/<pid>/{stat,status,io,wchan}` plus fd/socket/native-library counts | 1.5 s |
| File writes | root `inotifyd` on the private data dir and external app dirs | streaming |
| Connections | `/proc/net/*` socket table, sockets attributed by inode (`/proc/<pid>/fd`) and uid | 1 s |
| Bytes | `xt_qtaguid` when present, otherwise root `iptables` per-uid chains (v4 + v6), otherwise `/proc/<pid>/io`; the active source is always displayed | 4 s |
| Per-flow bytes | root `tcpdump` stream matched against the attributed sockets | streaming |
| DNS | capture `VpnService` (below), `tcpdump` fallback | per packet |
| Memory | `dumpsys meminfo` total PSS | 12 s |
| Device profile | on-demand, cached per ACCESS visit | on demand |

### The DNS capture VPN

`DnsVpnService` is a real `VpnService`, but it is scoped so nothing else on the
device is affected:

* it assigns `10.111.222.1/30` and advertises `10.111.222.2` as the DNS server;
* it installs **/32 routes for the device's real resolvers only**, so all other
  traffic keeps flowing through Wi-Fi/cellular untouched;
* queries arriving on the TUN are forwarded upstream over a `protect()`ed socket
  and the answer is synthesised back into the TUN (IP/UDP checksums recomputed,
  TC bit set when a reply would exceed the MTU so the client falls back to TCP);
* DNS over **TCP :53** and **DNS-over-TLS :853** are DNAT-ed to a loopback relay
  (installed with root `iptables`) that pumps the stream and decodes the DNS
  framing for the log — the transport is encrypted and stays encrypted;
* the client uid is resolved exactly, by looking the packet's source port up in
  `/proc/net`, so a query is attributed to the right application.

If the VPN is declined, monitoring continues and the DNS tab falls back to
`tcpdump` (when installed) and to the DNS servers observed in `/proc/net`.

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
├── ui/         MainActivity DetailActivity LogsActivity
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
  exception-isolated, and a hung `su` can never take the UI down.

---

## 7. Legal

AppLens only reports what the operating system exposes to a privileged process on
a device you own and have rooted. Use it on your own devices, and respect local
law and the privacy of others when analysing an application.
