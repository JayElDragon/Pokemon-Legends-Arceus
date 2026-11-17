// hwopus: the Switch's Opus decoder service (IHardwareOpusDecoderManager / IHardwareOpusDecoder). Pokémon Legends:
// Arceus streams its music as Opus and hands every packet to this service; with only the logging stub nothing came
// back and the music was silent (sound effects take another path). Decoded here with libopus (third_party/opus, BSD).
// Built where the build defines HAVE_OPUS (android/CMakeLists.txt); elsewhere the stub stays.
//
// Packets come with the console's 8-byte header: the packet's size and its final range, both big-endian. Each decode
// call takes one packet and writes interleaved signed 16-bit samples. The work buffer the game passes (transfer
// memory) isn't needed: libopus keeps its own state.
#ifdef HAVE_OPUS
#include <stdlib.h>
#include <string.h>

#include <opus.h>
#include <opus_multistream.h>

#include "../hle.h"
#include "../ipc.h"

typedef struct {
    OpusDecoder *dec;              // one stream
    OpusMSDecoder *ms;             // multistream
    int channels, rate;
} OpusState;

#define RES_OK 0
#define RES_BAD_PACKET 0x20C11  // (hwopus's "invalid packet"-class result, module 111)

static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]; }

static void opus_on_close(Service *s) {
    OpusState *o = s->state;
    if (!o) return;
    if (o->dec) opus_decoder_destroy(o->dec);
    if (o->ms) opus_multistream_decoder_destroy(o->ms);
    free(o);
    s->state = NULL;
}

// Decode one packet from the input buffer into the output buffer: consumed bytes and samples (per channel) out,
// plus the "performance" time (0) for the WithPerf variants.
static void decode(OpusState *o, IpcReq *req, IpcResp *resp, bool perf, bool reset) {
    IpcBuf *in = in_buf(req, 0), *out = out_buf(req, 0);
    if (reset) {
        if (o->dec) opus_decoder_ctl(o->dec, OPUS_RESET_STATE);
        if (o->ms) opus_multistream_decoder_ctl(o->ms, OPUS_RESET_STATE);
    }
    if (!in || !out || in->size < 8 || !out->size) { resp->result = RES_BAD_PACKET; return; }
    const uint8_t *src = (const uint8_t *)(uintptr_t)in->addr;
    uint32_t size = be32(src);
    if (size > in->size - 8) { resp->result = RES_BAD_PACKET; return; }
    int16_t *pcm = (int16_t *)(uintptr_t)out->addr;
    int max_frames = (int)(out->size / (sizeof(int16_t) * (o->channels ? o->channels : 1)));
    int n = o->ms ? opus_multistream_decode(o->ms, src + 8, (opus_int32)size, pcm, max_frames, 0)
                  : opus_decode(o->dec, src + 8, (opus_int32)size, pcm, max_frames, 0);
    if (n < 0) {
        static int nlog;
        if (nlog++ < 8) LOG("[hwopus] decode failed: %s (packet %u bytes)\n", opus_strerror(n), size);
        resp->result = RES_BAD_PACKET;
        return;
    }
    OUT(uint32_t, 0) = size + 8;  // consumed
    OUT(uint32_t, 4) = (uint32_t)n;  // samples per channel
    if (perf) OUT(uint64_t, 8) = 0;
    resp_size(resp, perf ? 16 : 8);
    resp->result = RES_OK;
}

static void opus_decoder_svc(Service *s, IpcReq *req, IpcResp *resp) {
    OpusState *o = s->state;
    switch (req->cmd) {
    case 0: case 2: decode(o, req, resp, false, false); break;          // DecodeInterleavedOld (+ForMultiStream)
    case 1: case 3: resp->result = RES_OK; break;                       // SetContext (+ForMultiStream): no state kept
    case 4: case 5: decode(o, req, resp, true, false); break;           // ...WithPerfOld
    case 6: case 7: decode(o, req, resp, true, IN(uint8_t, 0) != 0); break;  // ...WithPerfAndResetOld
    case 8: case 9: decode(o, req, resp, true, IN(uint8_t, 0) != 0); break;  // DecodeInterleaved (12.0+)
    default: LOG("[hwopus] decoder cmd %u (ok)\n", req->cmd); resp->result = RES_OK;
    }
}

