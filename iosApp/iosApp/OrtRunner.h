#ifndef MARKERA_ORT_RUNNER_H
#define MARKERA_ORT_RUNNER_H

/// A single-input, single-output fp32 ONNX Runtime session through the C API.
/// The Objective-C wrapper cannot switch off ORT's CPU arena or its memory
/// pattern, and with both on the app's footprint grows past what iOS allows.
typedef struct MarkeraOrt MarkeraOrt;

/// NULL on failure (see markera_ort_last_error). `lean` turns the arena and the
/// memory pattern off: tensors are malloc'ed and freed as the graph runs.
MarkeraOrt* markera_ort_open(const char* model_path, int threads, int lean);

/// Runs `[1, 3, input_size, input_size]` (not copied) and returns the output's
/// float count, or -1. `*out` is malloc'ed; release it with markera_ort_free.
long markera_ort_run(MarkeraOrt* ort, const float* input, int input_size, float** out);

void markera_ort_free(float* out);
const char* markera_ort_last_error(void);

#endif
