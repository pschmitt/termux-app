import pathlib
import sys

source = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
output.mkdir()
for name in ['default.nix', 'stub.S']:
    (output / name).write_bytes((source / name).read_bytes())
text = (source / 'launcher.c').read_text()
needle = '\tlong nr = si->si_syscall;\n'
assert text.count(needle) == 1
text = text.replace(needle, needle + '''
    if (nr == SYS_fchmodat2) {
        r[0] = (unsigned long)-ENOSYS;
        return;
    }
    if (nr == SYS_sigaltstack) {
        if (r[0]) {
            stack_t *ss = (stack_t *)r[0];
            out(2, "ALTSTACK_SIZE=");
            outn(2, ss->ss_size);
            out(2, " FLAGS=");
            outn(2, ss->ss_flags);
            out(2, "\\n");
        }
        r[0] = (unsigned long)SYS2(SYS_sigaltstack, r[0], r[1]);
        return;
    }
''')
text = text.replace('static const int traced[] = {', 'static const int traced[] = {\n SYS_sigaltstack,')
(output / 'launcher.c').write_text(text)
