#pragma once

// Signal analysis helpers for offline DSP tests (host only; these allocate freely).

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>
#include <vector>

namespace groove::test {

inline constexpr double kPiD = 3.14159265358979323846;

// In-place iterative radix-2 FFT. x.size() must be a power of two.
inline void fft(std::vector<std::complex<double>>& x) {
    const std::size_t n = x.size();
    for (std::size_t i = 1, j = 0; i < n; ++i) {
        std::size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(x[i], x[j]);
    }
    for (std::size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * kPiD / static_cast<double>(len);
        const std::complex<double> wl(std::cos(ang), std::sin(ang));
        for (std::size_t i = 0; i < n; i += len) {
            std::complex<double> w(1.0, 0.0);
            for (std::size_t k = 0; k < len / 2; ++k) {
                const std::complex<double> u = x[i + k];
                const std::complex<double> v = x[i + k + len / 2] * w;
                x[i + k] = u + v;
                x[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// Magnitude spectrum (bins 0..n/2) of the first n samples of x, Blackman-Harris window
// (sidelobes below -92 dB, main lobe +-4 bins).
inline std::vector<double> magnitudeSpectrum(const std::vector<float>& x, std::size_t offset, std::size_t n) {
    std::vector<std::complex<double>> buf(n);
    for (std::size_t i = 0; i < n; ++i) {
        const double p = 2.0 * kPiD * static_cast<double>(i) / static_cast<double>(n);
        const double w = 0.35875 - 0.48829 * std::cos(p) + 0.14128 * std::cos(2 * p) - 0.01168 * std::cos(3 * p);
        buf[i] = w * static_cast<double>(x[offset + i]);
    }
    fft(buf);
    std::vector<double> mag(n / 2 + 1);
    for (std::size_t i = 0; i <= n / 2; ++i) mag[i] = std::abs(buf[i]);
    return mag;
}

inline double toDb(double ratio) { return 20.0 * std::log10(std::max(ratio, 1e-300)); }

// Peak magnitude within +-halfWidth bins of a frequency.
inline double peakNear(const std::vector<double>& mag, double binHz, double hz, int halfWidth = 3) {
    const long centre = std::lround(hz / binHz);
    double p = 0.0;
    for (long b = centre - halfWidth; b <= centre + halfWidth; ++b) {
        if (b >= 0 && b < static_cast<long>(mag.size())) p = std::max(p, mag[static_cast<std::size_t>(b)]);
    }
    return p;
}

struct AliasReport {
    double worstDb = -300.0;  // strongest non-harmonic component relative to the fundamental
    double worstHz = 0.0;
};

// Strongest spectral component below maxHz that is not a harmonic of f0 (nor DC),
// relative to the fundamental.
inline AliasReport measureAliasing(const std::vector<float>& x, double fs, double f0, double maxHz = 20000.0) {
    const std::size_t n = 65536;
    const auto mag = magnitudeSpectrum(x, x.size() - n, n);
    const double binHz = fs / static_cast<double>(n);
    const double fundamental = peakNear(mag, binHz, f0);
    const double guardHz = 8.0 * binHz;
    AliasReport r;
    for (std::size_t b = 0; b < mag.size(); ++b) {
        const double hz = static_cast<double>(b) * binHz;
        if (hz > maxHz) break;
        if (hz < guardHz) continue;
        const double k = std::round(hz / f0);
        if (k >= 1.0 && std::fabs(hz - k * f0) < guardHz) continue;
        const double db = toDb(mag[b] / fundamental);
        if (db > r.worstDb) {
            r.worstDb = db;
            r.worstHz = hz;
        }
    }
    return r;
}

// Frequency from linearly interpolated rising zero crossings over [from, to).
inline double zeroCrossingHz(const std::vector<float>& x, double fs, std::size_t from = 0, std::size_t to = SIZE_MAX) {
    to = std::min(to, x.size());
    double first = -1.0, last = -1.0;
    long count = 0;
    for (std::size_t i = std::max<std::size_t>(from, 1); i < to; ++i) {
        const double a = x[i - 1], b = x[i];
        if (a < 0.0 && b >= 0.0) {
            const double t = static_cast<double>(i - 1) + a / (a - b);
            if (first < 0.0) first = t;
            last = t;
            ++count;
        }
    }
    if (count < 2) return 0.0;
    return static_cast<double>(count - 1) * fs / (last - first);
}

inline double cents(double hz, double referenceHz) { return 1200.0 * std::log2(hz / referenceHz); }

// Largest sample-to-sample step in [from, to): a click detector.
inline float maxStep(const std::vector<float>& x, std::size_t from = 1, std::size_t to = SIZE_MAX) {
    to = std::min(to, x.size());
    float m = 0.0f;
    for (std::size_t i = std::max<std::size_t>(from, 1); i < to; ++i) m = std::max(m, std::fabs(x[i] - x[i - 1]));
    return m;
}

inline float peakAbs(const std::vector<float>& x, std::size_t from = 0, std::size_t to = SIZE_MAX) {
    to = std::min(to, x.size());
    float p = 0.0f;
    for (std::size_t i = from; i < to; ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

inline double rms(const std::vector<float>& x, std::size_t from = 0, std::size_t to = SIZE_MAX) {
    to = std::min(to, x.size());
    if (to <= from) return 0.0;
    double s = 0.0;
    for (std::size_t i = from; i < to; ++i) s += static_cast<double>(x[i]) * x[i];
    return std::sqrt(s / static_cast<double>(to - from));
}

// Amplitude of the component at hz (Goertzel on a Hann-windowed segment), relative to a
// full-scale sine of the same frequency.
inline double goertzelAmplitude(const std::vector<float>& x, double fs, double hz, std::size_t from, std::size_t n) {
    const double w = 2.0 * kPiD * hz / fs;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0, wsum = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        const double win = 0.5 - 0.5 * std::cos(2.0 * kPiD * static_cast<double>(i) / static_cast<double>(n - 1));
        wsum += win;
        const double s0 = win * x[from + i] + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    const double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return 2.0 * std::sqrt(std::max(power, 0.0)) / wsum;
}

}  // namespace groove::test
