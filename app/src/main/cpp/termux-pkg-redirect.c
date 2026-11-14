#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

extern char **environ;

static const char OLD_DATA_PREFIX[] = "/data/data/com.termux";
static const size_t OLD_DATA_PREFIX_LEN = sizeof(OLD_DATA_PREFIX) - 1;

static const char OLD_USER_PREFIX[] = "/data/user/0/com.termux";
static const size_t OLD_USER_PREFIX_LEN = sizeof(OLD_USER_PREFIX) - 1;

static char g_target_dir[PATH_MAX];
static size_t g_target_dir_len = 0;

__attribute__((constructor))
static void init_redirect_target(void) {
    const char *env_dir = getenv("TERMUX_APP__DATA_DIR");
    if (!env_dir || !*env_dir)
        return;
    if (strcmp(env_dir, OLD_DATA_PREFIX) == 0 || strcmp(env_dir, OLD_USER_PREFIX) == 0)
        return;
    size_t len = strlen(env_dir);
    while (len > 1 && env_dir[len - 1] == '/')
        len--;
    if (len >= sizeof(g_target_dir))
        return;
    memcpy(g_target_dir, env_dir, len);
    g_target_dir[len] = '\0';
    g_target_dir_len = len;
}

static const char *rewrite_path(const char *path, char *buf, size_t buf_size) {
    if (!path || g_target_dir_len == 0)
        return path;

    size_t match_len = 0;
    if (strncmp(path, OLD_DATA_PREFIX, OLD_DATA_PREFIX_LEN) == 0 &&
        (path[OLD_DATA_PREFIX_LEN] == '\0' || path[OLD_DATA_PREFIX_LEN] == '/')) {
        match_len = OLD_DATA_PREFIX_LEN;
    } else if (strncmp(path, OLD_USER_PREFIX, OLD_USER_PREFIX_LEN) == 0 &&
               (path[OLD_USER_PREFIX_LEN] == '\0' || path[OLD_USER_PREFIX_LEN] == '/')) {
        match_len = OLD_USER_PREFIX_LEN;
    } else {
        return path;
    }

    const char *rest = path + match_len;
    size_t rest_len = strlen(rest);
    if (g_target_dir_len + rest_len >= buf_size)
        return path;

    memcpy(buf, g_target_dir, g_target_dir_len);
    memcpy(buf + g_target_dir_len, rest, rest_len + 1);
    return buf;
}

#define RESOLVE_REAL(name, ret_type, args) \
    typedef ret_type (*fn_type) args; \
    static fn_type real_fn = NULL; \
    if (!real_fn) { \
        real_fn = (fn_type) dlsym(RTLD_NEXT, #name); \
    } \
    if (!real_fn) { \
        errno = ENOSYS; \
    }

int open(const char *pathname, int flags, ...) {
    RESOLVE_REAL(open, int, (const char *, int, ...));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    const char *p = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t) va_arg(ap, int);
        va_end(ap);
        return real_fn(p, flags, mode);
    }
    return real_fn(p, flags);
}

int open64(const char *pathname, int flags, ...) {
    RESOLVE_REAL(open64, int, (const char *, int, ...));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    const char *p = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t) va_arg(ap, int);
        va_end(ap);
        return real_fn(p, flags, mode);
    }
    return real_fn(p, flags);
}

int __open_2(const char *pathname, int flags) {
    RESOLVE_REAL(__open_2, int, (const char *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), flags);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    RESOLVE_REAL(openat, int, (int, const char *, int, ...));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    const char *p = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t) va_arg(ap, int);
        va_end(ap);
        return real_fn(dirfd, p, flags, mode);
    }
    return real_fn(dirfd, p, flags);
}

int openat64(int dirfd, const char *pathname, int flags, ...) {
    RESOLVE_REAL(openat64, int, (int, const char *, int, ...));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    const char *p = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t) va_arg(ap, int);
        va_end(ap);
        return real_fn(dirfd, p, flags, mode);
    }
    return real_fn(dirfd, p, flags);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    RESOLVE_REAL(__openat_2, int, (int, const char *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), flags);
}

