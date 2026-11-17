import json
import pathlib
import runpy
import unittest

module = runpy.run_path(str(pathlib.Path(__file__).with_name('check-results.py')))
check = module['check']
names = sorted(module['EXPECTED'])


class ResultValidationTest(unittest.TestCase):
    def fixture(self):
        return '\n'.join([
            'EVAL_DIRECT_GO_EXIT=0',
            'EVAL_UNPRIVILEGED_UID=10000',
            'EVAL_GUEST_END',
            *[json.dumps({'test': name, 'pass': True}) for name in names],
            'EVAL_GUEST_EXIT=0',
        ])

    def test_success(self):
        self.assertEqual(len(check(self.fixture())), len(names))

    def test_missing_test(self):
        with self.assertRaises(ValueError):
            check(self.fixture().replace(json.dumps({'test': names[0], 'pass': True}), ''))

    def test_failed_test(self):
        with self.assertRaises(ValueError):
            check(self.fixture().replace('"pass": true', '"pass": false', 1))

    def test_duplicate_test(self):
        with self.assertRaises(ValueError):
            check(self.fixture() + '\n' + json.dumps({'test': names[0], 'pass': True}))

    def test_nonzero_guest_exit(self):
        with self.assertRaises(ValueError):
            check(self.fixture().replace('EVAL_GUEST_EXIT=0', 'EVAL_GUEST_EXIT=1'))

    def test_failed_control(self):
        with self.assertRaises(ValueError):
            check(self.fixture().replace('EVAL_DIRECT_GO_EXIT=0', 'EVAL_DIRECT_GO_EXIT=1'))

    def test_incomplete_boot(self):
        with self.assertRaises(ValueError):
            check('Booting Linux...')


if __name__ == '__main__':
    unittest.main()
