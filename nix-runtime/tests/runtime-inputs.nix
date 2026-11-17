{
  source,
  patched ? true,
}:
let
  f = builtins.getFlake source;
  host = f.inputs.nixpkgs.legacyPackages.x86_64-linux;
  guest = f.inputs.nixpkgs.legacyPackages.aarch64-linux;
  launcher =
    if patched then
      import ../launcher.nix { pkgs = host; }
    else
      host.pkgsCross.aarch64-multiplatform-musl.pkgsStatic.callPackage ../upstream { };
in
host.linkFarm "droid-nix-runtime-inputs" [
  {
    name = "kernel";
    path = guest.linuxPackages.kernel;
  }
  {
    name = "busybox";
    path = guest.busybox;
  }
  {
    name = "bash";
    path = guest.bash;
  }
  {
    name = "coreutils";
    path = guest.coreutils;
  }
  {
    name = "python";
    path = guest.python3;
  }
  {
    name = "nix";
    path = guest.nix;
  }
  {
    name = "qemu";
    path = host.qemu;
  }
  {
    name = "cpio";
    path = host.cpio;
  }
  {
    name = "launcher";
    path = launcher;
  }
]
