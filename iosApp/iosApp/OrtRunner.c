#include "OrtRunner.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <onnxruntime/onnxruntime_c_api.h>

struct MarkeraOrt {
    const OrtApi* api;
    OrtEnv* env;
    OrtSession* session;
    OrtMemoryInfo* cpu;
    char* input_name;
    char* output_name;
};

// Per thread, so a caller reads the message of its own failed call.
static _Thread_local char last_error[512] = "";

const char* markera_ort_last_error(void) { return last_error; }

// Records and releases a failed status; returns 1 when there was one.
static int failed(const OrtApi* api, OrtStatus* status) {
    if (status == NULL) return 0;
    snprintf(last_error, sizeof last_error, "%s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 1;
}

static int out_of_memory(const char* what) {
    snprintf(last_error, sizeof last_error, "out of memory: %s", what);
    return 1;
}

// Copies an ORT-allocated name into malloc'ed memory and frees the original.
// NULL on failure, with last_error set.
static char* copy_name(const OrtApi* api, OrtAllocator* allocator, char* name) {
    char* copy = strdup(name);
    int freed = !failed(api, api->AllocatorFree(allocator, name));
    if (copy == NULL) { out_of_memory("model input/output name"); return NULL; }
    if (!freed) { free(copy); return NULL; }
    return copy;
}

void markera_ort_close(MarkeraOrt* ort) {
    if (ort == NULL) return;
    const OrtApi* api = ort->api;
    if (api != NULL) {
        if (ort->session != NULL) api->ReleaseSession(ort->session);
        if (ort->cpu != NULL) api->ReleaseMemoryInfo(ort->cpu);
        if (ort->env != NULL) api->ReleaseEnv(ort->env);
    }
    free(ort->input_name);
    free(ort->output_name);
    free(ort);
}

MarkeraOrt* markera_ort_open(const char* model_path, int threads, int lean) {
    if (model_path == NULL) { snprintf(last_error, sizeof last_error, "no model path"); return NULL; }
    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api == NULL) { snprintf(last_error, sizeof last_error, "no ORT API %d", ORT_API_VERSION); return NULL; }
    MarkeraOrt* ort = calloc(1, sizeof *ort);
    if (ort == NULL) { out_of_memory("session handle"); return NULL; }
    ort->api = api;
    OrtSessionOptions* options = NULL;
    OrtAllocator* allocator = NULL;
    char* name = NULL;
    int ok = !failed(api, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "markera", &ort->env))
        && !failed(api, api->CreateSessionOptions(&options))
        && !failed(api, api->SetIntraOpNumThreads(options, threads))
        && (!lean || (!failed(api, api->DisableCpuMemArena(options))
                      && !failed(api, api->DisableMemPattern(options))))
        && !failed(api, api->CreateSession(ort->env, model_path, options, &ort->session))
        && !failed(api, api->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &ort->cpu))
        && !failed(api, api->GetAllocatorWithDefaultOptions(&allocator))
        && !failed(api, api->SessionGetInputName(ort->session, 0, allocator, &name))
        && (ort->input_name = copy_name(api, allocator, name)) != NULL
        && !failed(api, api->SessionGetOutputName(ort->session, 0, allocator, &name))
        && (ort->output_name = copy_name(api, allocator, name)) != NULL;
    // The session keeps what it needs of the options; they are not used again.
    if (options != NULL) api->ReleaseSessionOptions(options);
    if (!ok) {
        markera_ort_close(ort);
        return NULL;
    }
    return ort;
}

long markera_ort_run(MarkeraOrt* ort, const float* input, long input_floats, int input_size, float** out) {
    if (out == NULL) { snprintf(last_error, sizeof last_error, "no output pointer"); return -1; }
    *out = NULL;
    if (ort == NULL) { snprintf(last_error, sizeof last_error, "session closed"); return -1; }
    if (input == NULL || input_size <= 0
        || input_floats != (long)3 * input_size * input_size) {
        snprintf(last_error, sizeof last_error, "input is %ld floats, want 3x%dx%d", input_floats,
                 input_size, input_size);
        return -1;
    }
    const OrtApi* api = ort->api;
    const int64_t shape[4] = {1, 3, input_size, input_size};
    size_t bytes = (size_t)input_floats * sizeof(float);
    OrtValue* in = NULL;
    OrtValue* result = NULL;
    OrtTensorTypeAndShapeInfo* info = NULL;
    float* data = NULL;
    size_t count = 0;
    long written = -1;
    const char* input_names[1] = {ort->input_name};
    const char* output_names[1] = {ort->output_name};
    // The tensor wraps `input` without copying it; the caller keeps it alive (pinned) until we return.
    if (!failed(api, api->CreateTensorWithDataAsOrtValue(ort->cpu, (void*)input, bytes, shape, 4,
                                                         ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in))
        && !failed(api, api->Run(ort->session, NULL, input_names, (const OrtValue* const*)&in, 1,
                                 output_names, 1, &result))
        && !failed(api, api->GetTensorTypeAndShape(result, &info))
        && !failed(api, api->GetTensorShapeElementCount(info, &count))
        && !failed(api, api->GetTensorMutableData(result, (void**)&data))) {
        float* copy = malloc(count * sizeof(float) + 1);
        if (copy == NULL) {
            out_of_memory("model output");
        } else if (count > 0 && data == NULL) {
            free(copy);
            snprintf(last_error, sizeof last_error, "model output has no data");
        } else {
            if (count > 0) memcpy(copy, data, count * sizeof(float));
            *out = copy;
            written = (long)count;
        }
    }
    if (info != NULL) api->ReleaseTensorTypeAndShapeInfo(info);
    if (result != NULL) api->ReleaseValue(result);
    if (in != NULL) api->ReleaseValue(in);
    return written;
}

void markera_ort_free(float* out) { free(out); }
