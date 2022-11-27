#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <dirent.h>
#include <ctime>
#include <cstring>
#include <libgen.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <cerrno>
#include <string_view>
#include <termios.h>
#include <android/log.h>
#include <cctype>

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7

#define SERVER_NAME "shelljava"
#define SERVER_CLASS_PATH "com.mordred.shelljava.Server"

#if defined(__arm__)
#define ABI "armeabi-v7a"
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__aarch64__)
#define ABI "arm64-v8a"
#endif

static void run_server(const char *dex_path, const char *main_class, const char *process_name, const char *server_key, const char *server_port) {
    if (setenv("CLASSPATH", dex_path, true)) {
        printf("E: can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

#define ARG(v) char **v = nullptr; \
    char buf_##v[PATH_MAX]; \
    size_t v_size = 0; \
    uintptr_t v_current = 0;
#define ARG_PUSH(v, arg) v_size += sizeof(char *); \
if (v == nullptr) { \
    v = (char **) malloc(v_size); \
} else { \
    v = (char **) realloc(v, v_size);\
} \
v_current = (uintptr_t) v + v_size - sizeof(char *); \
*((char **) v_current) = arg ? strdup(arg) : nullptr;

#define ARG_END(v) ARG_PUSH(v, nullptr)

#define ARG_PUSH_FMT(v, fmt, ...) snprintf(buf_##v, PATH_MAX, fmt, __VA_ARGS__); \
    ARG_PUSH(v, buf_##v)

    char lib_path[PATH_MAX]{0};
    snprintf(lib_path, PATH_MAX, "%s!/lib/%s", dex_path, ABI);

    ARG(argv)
    ARG_PUSH(argv, "/system/bin/app_process")
    ARG_PUSH_FMT(argv, "-Djava.class.path=%s", dex_path)
    ARG_PUSH(argv, "/system/bin")
    ARG_PUSH_FMT(argv, "--nice-name=%s", process_name)
    ARG_PUSH(argv, main_class)
    ARG_PUSH(argv, server_key)
    ARG_PUSH(argv, server_port)
    ARG_END(argv)

    printf("D: exec app_process");

    if (execvp((const char *) argv[0], argv)) {
        exit(EXIT_FATAL_APP_PROCESS);
    }
}

static void start_server(const char *path, const char *main_class, const char *process_name,
                         const char *serv_key, const char *serv_port) {
    if (daemon(false, false) == 0) {
        printf("D: child");
        run_server(path, main_class, process_name, serv_key, serv_port);
    } else {
        printf("fatal: can't fork\n");
        exit(EXIT_FATAL_FORK);
    }
}

int main(int argc, char **argv) {
    char *apk_path = nullptr;
    char *server_key = nullptr;
    char *server_port = nullptr;
    for (int i = 0; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
        } else if (strncmp(argv[i], "--key=", 6) == 0) {
            server_key = argv[i] + 6;
        } else if (strncmp(argv[i], "--port=", 7) == 0) {
            server_port = argv[i] + 7;
        }
    }

    int uid = getuid();
    if (uid != 2000) {
        printf("fatal: run shelljava from non adb user (uid=%d).\n", uid);
        exit(EXIT_FATAL_UID);
    }

    mkdir("/data/local/tmp/shelljava", 0707);
    chmod("/data/local/tmp/shelljava", 0707);

    printf("info: starter begin\n");
    fflush(stdout);

    if (access(apk_path, R_OK) == 0) {
        printf("info: use apk path from argv\n");
        fflush(stdout);
    }

    if (!apk_path) {
        printf("fatal: can't get path of manager\n");
        exit(EXIT_FATAL_PM_PATH);
    }

    printf("info: apk path is %s\n", apk_path);
    if (access(apk_path, R_OK) != 0) {
        printf("fatal: can't access manager %s\n", apk_path);
        exit(EXIT_FATAL_PM_PATH);
    }

    printf("info: starting server...\n");
    fflush(stdout);
    printf("D: start_server");
    start_server(apk_path, SERVER_CLASS_PATH, SERVER_NAME, server_key, server_port);
    exit(EXIT_SUCCESS);
}