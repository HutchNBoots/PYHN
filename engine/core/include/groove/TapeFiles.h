#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace groove::tapefile {

// On-disk format of the saved tape (docs/feature-spec.md F7.5, F7.6). All integers are
// little-endian.
//
// tape-meta.bin (kMetaBytes, replaced atomically: written to tape-meta.tmp, fsynced, renamed,
// then the directory is fsynced):
//    0  char[8] magic "GRVTAPE\0"
//    8  u32 version (kVersion)
//   12  u32 header size in bytes (kMetaBytes)
//   16  u32 sample rate (frames per second)
//   20  u16 channels (2), u16 bits per sample (16)
//   24  u32 tracks (4)
//   28  u32 hasAudio mask (bit t = track t has audio)
//   32  i64 tape length in frames (whole bars; 0 = empty tape)
//   40  i64 frames per bar
//   48  i64 preroll: file frame 0 holds tape frame -preroll
//   56  i64 frames per track file (every track file is exactly this many frames long)
//   64  u64 sequence (the tape's change counter when this state was taken)
//   72  u32 reserved (0)
//   76  u32 CRC-32 (IEEE) of bytes 0..75
//
// tape-track1.pcm .. tape-track4.pcm: raw interleaved int16 stereo frames, the tape's own
// storage (full scale = Tape::kHeadroom), fileFrames frames each.
inline constexpr uint32_t kVersion = 1;
inline constexpr std::size_t kMetaBytes = 80;
inline constexpr int kTracks = 4;
inline constexpr int kBytesPerFrame = 4;
inline constexpr char kMetaName[] = "tape-meta.bin";
inline constexpr char kMetaTempName[] = "tape-meta.tmp";

std::string trackName(int track);  // "tape-track1.pcm" for track 0
std::string join(const std::string& dir, const std::string& name);

struct Meta {
    uint32_t sampleRate = 0;
    uint32_t hasAudioMask = 0;
    int64_t lengthFrames = 0;
    int64_t barFrames = 0;
    int64_t prerollFrames = 0;
    int64_t fileFrames = 0;
    uint64_t sequence = 0;
};

enum class DecodeResult { Ok, BadSize, BadMagic, BadVersion, BadCrc, BadFormat };

uint32_t crc32(const uint8_t* data, std::size_t size);
void encode(const Meta& meta, uint8_t* out);  // out: kMetaBytes
DecodeResult decode(const uint8_t* data, std::size_t size, Meta& out);

// --- POSIX file helpers (never used on the audio thread) ---
// Reads a whole small file (at most maxBytes); false if missing, unreadable or larger.
bool readSmallFile(const std::string& path, std::size_t maxBytes, std::vector<uint8_t>& out);
// -1 if missing.
int64_t fileSize(const std::string& path);
bool isDirectory(const std::string& path);
// Writes data to dir/tempName, fsyncs it, renames it over dir/name and fsyncs dir.
bool writeAtomically(const std::string& dir, const char* name, const char* tempName, const uint8_t* data,
                     std::size_t size);
// Reads exactly size bytes at offset; false on any error or short read.
bool preadExact(int fd, void* out, std::size_t size, int64_t offset);
bool pwriteExact(int fd, const void* data, std::size_t size, int64_t offset);

// --- WAV (F7.2): 24-bit PCM, little-endian, canonical 44-byte header ---
inline constexpr std::size_t kWavHeaderBytes = 44;
void wavHeader(uint32_t sampleRate, uint16_t channels, uint16_t bits, int64_t frames, uint8_t* out);
// Rounds to the nearest 24-bit code, clamped; NaN is 0.
int32_t toPcm24(float x);

}  // namespace groove::tapefile
