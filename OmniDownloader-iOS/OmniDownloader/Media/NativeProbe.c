#include "NativeProbe.h"
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/display.h>
#include <math.h>

/* Read actual container streams directly; no process stdout or global ffprobe state. */
OmniProbeResult omni_probe_local(const char *path) {
    OmniProbeResult result = {.status = -1};
    AVFormatContext *context = NULL;
    AVDictionary *options = NULL;
    av_dict_set(&options, "protocol_whitelist", "file,crypto,data", 0);
    int status = avformat_open_input(&context, path, NULL, &options);
    av_dict_free(&options);
    if (status < 0) return result;
    if (avformat_find_stream_info(context, NULL) < 0) goto end;
    if (context->duration > 0 && context->duration != AV_NOPTS_VALUE)
        result.duration_ms = context->duration / (AV_TIME_BASE / 1000);
    for (unsigned int i = 0; i < context->nb_streams && i < 1024; ++i) {
        AVStream *stream = context->streams[i];
        AVCodecParameters *codec = stream->codecpar;
        if (codec->codec_type == AVMEDIA_TYPE_AUDIO) result.audio = 1;
        if (codec->codec_type == AVMEDIA_TYPE_VIDEO && result.width == 0) {
            result.width = codec->width;
            result.height = codec->height;
            size_t size = 0;
            const uint8_t *matrix = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, &size);
            if (matrix && size >= 9 * sizeof(int32_t)) {
                double rotation = av_display_rotation_get((const int32_t *)matrix);
                if (isfinite(rotation) && labs(lround(rotation)) % 180 == 90) {
                    int swap = result.width; result.width = result.height; result.height = swap;
                }
            }
        }
        if (result.duration_ms <= 0 && stream->duration > 0 && stream->duration != AV_NOPTS_VALUE)
            result.duration_ms = (int64_t)(stream->duration * av_q2d(stream->time_base) * 1000);
    }
    result.status = 0;
end:
    avformat_close_input(&context);
    return result;
}
