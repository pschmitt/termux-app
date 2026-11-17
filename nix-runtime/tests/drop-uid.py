import os
import sys

os.setgroups([])
os.setgid(10000)
os.setuid(10000)
print('EVAL_UNPRIVILEGED_UID=' + str(os.getuid()), flush=True)
os.execv(sys.argv[1], sys.argv[1:])