static Service *open_single(int rate, int channels) {
    int err = 0;
    OpusState *o = calloc(1, sizeof *o);
    o->rate = rate, o->channels = channels;
    o->dec = opus_decoder_create(rate, channels, &err);
    if (!o->dec) { LOG("[hwopus] decoder %d Hz x%d: %s\n", rate, channels, opus_strerror(err)); free(o); return NULL; }
    LOG("[hwopus] decoder %d Hz, %d channel(s)\n", rate, channels);
    Service *sv = service_new("IHardwareOpusDecoder", opus_decoder_svc, o);
    sv->on_close = opus_on_close;
    return sv;
}

// multistream parameters (an input buffer): rate, channels, streams, coupled (stereo) streams, the channel mapping
static Service *open_multi(const uint8_t *p) {
    int rate = *(const int32_t *)p, channels = *(const int32_t *)(p + 4);
    int streams = *(const int32_t *)(p + 8), coupled = *(const int32_t *)(p + 12);
    int err = 0;
    OpusState *o = calloc(1, sizeof *o);
    o->rate = rate, o->channels = channels;
    o->ms = opus_multistream_decoder_create(rate, channels, streams, coupled, p + 16, &err);
    if (!o->ms) { LOG("[hwopus] multistream %d Hz x%d: %s\n", rate, channels, opus_strerror(err)); free(o); return NULL; }
    LOG("[hwopus] multistream decoder %d Hz, %d channels, %d streams (%d stereo)\n", rate, channels, streams, coupled);
    Service *sv = service_new("IHardwareOpusDecoder", opus_decoder_svc, o);
    sv->on_close = opus_on_close;
    return sv;
}

// the work buffer the console's decoder wants: the game allocates it and passes it back; libopus doesn't use it, but
// a size of zero made the game give up before opening a decoder
static uint32_t work_size(int channels) { return 0x4000 + (uint32_t)opus_decoder_get_size(channels > 0 ? channels : 2) * 2; }

SERVICE("hwopus", hwopus) {
    (void)s;
    switch (req->cmd) {
    case 0: {  // OpenHardwareOpusDecoder(rate, channels, work size; transfer memory)
        Service *d = open_single(IN(int32_t, 0), IN(int32_t, 4));
        if (d) ipc_return_object(resp, d);
        else resp->result = RES_BAD_PACKET;
        break;
    }
    case 1: OUT(uint32_t, 0) = work_size(IN(int32_t, 4)); resp_size(resp, 4); break;  // GetWorkBufferSize(rate, channels)
    case 2: {  // OpenHardwareOpusDecoderForMultiStream(parameters buffer; transfer memory)
        IpcBuf *b = in_buf(req, 0);
        Service *d = b && b->size >= 16 ? open_multi((const uint8_t *)(uintptr_t)b->addr) : NULL;
        if (d) ipc_return_object(resp, d);
        else resp->result = RES_BAD_PACKET;
        break;
    }
    case 3: {  // GetWorkBufferSizeForMultiStream(parameters buffer)
        IpcBuf *b = in_buf(req, 0);
        int ch = b && b->size >= 8 ? *(const int32_t *)(uintptr_t)(b->addr + 4) : 2;
        OUT(uint32_t, 0) = work_size(ch) * 2;
        resp_size(resp, 4);
        break;
    }
    case 4: {  // OpenHardwareOpusDecoderEx(parameters {rate, channels, flags...}; transfer memory) - 12.0+
        Service *d = open_single(IN(int32_t, 0), IN(int32_t, 4));
        if (d) ipc_return_object(resp, d);
        else resp->result = RES_BAD_PACKET;
        break;
    }
    case 5: OUT(uint32_t, 0) = work_size(IN(int32_t, 4)); resp_size(resp, 4); break;  // GetWorkBufferSizeEx(parameters)
    case 6: {  // OpenHardwareOpusDecoderForMultiStreamEx(parameters buffer; transfer memory)
        IpcBuf *b = in_buf(req, 0);
        Service *d = b && b->size >= 16 ? open_multi((const uint8_t *)(uintptr_t)b->addr) : NULL;
        if (d) ipc_return_object(resp, d);
        else resp->result = RES_BAD_PACKET;
        break;
    }
    case 7: case 8: case 9: {  // GetWorkBufferSize...Ex / ExEx: a size large enough for any of them
        OUT(uint64_t, 0) = work_size(8) * 4;
        resp_size(resp, 8);
        break;
    }
    default: LOG("[hwopus] manager cmd %u (ok)\n", req->cmd); resp->result = RES_OK;
    }
}
#endif
