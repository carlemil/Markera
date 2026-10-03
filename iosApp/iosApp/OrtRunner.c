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

static char last_error[512] = "";

const char* markera_ort_last_error(void) { return last_error; }

// Records and releases a failed status; returns 1 when there was one.
static int failed(const OrtApi* api, OrtStatus* status) {
    if (status == NULL) return 0;
    snprintf(last_error, sizeof last_error, "%s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 1;
}

static char* copy_name(const OrtApi* api, OrtAllocator* allocator, char* name) {
    char* copy = strdup(name);
    api->AllocatorFree(allocator, name);
    return copy;
}

MarkeraOrt* markera_ort_open(const char* model_path, int threads, int lean) {
    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api == NULL) { snprintf(last_error, sizeof last_error, "no ORT API %d", ORT_API_VERSION); return NULL; }
    MarkeraOrt* ort = calloc(1, sizeof *ort);
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
    if (options != NULL) api->ReleaseSessionOptions(options);
    // A failed open leaks the few small objects made so far; it happens at most once per launch.
    return ok ? ort : NULL;
}

long markera_ort_run(MarkeraOrt* ort, const float* input, int input_size, float** out) {
    const OrtApi* api = ort->api;
    const int64_t shape[4] = {1, 3, input_size, input_size};
    size_t bytes = (size_t)3 * input_size * input_size * sizeof(float);
    OrtValue* in = NULL;
    OrtValue* result = NULL;
    OrtTensorTypeAndShapeInfo* info = NULL;
    float* data = NULL;
    size_t count = 0;
    long written = -1;
    const char* input_names[1] = {ort->input_name};
    const char* output_names[1] = {ort->output_name};
    if (!failed(api, api->CreateTensorWithDataAsOrtValue(ort->cpu, (void*)input, bytes, shape, 4,
                                                         ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in))
        && !failed(api, api->Run(ort->session, NULL, input_names, (const OrtValue* const*)&in, 1,
                                 output_names, 1, &result))
        && !failed(api, api->GetTensorTypeAndShape(result, &info))
        && !failed(api, api->GetTensorShapeElementCount(info, &count))
        && !failed(api, api->GetTensorMutableData(result, (void**)&data))) {
        *out = malloc(count * sizeof(float) + 1);
        memcpy(*out, data, count * sizeof(float));
        written = (long)count;
    }
    if (info != NULL) api->ReleaseTensorTypeAndShapeInfo(info);
    if (result != NULL) api->ReleaseValue(result);
    if (in != NULL) api->ReleaseValue(in);
    return written;
}

void markera_ort_free(float* out) { free(out); }
