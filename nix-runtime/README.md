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
translated reads each, followed by shell execution. Matched-UID controls run
directly and under the launcher as UID/GID 10000. The unpatched launcher crashes;
the patched launcher passes. This is a bounded compatibility result, not a proof
of arbitrary signal nesting or compatibility with every Go version.

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
