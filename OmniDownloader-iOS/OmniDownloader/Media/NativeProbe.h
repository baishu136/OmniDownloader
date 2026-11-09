#include <stdint.h>
typedef struct {
    int status;
    int width;
    int height;
    int audio;
    int64_t duration_ms;
} OmniProbeResult;
OmniProbeResult omni_probe_local(const char *path);
