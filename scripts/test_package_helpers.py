import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("helpers", Path(__file__).with_name("package-helpers.py"))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
spec = importlib.util.spec_from_file_location("bootstrap", Path(__file__).with_name("build-aarch64-bootstrap.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class HelperPackageTests(unittest.TestCase):
    def test_original_api_cli_scripts_are_packaged_with_fork_prefix(self):
        api_source = Path(__file__).parent.parent / "termux-api-package"
        files = helpers.api_cli_scripts(api_source)
        self.assertGreaterEqual(len(files), 50)
        self.assertIn("bin/termux-battery-status", files)
        self.assertIn("bin/termux-share", files)

        for bootstrap_mode in (False, True):
            with self.subTest(bootstrap=bootstrap_mode), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                helpers.package(out, "api", "1", "aarch64", "bash", files, bootstrap_mode)
                deb = bootstrap.parse_ar((out / "api_1_aarch64.deb").read_bytes())
                prefix = helpers.BOOTSTRAP_PREFIX if bootstrap_mode else helpers.PREFIX
                with bootstrap.open_tar("data.tar.gz", deb["data.tar.gz"]) as archive:
                    for relative, (expected, _, _) in files.items():
                        member = archive.getmember(prefix + "/" + relative)
                        self.assertEqual(member.mode, 0o755)
                        self.assertEqual(archive.extractfile(member).read(), expected)
                        shebangs = (
                            ("#!" + "/" + helpers.PREFIX + "/bin/bash").encode(),
                            ("#!" + "/" + helpers.PREFIX + "/bin/sh").encode(),
                        )
                        self.assertTrue(expected.startswith(shebangs))
                        self.assertNotIn(b"@TERMUX_PREFIX@", expected)

    def test_bootstrap_package_roundtrip_and_reproducibility(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            files = {"bin/am": (b"#!/system/bin/sh\n", 0o755, None)}
            helpers.package(out, "helper", "1+termuxpp1", "all", "", files, True)
            path = out / "helper_1+termuxpp1_all.deb"
            original = path.read_bytes()
            deb = bootstrap.Deb(str(path))
            self.assertEqual(deb.name, "helper")
            tree = bootstrap.Tree()
            bootstrap.install_deb(tree, deb, False, False)
            self.assertEqual(bootstrap.verify_tree(tree, ["helper"]), [])
            self.assertEqual(tree.files["bin/am"][1], 0o755)
            helpers.package(out, "helper", "1+termuxpp1", "all", "", files, True)
            self.assertEqual(original, path.read_bytes())

    def test_device_package_is_not_accepted_as_bootstrap_input(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            helpers.package(out, "helper", "1", "all", "", {"bin/am": (b"x", 0o755, None)}, False)
            with self.assertRaises(bootstrap.BootstrapError):
                bootstrap.Deb(str(out / "helper_1_all.deb"))

    def test_regular_files_are_owner_writable_in_device_and_bootstrap_packages(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            files = {
                "bin/executable": (b"binary", 0o555, None),
                "lib/read-only.so": (b"library", 0o444, None),
                "lib/already-writable.so": (b"library", 0o755, None),
                "lib/link.so": (b"", 0o777, "read-only.so"),
            }

            for bootstrap_mode in (False, True):
                helpers.package(out, "helper", "1", "all", "", files, bootstrap_mode)
                path = out / "helper_1_all.deb"
                members = bootstrap.parse_ar(path.read_bytes())
                prefix = helpers.BOOTSTRAP_PREFIX if bootstrap_mode else helpers.PREFIX
                with bootstrap.open_tar("data.tar.gz", members["data.tar.gz"]) as archive:
                    self.assertEqual(archive.getmember(prefix + "/bin/executable").mode, 0o755)
                    self.assertEqual(archive.getmember(prefix + "/lib/read-only.so").mode, 0o644)
                    self.assertEqual(archive.getmember(prefix + "/lib/already-writable.so").mode, 0o755)
                    self.assertTrue(archive.getmember(prefix + "/lib/link.so").issym())
