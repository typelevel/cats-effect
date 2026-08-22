#ifdef CATS_EFFECT_SIGNAL_HELPER

// we'll need POSIX for `sigaction`:
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 1
#endif

#include <stddef.h>
#include <signal.h>

typedef void (*Handler)(int);

int cats_effect_install_handler(int signum, Handler handler) {
    int error;
    struct sigaction action;
    action.sa_handler = handler;
    action.sa_flags = 0;
    error = sigemptyset(&action.sa_mask);
    if (error != 0) {
        return error;
    }
    error = sigaddset(&action.sa_mask, 13); // mask SIGPIPE
    if (error != 0) {
        return error;
    }
    return sigaction(signum, &action, NULL);
}

#endif
