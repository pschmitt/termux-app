# Full Nix runtime (experimental)

This directory is a compatibility/hardening prototype, not an enabled TermuxPP
runtime. It does not change APK packaging, startup, `$PREFIX`, installed packages
or existing nixpp generations. Do not use it as a security sandbox.

The intended user experience is one full-Nix environment: opening TermuxPP starts
the Nix-managed shell directly. A native Android bridge and recovery shell remain
implementation plumbing, not a required `nixpp enter` workflow.

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
