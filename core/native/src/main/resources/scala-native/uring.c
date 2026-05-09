#ifdef CATS_EFFECT_URING

#include <liburing.h>

struct io_uring_sqe *ce_io_uring_get_sqe(struct io_uring *ring) {
  return io_uring_get_sqe(ring);
}

void ce_io_uring_cq_advance(struct io_uring *ring, unsigned nr) {
  io_uring_cq_advance(ring, nr);
}

#endif