int creat(const char *pathname, mode_t mode) {
    RESOLVE_REAL(creat, int, (const char *, mode_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

FILE *fopen(const char *pathname, const char *mode) {
    RESOLVE_REAL(fopen, FILE *, (const char *, const char *));
    if (!real_fn)
        return NULL;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    RESOLVE_REAL(freopen, FILE *, (const char *, const char *, FILE *));
    if (!real_fn)
        return NULL;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode, stream);
}

int stat(const char *pathname, struct stat *statbuf) {
    RESOLVE_REAL(stat, int, (const char *, struct stat *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int stat64(const char *pathname, struct stat64 *statbuf) {
    RESOLVE_REAL(stat64, int, (const char *, struct stat64 *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int lstat(const char *pathname, struct stat *statbuf) {
    RESOLVE_REAL(lstat, int, (const char *, struct stat *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int lstat64(const char *pathname, struct stat64 *statbuf) {
    RESOLVE_REAL(lstat64, int, (const char *, struct stat64 *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    RESOLVE_REAL(fstatat, int, (int, const char *, struct stat *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), statbuf, flags);
}

int fstatat64(int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    RESOLVE_REAL(fstatat64, int, (int, const char *, struct stat64 *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), statbuf, flags);
}

int access(const char *pathname, int mode) {
    RESOLVE_REAL(access, int, (const char *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    RESOLVE_REAL(faccessat, int, (int, const char *, int, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), mode, flags);
}

int chdir(const char *path) {
    RESOLVE_REAL(chdir, int, (const char *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(path, buf, sizeof(buf)));
}

int chmod(const char *pathname, mode_t mode) {
    RESOLVE_REAL(chmod, int, (const char *, mode_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    RESOLVE_REAL(fchmodat, int, (int, const char *, mode_t, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), mode, flags);
}

int chown(const char *pathname, uid_t owner, gid_t group) {
    RESOLVE_REAL(chown, int, (const char *, uid_t, gid_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), owner, group);
}

int lchown(const char *pathname, uid_t owner, gid_t group) {
    RESOLVE_REAL(lchown, int, (const char *, uid_t, gid_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), owner, group);
}

int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    RESOLVE_REAL(fchownat, int, (int, const char *, uid_t, gid_t, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), owner, group, flags);
}

int mkdir(const char *pathname, mode_t mode) {
    RESOLVE_REAL(mkdir, int, (const char *, mode_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    RESOLVE_REAL(mkdirat, int, (int, const char *, mode_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int rmdir(const char *pathname) {
    RESOLVE_REAL(rmdir, int, (const char *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)));
}

int unlink(const char *pathname) {
    RESOLVE_REAL(unlink, int, (const char *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)));
}

int unlinkat(int dirfd, const char *pathname, int flags) {
    RESOLVE_REAL(unlinkat, int, (int, const char *, int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), flags);
}

int remove(const char *pathname) {
    RESOLVE_REAL(remove, int, (const char *));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(pathname, buf, sizeof(buf)));
}

int rename(const char *oldpath, const char *newpath) {
    RESOLVE_REAL(rename, int, (const char *, const char *));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(rewrite_path(oldpath, buf1, sizeof(buf1)),
                   rewrite_path(newpath, buf2, sizeof(buf2)));
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    RESOLVE_REAL(renameat, int, (int, const char *, int, const char *));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(olddirfd, rewrite_path(oldpath, buf1, sizeof(buf1)),
                   newdirfd, rewrite_path(newpath, buf2, sizeof(buf2)));
}

int link(const char *oldpath, const char *newpath) {
    RESOLVE_REAL(link, int, (const char *, const char *));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(rewrite_path(oldpath, buf1, sizeof(buf1)),
                   rewrite_path(newpath, buf2, sizeof(buf2)));
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    RESOLVE_REAL(linkat, int, (int, const char *, int, const char *, int));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(olddirfd, rewrite_path(oldpath, buf1, sizeof(buf1)),
                   newdirfd, rewrite_path(newpath, buf2, sizeof(buf2)), flags);
}

int symlink(const char *target, const char *linkpath) {
    RESOLVE_REAL(symlink, int, (const char *, const char *));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(rewrite_path(target, buf1, sizeof(buf1)),
                   rewrite_path(linkpath, buf2, sizeof(buf2)));
}

int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    RESOLVE_REAL(symlinkat, int, (const char *, int, const char *));
    if (!real_fn)
        return -1;
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    return real_fn(rewrite_path(target, buf1, sizeof(buf1)),
                   newdirfd, rewrite_path(linkpath, buf2, sizeof(buf2)));
}

ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    RESOLVE_REAL(readlink, ssize_t, (const char *, char *, size_t));
    if (!real_fn)
        return -1;
    char pbuf[PATH_MAX];
    return real_fn(rewrite_path(pathname, pbuf, sizeof(pbuf)), buf, bufsiz);
}

ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    RESOLVE_REAL(readlinkat, ssize_t, (int, const char *, char *, size_t));
    if (!real_fn)
        return -1;
    char pbuf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, pbuf, sizeof(pbuf)), buf, bufsiz);
}

char *realpath(const char *path, char *resolved_path) {
    RESOLVE_REAL(realpath, char *, (const char *, char *));
    if (!real_fn)
        return NULL;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(path, buf, sizeof(buf)), resolved_path);
}

DIR *opendir(const char *name) {
    RESOLVE_REAL(opendir, DIR *, (const char *));
    if (!real_fn)
        return NULL;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(name, buf, sizeof(buf)));
}

int truncate(const char *path, off_t length) {
    RESOLVE_REAL(truncate, int, (const char *, off_t));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(path, buf, sizeof(buf)), length);
}

int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    RESOLVE_REAL(utimensat, int, (int, const char *, const struct timespec[2], int));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(dirfd, rewrite_path(pathname, buf, sizeof(buf)), times, flags);
}

int utimes(const char *filename, const struct timeval times[2]) {
    RESOLVE_REAL(utimes, int, (const char *, const struct timeval[2]));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(filename, buf, sizeof(buf)), times);
}

int statfs(const char *path, struct statfs *buf) {
    RESOLVE_REAL(statfs, int, (const char *, struct statfs *));
    if (!real_fn)
        return -1;
    char pbuf[PATH_MAX];
    return real_fn(rewrite_path(path, pbuf, sizeof(pbuf)), buf);
}

int statvfs(const char *path, struct statvfs *buf) {
    RESOLVE_REAL(statvfs, int, (const char *, struct statvfs *));
    if (!real_fn)
        return -1;
    char pbuf[PATH_MAX];
    return real_fn(rewrite_path(path, pbuf, sizeof(pbuf)), buf);
}

void *dlopen(const char *filename, int flags) {
    RESOLVE_REAL(dlopen, void *, (const char *, int));
    if (!real_fn)
        return NULL;
    char buf[PATH_MAX];
    return real_fn(rewrite_path(filename, buf, sizeof(buf)), flags);
}

static const char *resolve_exec_path(const char *file, char *buf, size_t buf_size) {
    if (!file)
        return NULL;
    if (strchr(file, '/') != NULL)
        return rewrite_path(file, buf, buf_size);

    const char *path_env = getenv("PATH");
    if (!path_env || !*path_env)
        return file;

    typedef int (*access_fn_t)(const char *, int);
    static access_fn_t real_access = NULL;
    if (!real_access)
        real_access = (access_fn_t) dlsym(RTLD_NEXT, "access");

    size_t file_len = strlen(file);
    const char *p = path_env;
    while (*p) {
        const char *colon = strchr(p, ':');
        size_t dir_len = colon ? (size_t) (colon - p) : strlen(p);
        char dir_buf[PATH_MAX];
        char rewritten_dir[PATH_MAX];
        const char *dir = ".";
        if (dir_len > 0 && dir_len < sizeof(dir_buf)) {
            memcpy(dir_buf, p, dir_len);
            dir_buf[dir_len] = '\0';
            dir = rewrite_path(dir_buf, rewritten_dir, sizeof(rewritten_dir));
        }
        size_t rdir_len = strlen(dir);
        if (rdir_len + 1 + file_len < buf_size) {
            memcpy(buf, dir, rdir_len);
            buf[rdir_len] = '/';
            memcpy(buf + rdir_len + 1, file, file_len + 1);
            if (real_access && real_access(buf, X_OK) == 0)
                return buf;
        }
        if (!colon)
            break;
        p = colon + 1;
    }
    return file;
}

/*
 * Interpreters of the form /bin/NAME or /usr/bin/NAME (including /usr/bin/env)
 * do not exist on Android. Map them to the matching executable in the app
 * prefix, but only when the original path is absent, so real system files
 * (e.g. /system/bin/sh, or a /bin that does exist) are never shadowed.
 */
static const char *rewrite_conventional_interp(const char *interp, char *buf, size_t buf_size) {
    const char *name;
    if (strncmp(interp, "/usr/bin/", 9) == 0)
        name = interp + 9;
    else if (strncmp(interp, "/bin/", 5) == 0)
        name = interp + 5;
    else
        return interp;

    if (!*name || strchr(name, '/') != NULL)
        return interp;

    typedef int (*access_fn_t)(const char *, int);
    static access_fn_t real_access = NULL;
    if (!real_access)
        real_access = (access_fn_t) dlsym(RTLD_NEXT, "access");
    if (!real_access || real_access(interp, X_OK) == 0)
        return interp;

    static const char PREFIX_BIN[] = "/files/usr/bin/";
    size_t name_len = strlen(name);
    if (g_target_dir_len + sizeof(PREFIX_BIN) - 1 + name_len >= buf_size)
        return interp;
    memcpy(buf, g_target_dir, g_target_dir_len);
    memcpy(buf + g_target_dir_len, PREFIX_BIN, sizeof(PREFIX_BIN) - 1);
    memcpy(buf + g_target_dir_len + sizeof(PREFIX_BIN) - 1, name, name_len + 1);
    if (real_access(buf, X_OK) != 0)
        return interp;
    return buf;
}

struct shebang_exec {
    char interp[PATH_MAX];
    char *opt;
    char **argv;
};

/*
 * If PATH is a script whose interpreter must be redirected, fill OUT with the
 * new interpreter path and an argv equivalent to what the kernel would build
 * (interpreter, optional single argument, script path, original arguments).
 * Returns 1 if OUT is filled (caller frees OUT->argv), 0 if the plain
 * exec/spawn should proceed.
 */
static int shebang_prepare(const char *path, char *const argv[], struct shebang_exec *out) {
    if (!path || g_target_dir_len == 0)
        return 0;

    typedef int (*open_fn_t)(const char *, int, ...);
    static open_fn_t real_open = NULL;
    if (!real_open)
        real_open = (open_fn_t) dlsym(RTLD_NEXT, "open");
    if (!real_open)
        return 0;

    int fd = real_open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return 0;

    char hdr[256];
    ssize_t n = read(fd, hdr, sizeof(hdr) - 1);
    close(fd);
    if (n < 3 || hdr[0] != '#' || hdr[1] != '!')
        return 0;

    hdr[n] = '\0';
    char *nl = strchr(hdr, '\n');
    if (nl)
        *nl = '\0';

    char *p = hdr + 2;
    while (*p == ' ' || *p == '\t')
        p++;
    if (!*p)
        return 0;

    char *interp = p;
    while (*p && *p != ' ' && *p != '\t')
        p++;
    char *opt_arg = NULL;
    if (*p) {
        *p++ = '\0';
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p) {
            opt_arg = p;
            size_t len = strlen(opt_arg);
            while (len > 0 && (opt_arg[len - 1] == ' ' || opt_arg[len - 1] == '\t' ||
                               opt_arg[len - 1] == '\r'))
                opt_arg[--len] = '\0';
            if (!*opt_arg)
                opt_arg = NULL;
        }
    }

    char interp_buf[PATH_MAX];
    const char *rewritten_interp = rewrite_path(interp, interp_buf, sizeof(interp_buf));
    if (rewritten_interp == interp)
        rewritten_interp = rewrite_conventional_interp(interp, interp_buf, sizeof(interp_buf));
    if (rewritten_interp == interp)
        return 0;
    if (strlen(rewritten_interp) >= sizeof(out->interp))
        return 0;

    size_t argc = 0;
    while (argv && argv[argc])
        argc++;

    char **new_argv = malloc((4 + argc) * sizeof(char *));
    if (!new_argv)
        return 0;
    strcpy(out->interp, rewritten_interp);

    /* opt_arg lives in the stack buffer hdr: duplicate it. */
    char *opt_copy = NULL;
    if (opt_arg) {
        opt_copy = strdup(opt_arg);
        if (!opt_copy) {
            free(new_argv);
            return 0;
        }
    }

    size_t idx = 0;
    new_argv[idx++] = out->interp;
    if (opt_copy)
        new_argv[idx++] = opt_copy;
    new_argv[idx++] = (char *) path;
    for (size_t i = 1; i < argc; i++)
        new_argv[idx++] = argv[i];
    new_argv[idx] = NULL;
    out->opt = opt_copy;
    out->argv = new_argv;
    return 1;
}

static void shebang_free(struct shebang_exec *sb) {
    free(sb->opt);
    free(sb->argv);
    sb->opt = NULL;
    sb->argv = NULL;
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    RESOLVE_REAL(execve, int, (const char *, char *const[], char *const[]));
    if (!real_fn)
        return -1;
    char buf[PATH_MAX];
    const char *rewritten = rewrite_path(pathname, buf, sizeof(buf));
    struct shebang_exec sb;
    if (shebang_prepare(rewritten, argv, &sb)) {
        real_fn(sb.interp, sb.argv, envp);
        int saved_errno = errno;
        shebang_free(&sb);
        errno = saved_errno;
        return -1;
    }
    return real_fn(rewritten, argv, envp);
}

int execv(const char *pathname, char *const argv[]) {
    return execve(pathname, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
    char buf[PATH_MAX];
    return execve(resolve_exec_path(file, buf, sizeof(buf)), argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX];
    return execve(resolve_exec_path(file, buf, sizeof(buf)), argv, envp);
}

static int call_execv_va(const char *path, int use_path, const char *arg0, va_list ap, int has_envp) {
    size_t cap = 16;
    size_t n = 0;
    char **argv = malloc(cap * sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    argv[n++] = (char *) arg0;
    char *arg;
    while ((arg = va_arg(ap, char *)) != NULL) {
        if (n + 1 >= cap) {
            cap *= 2;
            char **tmp = realloc(argv, cap * sizeof(char *));
            if (!tmp) {
                free(argv);
                errno = ENOMEM;
                return -1;
            }
            argv = tmp;
        }
        argv[n++] = arg;
    }
    argv[n] = NULL;
    char *const *envp = has_envp ? va_arg(ap, char *const *) : environ;

    int rc;
    if (use_path)
        rc = execvpe(path, argv, envp);
    else
        rc = execve(path, argv, envp);
    int saved_errno = errno;
    free(argv);
    errno = saved_errno;
    return rc;
}

int execl(const char *pathname, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    int rc = call_execv_va(pathname, 0, arg, ap, 0);
    va_end(ap);
    return rc;
}

int execlp(const char *file, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    int rc = call_execv_va(file, 1, arg, ap, 0);
    va_end(ap);
    return rc;
}

int execle(const char *pathname, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    int rc = call_execv_va(pathname, 0, arg, ap, 1);
    va_end(ap);
    return rc;
}

static int spawn_with_shebang(int (*real_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                            const posix_spawnattr_t *, char *const[], char *const[]),
                             pid_t *pid, const char *path,
                             const posix_spawn_file_actions_t *file_actions,
                             const posix_spawnattr_t *attrp, char *const argv[],
                             char *const envp[]) {
    struct shebang_exec sb;
    if (shebang_prepare(path, argv, &sb)) {
        int rc = real_fn(pid, sb.interp, file_actions, attrp, sb.argv, envp);
        shebang_free(&sb);
        return rc;
    }
    return real_fn(pid, path, file_actions, attrp, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *file_actions,
                const posix_spawnattr_t *attrp, char *const argv[], char *const envp[]) {
    RESOLVE_REAL(posix_spawn, int, (pid_t *, const char *, const posix_spawn_file_actions_t *,
                                    const posix_spawnattr_t *, char *const[], char *const[]));
    if (!real_fn)
        return ENOSYS;
    char buf[PATH_MAX];
    return spawn_with_shebang(real_fn, pid, rewrite_path(path, buf, sizeof(buf)), file_actions, attrp,
                              argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *file_actions,
                 const posix_spawnattr_t *attrp, char *const argv[], char *const envp[]) {
    RESOLVE_REAL(posix_spawnp, int, (pid_t *, const char *, const posix_spawn_file_actions_t *,
                                    const posix_spawnattr_t *, char *const[], char *const[]));
    if (!real_fn)
        return ENOSYS;
    char buf[PATH_MAX];
    return spawn_with_shebang(real_fn, pid, resolve_exec_path(file, buf, sizeof(buf)), file_actions,
                              attrp, argv, envp);
}

/* AF_UNIX sockets (e.g. ssh ControlPath) carry filesystem paths too. */
static const struct sockaddr *rewrite_sockaddr(const struct sockaddr *addr, socklen_t *len,
                                               struct sockaddr_un *out) {
    if (!addr || g_target_dir_len == 0 || addr->sa_family != AF_UNIX ||
        (size_t) *len <= offsetof(struct sockaddr_un, sun_path) || (size_t) *len > sizeof(*out))
        return addr;
    const struct sockaddr_un *un = (const struct sockaddr_un *) addr;
    if (un->sun_path[0] == '\0')
        return addr; /* abstract socket */
    size_t max = (size_t) *len - offsetof(struct sockaddr_un, sun_path);
    char path[sizeof(un->sun_path) + 1];
    size_t plen = strnlen(un->sun_path, max < sizeof(un->sun_path) ? max : sizeof(un->sun_path));
    memcpy(path, un->sun_path, plen);
    path[plen] = '\0';
    char buf[PATH_MAX];
    const char *np = rewrite_path(path, buf, sizeof(buf));
    if (np == path)
        return addr;
    size_t nlen = strlen(np);
    if (nlen >= sizeof(out->sun_path))
        return addr;
    memset(out, 0, sizeof(*out));
    out->sun_family = AF_UNIX;
    memcpy(out->sun_path, np, nlen + 1);
    *len = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + nlen + 1);
    return (const struct sockaddr *) out;
}

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    RESOLVE_REAL(connect, int, (int, const struct sockaddr *, socklen_t));
    if (!real_fn)
        return -1;
    struct sockaddr_un un;
    const struct sockaddr *a = rewrite_sockaddr(addr, &addrlen, &un);
    return real_fn(sockfd, a, addrlen);
}

int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    RESOLVE_REAL(bind, int, (int, const struct sockaddr *, socklen_t));
    if (!real_fn)
        return -1;
    struct sockaddr_un un;
    const struct sockaddr *a = rewrite_sockaddr(addr, &addrlen, &un);
    return real_fn(sockfd, a, addrlen);
}
