# Full Nix runtime (experimental)

This directory is a compatibility/hardening prototype, not an enabled TermuxPP
runtime. It does not change APK packaging, startup, `$PREFIX`, installed packages
or existing nixpp generations. Do not use it as a security sandbox.

The intended user experience is one full-Nix environment: opening TermuxPP starts
the Nix-managed shell directly. A native Android bridge and recovery shell remain
implementation plumbing, not a required `nixpp enter` workflow.

## Usable isolated session

An opt-in Linux/glibc shell is implemented by `session.sh`, `session.nix`,
`prepare-session.py` and `install-session.sh`. It is not packaged or enabled by
an APK. The normal native shell, prefix and nixpp generations remain untouched.
Only AArch64 devices with 4096-byte pages are accepted.

On the disposable worker described below, generate the pinned package manifest:

```sh
nix build --impure --expr 'import /home/agent/runtime/session.nix {
  source = "/home/agent/droid-nix-source";
}' -o /home/agent/session-manifest
cp -L /home/agent/session-manifest /home/agent/manifest.json
```

Stage `launcher` from the patched build, `manifest.json`, `session.sh`,
`system-session.sh`, `install-session.sh` and `prepare-session.py` together in a new app-private
`$HOME/.local/share/termuxpp-nix` directory. Transfer only these small files;
packages download directly on the phone. Verify the transferred launcher hash
before executing it. With ADB stdin transfers use `adb shell -T run-as ...
/system/bin/toybox tee ...`, not a PTY; verify hashes after transfer.

Download the droid-nix v0.9.0 bootstrap APK into a separate new download directory
on the phone. Do not install it. The preparer requires SHA-256
`f74f0de161fb51ccc9a6ffd1a839150c1c88fd88f1b7176e320ec564fc696ea2`.
Run from the normal visible TermuxPP shell, with native Python available:

```sh
chmod 700 "$HOME/.local/share/termuxpp-nix/launcher" \
  "$HOME/.local/share/termuxpp-nix/session.sh" \
  "$HOME/.local/share/termuxpp-nix/install-session.sh"
"$HOME/.local/share/termuxpp-nix/install-session.sh" /absolute/path/to/bootstrap.apk
```

Existing roots are never overwritten. After package-download interruption,
`install-session.sh --resume` repeats signed cache imports and GC-root creation.
An interrupted extraction is not resumable: preserve/inspect the failed directory
and prepare a different empty directory rather than deleting an existing runtime.
A `ready` marker is written only after dynamic Nix and glibc checks succeed.

Start the session with `~/.local/share/termuxpp-nix/session.sh`; use
`session.sh -- COMMAND ARG...` for command mode. The installed test device also
has a `~/.local/bin/termuxpp-nix` symlink. `exit` returns to the unchanged native
shell. There is deliberately no automatic startup change yet. That test symlink
is historical; the planned user-facing system management command is `nixpp`,
reusing the existing name rather than adding a competing command.

### Explicit system candidates

With the matching declarative system tooling, `nixpp prepare` creates a retained
candidate under `system-candidates/` in the native runtime directory. Launch it
explicitly from the native shell:

```sh
"$HOME/.local/share/termuxpp-nix/session.sh" --system HASH-termuxpp-system-HOST -- bash -lc 'echo "$PATH"'
```

`--system` loads a fixed six-line data descriptor, never shell code. It checks
the existing guest identity and uses the candidate's system PATH, Bash,
certificate, terminfo and physical `/etc` snapshot. The snapshot preserves
runtime identity/networking defaults and overlays declared system files.
It does not alter `session.env`, the original `root/etc`, existing sessions,
Home Manager or default login selection. Bootstrap continues to use the original
runtime regardless of prepared candidates. Candidate sessions share the guest
home and store; they are not disposable homes or a sandbox. They have not yet
been qualified on Android. Persistent switch, rollback and child HM activation
remain unsupported.

The descriptor loader tests run under both Bash and mksh on a worker:

