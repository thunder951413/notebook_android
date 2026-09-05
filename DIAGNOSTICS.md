# Android diagnostics

Notebook writes privacy-safe JSONL diagnostics to the app-private `files/diagnostics` directory. The release package is `io.github.notebook.android`; the debug package is `io.github.notebook.android.nexttest`.

The active file is capped at 512 KiB and rotates through three archives, for a maximum of about 2 MiB. Individual entries are capped at 8 KiB. A single background worker drains a bounded 256-entry queue, so editor input never waits for disk I/O. Repeated identical events within 30 seconds are collapsed and the next record reports the suppressed count.

Every record includes a UTC timestamp, process session ID, app version, level and fixed event name. Operations may add a validated UUID operation ID, duration, bounded counts, a SHA-256-derived resource pseudoreference, exception class, and up to six class/method/line stack frames. The logger API has no arbitrary message field and never records note titles or bodies, passwords, tokens, raw URLs, server responses, or exception messages.

## Retrieve a debug build with ADB

First confirm the installed package, then stream a tar archive to the development computer:

```bash
adb shell pm list packages | grep io.github.notebook.android
adb exec-out run-as io.github.notebook.android.nexttest tar -cf - -C files diagnostics > notebook-diagnostics.tar
tar -tf notebook-diagnostics.tar
```

For a debuggable build without the `.nexttest` suffix, replace the package name with `io.github.notebook.android`. `run-as` is normally unavailable for release builds.

## Retrieve a release build

Open **设置与同步 → 诊断日志 → 导出并分享诊断日志**. Android opens the system share sheet for a small ZIP snapshot using the existing private `FileProvider`. The export is initiated by the user; there is no exported receiver or unauthenticated diagnostic endpoint.
