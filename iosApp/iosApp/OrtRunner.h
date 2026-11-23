#ifndef MARKERA_ORT_RUNNER_H
#define MARKERA_ORT_RUNNER_H

/// A single-input, single-output fp32 ONNX Runtime session through the C API.
/// The Objective-C wrapper cannot switch off ORT's CPU arena or its memory
/// pattern, and with both on the app's footprint grows past what iOS allows.
///
/// Not thread-safe: one caller at a time per session (OrtHoleModel serialises
/// open, run and close on one queue). Errors are reported per thread.
typedef struct MarkeraOrt MarkeraOrt;

/// NULL on failure (see markera_ort_last_error); a failed open frees what it made.
/// `lean` turns the arena and the memory pattern off: tensors are malloc'ed and
/// freed as the graph runs.
MarkeraOrt* markera_ort_open(const char* model_path, int threads, int lean);

/// Runs `[1, 3, input_size, input_size]` (`input_floats` must equal 3 * input_size²;
/// the buffer is not copied and must stay valid until this returns) and returns the
/// output's float count, or -1 with `*out` NULL (an ORT error or a failed allocation,
/// see markera_ort_last_error). On success `*out` is malloc'ed; release it with
/// markera_ort_free.
long markera_ort_run(MarkeraOrt* ort, const float* input, long input_floats, int input_size, float** out);

void markera_ort_free(float* out);

/// Releases the session, its memory info, the env and the handle itself. NULL is a
/// no-op. Must not overlap a markera_ort_run on the same handle (a native run cannot
/// be aborted); the handle is invalid afterwards.
void markera_ort_close(MarkeraOrt* ort);

/// The last error on the calling thread.
const char* markera_ort_last_error(void);

#endif