```sh
python3 -I nix-runtime/tests/test_system_session.py
TEST_SHELL=mksh python3 -I nix-runtime/tests/test_system_session.py
shellcheck -x -P nix-runtime -s bash nix-runtime/session.sh
```

The default fresh persistent guest home is `/home/nix`, separate from native
dotfiles. Set `TERMUXPP_NIX_USER=pschmitt` when invoking the installer (or pass
`--user pschmitt` directly to the preparer) for `/home/pschmitt`. The username and
home are recorded in `session.env` as `SESSION_USER` and `SESSION_HOME`; existing
runtime files without those fields retain the original `nix` default. This does
not change the Android UID. `login.sh` enters the Home Manager Zsh selected under
the configured home, after that profile is installed. Back up and migrate an
existing home and guest user records before changing these runtime settings.
Bash, Zsh, Git, Python, common shell tools, certificates and Nix are pinned in
the manifest; `~/.nix-profile/bin` takes precedence. Base packages have explicit
GC roots. Sandbox builds and build users are disabled; cache signature checks
remain required. Public DNS resolvers are written into the isolated root.
`/android` exposes host paths: this is **not a sandbox**. Proc metrics use fixtures,
not live CPU/uptime values. Home Manager profiles are maintained separately in
[nixos-config](https://github.com/pschmitt/nixos-config/tree/agent/full-nix-hosts/android/full-nix).

### Allowlisted Android bridge

Linux/glibc processes cannot directly execute Bionic helpers. Stage
`android-bridge.py` and `start-android-bridge.sh` alongside `session.sh`, then run
this from the **native** TermuxPP shell, not inside the guest:

```sh
/system/bin/sh "$HOME/.local/share/termuxpp-nix/start-android-bridge.sh"
```

The script resolves the current core APK's prefix-redirect library and starts
native Python with the native loader environment. The broker supports only
ping, status, battery status and toast. It checks same-UID peer credentials,
uses directory mode 0700/socket mode 0600, rejects extra request fields, and
bounds helper runtime and output. It is not a security boundary between processes
sharing the app UID. No arbitrary shell or private-data API is exposed.

Set `SESSION_TMP_DIR` in the managed `session.env` to a short app-private native
directory (for example `$HOME/.cache/tn`, using its absolute native path), and
create it with mode 0700. The default bridge directory is its `android-bridge`
child. The guest client uses `/tmp/android-bridge/bridge.sock`. An alternative
native directory can be passed with `--socket-dir`; configure the guest client
socket consistently. Relative bind/connect avoids Android-translated Unix socket
path length limits.

Once verified, a `bridge.enabled` marker beside `login.sh` opts into native
broker startup before guest entry. Removing the marker disables future startup;
it does not stop an already-running broker. A live broker is never replaced.
Android may terminate background processes, so startup does not guarantee a
persistent Android service. Keep native recovery available.

Guest-to-native ping, filtered battery status and toast passed on both test
phones. Reproduce broker/client tests on a worker with:

```sh
ANDROID_BRIDGE_CLIENT=/absolute/path/to/android-bridge-client.py \
  python3 -I -m unittest discover -s nix-runtime/tests -p 'test_android_bridge.py'
```

### Verified on Android, 2026-11-17

In the actual TermuxPP `untrusted_app_27` domain, the guest acceptance script
`tests/usable-session.py` passed glibc 2.44/Bash/Zsh, filesystem/Git, threads and
signals, 30 child processes, 256 alternate-stack registration/unmapping cycles,
PTY use, DNS and verified HTTPS. Dynamic Nix 2.34.9 evaluated 42, built and
force-rebuilt an offline glibc-Bash derivation, verified its output and added a
package to a profile. A separate visible-terminal check verified `/home/nix`,
Ctrl-Z suspension, `fg` resumption and Ctrl-C interruption with prompt recovery.

Run the guest suite with `session.sh -- python3 -I /android/ABSOLUTE_TEST_PATH`.
The PTY subprocess test alone does not prove controlling-terminal job control;
that claim comes from the separate visible-terminal check. Remote validation
passed five extraction tests, seven results-checker tests, ShellCheck, Nixfmt
and Statix. No performance, app-restart, resize, companion or production-lifetime
qualification is implied by this acceptance.

## Source and licensing

`upstream/` contains the launcher from
[droid-nix](https://github.com/lassulus/droid-nix/tree/bb08d50117568e9994b6d87c918c4c2d7b5de9b1),
revision `bb08d50117568e9994b6d87c918c4c2d7b5de9b1`. Its MIT license and AUTHORS
are retained. Upstream files are unmodified; `launcher.nix` applies local patches.

SHA-256 of the imported files:

```text
6191544b27abd05a309507ee0df21d463f54074add1e7b5784e69ccb4ffbc04f  launcher.c
bf3a585c844407128e8f6725eb6984af277c95a7db57f1188325dcf39377ddd7  stub.S
330699f4364fe76fb7e790cc1d088806f0d5542dce5faf6e9b4de3f63b677f93  default.nix
```

### Alternate signal stack

The SIGSYS handler uses large path buffers. Executing it on a Go goroutine's
small runtime-managed stack can corrupt that stack. `signal-stack.patch` adds
`SA_ONSTACK`, allowing the kernel to use the alternate signal stack installed by
the application/runtime. It does not allocate a per-thread alternate stack for
applications that have none; in that case the kernel uses the normal stack.

The regression probe is a static Go program with eight goroutines doing 100
translated reads each, followed by shell execution. Matched-UID VM controls run
directly and under the launcher as UID/GID 10000. The unpatched launcher crashes;
the original `signal-stack.patch` passes in that VM but exposes a Nix exit crash
on Android. It is retained as historical evidence, not applied by `launcher.nix`.

The current `signal-lifetime.patch` combines alternate-stack delivery with two
additional compatibility rules:

- `munmap` overlapping the calling thread's registered alternate stack returns
  `EBUSY`. Disabling that registration permits ordinary unmapping again.
- SIGSYS originating inside the raw syscall stub returns `ENOSYS` rather than
  recursively reissuing an Android-denied syscall.

This deliberately avoids scratch-stack switching and per-call allocation. It is
not a complete memory-lifetime solution: another thread can invalidate a stack,
`MAP_FIXED`, `mremap` and `mprotect` are not guarded, and applications ignoring
`EBUSY` may retain mappings until exit. No minimum safe alternate-stack size is
enforced. Do not enable the runtime merely because the regression probes pass.
The VM suite now includes alternate-stack lifetime and inherited syscall-denial
regressions, for 16 checks plus the direct Go control.

## Reproduce on a disposable x86_64 Linux worker

These are heavy builds/tests. Never run them on the local workstation. Use the
repository's remote-worker procedure. Keep closures and initramfs images on the
worker; retain only small console logs before destroying it.

Copy this `nix-runtime` directory to `/home/agent/runtime`, and clone droid-nix to
`/home/agent/droid-nix-source`. Check out the exact revision above. The upstream
flake lock pins the Nixpkgs inputs used by this test; do not substitute an
unreviewed newer checkout.

From the worker:

```sh
nix build --impure --expr 'import /home/agent/runtime/tests/runtime-inputs.nix {
  source = "/home/agent/droid-nix-source";
}' -o /home/agent/runtime-inputs

nix shell --inputs-from /home/agent/droid-nix-source nixpkgs#go --command \
  env CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build \
  -o /home/agent/static-go /home/agent/runtime/tests/static-go.go

bash /home/agent/runtime/tests/prepare-vm.sh \
  /home/agent/runtime-inputs /home/agent/patched-run /home/agent/static-go
```

Repeat with `patched = false;` in the Nix expression, a different input output
link, and a separate run directory to exercise the upstream regression. The
unpatched run is expected to fail. `prepare-vm.sh` creates a fresh root for each
invocation and returns failure if guest assertions fail, even when QEMU itself
exits successfully. Each output directory must be reserved for one run at a time.

The VM uses a full ARM64 kernel under QEMU TCG, not qemu-user. It checks real
seccomp/signal handling. It is not Android and its timings are not useful as
physical-device performance evidence. The guest has no configured network
interface and Nix tests are offline, with sandboxing disabled and no build-user
group. Its Nix closures are preloaded from the worker's store.

Small harness checks:

```sh
python3 -I nix-runtime/tests/test_results.py
shellcheck nix-runtime/tests/prepare-vm.sh nix-runtime/tests/guest-init.sh
```

## Physical Android findings (2026-11-17)

An isolated bootstrap from droid-nix v0.9.0 was tested over an existing TermuxPP
SSH session in the real `untrusted_app_27` SELinux domain, not `runas_app`.
The APK was downloaded and verified, but never installed. The normal prefix,
SSH configuration, default shell and installed APKs were not changed. The test
kernel uses 4096-byte pages. Under the launcher, `/proc/self/status` reported
`TracerPid: 0`, `Seccomp: 2` and two seccomp filters before diagnostic tracing.

| Probe | Upstream launcher | `SA_ONSTACK` launcher |
| --- | --- | --- |
| Static Go reads, threads and exec | Exit 2 | Pass, exit 0 |
| Released static Nix 2.34.9 `--version` | Exit 0 | Correct output, then exit 139 |
| Nix arithmetic evaluation | 42, exit 0 | 42, then exit 139 |
| Offline BusyBox derivation | Exit 159 | Not tested |

The static Go probe was cross-built with Go 1.26.10. Nix and BusyBox were the
release bootstrap's static musl binaries, not the VM suite's closures. Fake
`/proc/stat` and `/proc/uptime` fixtures followed upstream's workaround for
unreadable Android proc files; these are not real system metrics. Removing the
proc warning and setting `GC_DONT_GC=1` did not resolve the patched Nix crash.

### Alternate-stack lifetime failure

A syscall trace shows Nix installing an alternate stack with `ss_size=22528`
inside a 24576-byte mapping. During exit it unmaps that mapping without first
disabling the alternate stack. Its subsequent `rt_sigprocmask` is intercepted by
the launcher. With `SA_ONSTACK`, signal delivery then fails with kernel-generated
SIGSEGV. This is evidence of a stale alternate-stack mapping, not confirmation
of the earlier small-stack-overflow hypothesis. Merely allocating a larger
alternate stack does not address the observed lifetime failure.

A production solution must handle both Go's small runtime stacks and guest
alternate-stack lifetimes, including per-thread ownership, nested signals,
unmapping and thread teardown. Neither unconditional `SA_ONSTACK` nor reverting
it meets both tested runtimes' requirements.

### Android-denied syscall recursion

The upstream local build trace repeatedly issues `fchmodat2` from the launcher's
raw-syscall stub and receives SIGSYS at that same address. The handler recursively
reissues the denied syscall until stack exhaustion and fatal signal handling.
The precise Android policy rule was not separately inspected; the trace establishes
that the raw-stub exemption does not prevent this syscall from trapping.

A **diagnostic-only** upstream variant returns `-ENOSYS` for `fchmodat2` before
entering the path handler. With that variant, the offline derivation passes,
its output contains `android-build-pass`, and an untraced `--rebuild` also exits
0. This validates libc fallback for this workload, not complete chmod/symlink
semantics or a general solution for every Android-denied syscall. The variant
does not use `SA_ONSTACK`, so it does not resolve the Go regression.

`tests/make-android-diagnostic.py SOURCE OUTPUT` reproduces that variant from the
pinned upstream launcher directory. It also logs `sigaltstack` requests. Build
it on a worker using the same static cross `callPackage` as `launcher.nix`, with
`OUTPUT` as the source directory; do not package this diagnostic in an APK.
Structured results and artifact hashes are in
[`tests/android-evidence.json`](tests/android-evidence.json). Raw traces are
retained privately because they contain device paths. No performance comparison,
full glibc suite, PTY/job-control, lifecycle or companion acceptance is claimed.

### Combined lifetime/fallback candidate

The smaller `signal-lifetime.patch` passed all 16 VM checks and the direct Go
control. In a visible TermuxPP session in `untrusted_app_27`, the same binary
passed ten Go runs, ten Nix version probes, ten arithmetic evaluations, a fresh
offline BusyBox derivation, a forced rebuild and output verification: all 33 exit
statuses were zero and expected output markers were checked. Artifact and log
hashes are recorded in
[`tests/signal-lifetime-evidence.json`](tests/signal-lifetime-evidence.json).

An intermediate owned scratch-stack implementation passed the VM suite but
failed all three Android Go runs (139, 139, 2), despite successful Nix builds.
A subsequent nested-signal revision passed the VM but was not phone-tested;
the smaller no-switching candidate was selected instead. The exact cause of
the intermediate Go corruption has not been established. VM success alone must
not be treated as Android acceptance.

These results clear the specific observed Go/Nix regressions on the tested
4 KiB AArch64 device, not the production gates below. The lifetime guard is a
conservative compatibility restriction, not a complete stack-ownership design.

## Acceptance gates and integration sequence

1. **Launcher regression gate.** Keep the unmodified/patched matched-UID Go
   control, process/thread/signal/raw-syscall tests, Nix registration/evaluation,
   local derivation, GC-root registration and content verification reproducible.
   Test additional Go versions and nested/asynchronous signal delivery before
   treating `SA_ONSTACK` as comprehensive hardening.
2. **Actual Android gate.** Obtain exclusive device ownership through Concord.
   Test inside the TermuxPP app domain, not just `adb shell`: inherited Android
   seccomp, SELinux, execution policy, PTYs, job control, Ctrl-C, terminal resize,
   concurrent sessions, app restart and foreground/background transitions. No
   data clearing, prefix replacement or default-shell change for this probe.
3. **ABI/page-size gate.** The imported launcher is AArch64-only and assumes
   4096-byte pages. Do not enable it on other ABIs or 16 KiB kernels. Add an
   explicit unsupported-device check before enabling runtime selection; dynamic
   page-size support requires loader/auxv changes and its own kernel tests.
4. **APK integration.** Package the launcher in the APK native-library directory,
   derive its installed location from Android, and start an explicit full-Nix
   session. Clear Bionic preload/library variables at the Linux boundary. Retain
   a native recovery entrypoint independent of Nix profiles and bootstrap state.
5. **Store/bootstrap ownership.** Use a separate app-private runtime directory
   with a virtual standard `/nix/store`. APK owns the launcher, a verified,
   versioned bootstrap owns initial installation, and Nix owns store/profiles.
   Design interrupted-download/extraction recovery, upgrade compatibility and
   rollback before activation. Never reinterpret today's reference-free nixpp
   bundles as ordinary Linux closures or mutate signed downloaded contents.
6. **Android bridge.** Preserve TermuxPP API/Tasker identities, signature/UID and
   execution authorization checks, helper package holds, and read-only-dex
   handling. Test a deliberate clean-environment transition back to Bionic
   helpers; do not blindly inherit upstream droid-nix companion identifiers.
7. **Performance gate.** On the same physical phone compare identical closures
   under this launcher and PRoot. Measure repeated cold/warm shell startup,
   Nix evaluation, process-heavy shell work, metadata-heavy tree walks, unpacking
   and a representative build. Record distributions, thermal state and storage
   footprint. Include native controls where meaningful; do not infer speedup
   from the absence of ptrace alone.
8. **Single-environment activation.** After those gates pass, make new TermuxPP
   sessions start the Nix/Home Manager shell directly. Preserve existing prefix
   and generation backups until restart, upgrade, rollback and real Tasker/API
   acceptance pass. Update the maintained TermuxPP/upstream comparison in the
   same change that enables runtime behavior.

## Remaining compatibility limits

SIGSYS is reserved by the launcher. `clone3` is forced to return ENOSYS. Hard
links are emulated and store optimisation needs separate qualification. Mounts,
privileged namespaces, System V IPC and a normal NixOS/systemd environment are
not provided. The VM smoke suite does not test cache downloads/signatures,
actual garbage collection, Android helpers, application lifecycle or performance.
