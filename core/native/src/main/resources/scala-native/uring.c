#include <liburing.h>

struct io_uring_sqe *ce_io_uring_get_sqe(struct io_uring *ring) {
  return io_uring_get_sqe(ring);
}

void ce_io_uring_cq_advance(struct io_uring *ring, unsigned nr) {
  io_uring_cq_advance(ring, nr);
}

void ce_io_uring_prep_cancel64(struct io_uring_sqe *sqe, __u64 user_data,
                               int flags) {
  io_uring_prep_cancel64(sqe, user_data, flags);
}

void ce_io_uring_prep_poll_add(struct io_uring_sqe *sqe, int fd,
                               unsigned int pollmask) {
  io_uring_prep_poll_add(sqe, fd, pollmask);
}
