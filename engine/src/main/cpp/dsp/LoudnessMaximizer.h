// Linked 4x polyphase true-peak limiter with a fixed, allocation-free lookahead.
#ifndef FUTURA_ENGINE_DSP_LOUDNESS_MAXIMIZER_H
#define FUTURA_ENGINE_DSP_LOUDNESS_MAXIMIZER_H

#include <array>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace futura::engine::dsp {

class LoudnessMaximizer {
public:
    static constexpr size_t MAX_CHANNELS = 8;
    static constexpr size_t MAX_LOOKAHEAD = 2048;
    static constexpr size_t DELAY_CAPACITY = MAX_LOOKAHEAD + 1;
    static constexpr size_t FIR_TAPS = 12;
    static constexpr size_t PEAK_QUEUE_CAPACITY = 4096;

    LoudnessMaximizer() noexcept { build_polyphase_kernel(); set_sample_rate(48000.0f); }

    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        size_t lookahead = static_cast<size_t>(std::lround(sample_rate * kLookaheadSeconds));
        if (lookahead > MAX_LOOKAHEAD) lookahead = MAX_LOOKAHEAD;
        lookahead_frames_.store(lookahead, std::memory_order_release);
        release_coeff_.store(std::exp(-1.0f / (sample_rate * kReleaseSeconds)),
                             std::memory_order_release);
    }
    void set_ceiling_db(float ceiling_db) noexcept {
        if (!std::isfinite(ceiling_db)) return;
        ceiling_db = clamp(ceiling_db, -6.0f, -0.1f);
        ceiling_.store(std::pow(10.0f, ceiling_db * 0.05f), std::memory_order_release);
    }
    void set_lookahead_ms(float milliseconds) noexcept {
        if (!std::isfinite(milliseconds)) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        const float ms = clamp(milliseconds, 1.0f, 20.0f);
        size_t samples = static_cast<size_t>(std::lround(sr * ms * 0.001f));
        if (samples > MAX_LOOKAHEAD) samples = MAX_LOOKAHEAD;
        lookahead_frames_.store(samples, std::memory_order_release);
    }

    void reset() noexcept {
        for (auto& channel : delay_) channel.fill(0.0f);
        peak_history_.fill(0.0f);
        queue_peaks_.fill(0.0f);
        queue_indices_.fill(0);
        queue_head_ = queue_tail_ = write_index_ = 0;
        frame_index_ = 0;
        gain_ = 1.0f;
    }

    // This safety stage is intentionally always active; there is no bypass.
    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1) return;
        const int active_channels = channels > static_cast<int>(MAX_CHANNELS)
                                  ? static_cast<int>(MAX_CHANNELS) : channels;
        const size_t lookahead = lookahead_frames_.load(std::memory_order_acquire);
        const float ceiling = ceiling_.load(std::memory_order_acquire);
        const float release = release_coeff_.load(std::memory_order_acquire);

        for (size_t frame = 0; frame < frames; ++frame) {
            float frame_peak = 0.0f;
            for (int ch = 0; ch < active_channels; ++ch) {
                float& sample = buffer[frame * static_cast<size_t>(channels) +
                                       static_cast<size_t>(ch)];
                if (!std::isfinite(sample)) sample = 0.0f;
                delay_[ch][write_index_] = sample;
                const float estimate = true_peak_for_channel(ch, sample);
                frame_peak = std::max(frame_peak, estimate);
            }
            peak_history_[frame_index_ % PEAK_QUEUE_CAPACITY] = frame_peak;

            // Maintain a monotonic max deque over the samples that can affect
            // the delayed output. The audio thread never allocates or waits.
            while (queue_head_ != queue_tail_) {
                const size_t last = (queue_tail_ + PEAK_QUEUE_CAPACITY - 1) %
                                    PEAK_QUEUE_CAPACITY;
                if (queue_peaks_[last] > frame_peak) break;
                queue_tail_ = last;
            }
            queue_peaks_[queue_tail_] = frame_peak;
            queue_indices_[queue_tail_] = frame_index_;
            queue_tail_ = (queue_tail_ + 1) % PEAK_QUEUE_CAPACITY;
            const std::uint64_t oldest = frame_index_ > lookahead
                ? frame_index_ - lookahead : 0;
            while (queue_head_ != queue_tail_ &&
                   queue_indices_[queue_head_] < oldest) {
                queue_head_ = (queue_head_ + 1) % PEAK_QUEUE_CAPACITY;
            }

            const float window_peak = queue_head_ == queue_tail_
                                    ? frame_peak : queue_peaks_[queue_head_];
            const float target_gain = window_peak > ceiling
                                    ? ceiling / window_peak : 1.0f;
            if (target_gain < gain_) gain_ = target_gain;  // lookahead makes attack immediate
            else gain_ = release * gain_ + (1.0f - release) * target_gain;

            const size_t read_index =
                (write_index_ + DELAY_CAPACITY - lookahead) % DELAY_CAPACITY;
            for (int ch = 0; ch < active_channels; ++ch) {
                float& out = buffer[frame * static_cast<size_t>(channels) +
                                    static_cast<size_t>(ch)];
                out = frame_index_ < lookahead ? 0.0f : delay_[ch][read_index] * gain_;
            }
            write_index_ = (write_index_ + 1) % DELAY_CAPACITY;
            ++frame_index_;
        }
    }

    float ceiling_linear() const noexcept { return ceiling_.load(std::memory_order_acquire); }
    size_t lookahead_frames() const noexcept {
        return lookahead_frames_.load(std::memory_order_acquire);
    }

private:
    static constexpr float kPi = 3.14159265358979323846f;
    static constexpr float kLookaheadSeconds = 0.005f;
    static constexpr float kReleaseSeconds = 0.050f;

    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }
    static float sinc(float x) noexcept {
        if (std::fabs(x) < 1e-6f) return 1.0f;
        const float v = kPi * x;
        return std::sin(v) / v;
    }

    void build_polyphase_kernel() noexcept {
        // Causal 12-tap windowed-sinc fractional-delay bank. Four phases
        // estimate the reconstructed waveform between stored samples.
        constexpr float group_delay = static_cast<float>(FIR_TAPS - 1) * 0.5f;
        for (size_t phase = 0; phase < 4; ++phase) {
            const float fraction = static_cast<float>(phase) * 0.25f;
            float sum = 0.0f;
            for (size_t tap = 0; tap < FIR_TAPS; ++tap) {
                const float distance = static_cast<float>(tap) - group_delay - fraction;
                const float window = 0.42f - 0.5f * std::cos(
                    2.0f * kPi * static_cast<float>(tap) / static_cast<float>(FIR_TAPS - 1)) +
                    0.08f * std::cos(
                    4.0f * kPi * static_cast<float>(tap) / static_cast<float>(FIR_TAPS - 1));
                kernel_[phase][tap] = sinc(distance) * window;
                sum += kernel_[phase][tap];
            }
            if (std::fabs(sum) > 1e-8f) {
                for (float& coefficient : kernel_[phase]) coefficient /= sum;
            }
        }
    }

    float true_peak_for_channel(int channel, float current) const noexcept {
        float peak = std::fabs(current);
        for (size_t phase = 1; phase < 4; ++phase) {
            double interpolated = 0.0;
            size_t read = write_index_;
            for (size_t tap = 0; tap < FIR_TAPS; ++tap) {
                if (tap > 0) read = read == 0 ? DELAY_CAPACITY - 1 : read - 1;
                const float value = tap == 0 ? current : delay_[channel][read];
                interpolated += static_cast<double>(kernel_[phase][tap]) * value;
            }
            peak = std::max(peak, static_cast<float>(std::fabs(interpolated)));
        }
        return peak;
    }

    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> ceiling_{0.9660509f}; // -0.3 dBFS
    std::atomic<float> release_coeff_{0.9995834f};
    std::atomic<size_t> lookahead_frames_{240};
    std::array<std::array<float, DELAY_CAPACITY>, MAX_CHANNELS> delay_{};
    std::array<std::array<float, FIR_TAPS>, 4> kernel_{};
    std::array<float, PEAK_QUEUE_CAPACITY> peak_history_{};
    std::array<float, PEAK_QUEUE_CAPACITY> queue_peaks_{};
    std::array<std::uint64_t, PEAK_QUEUE_CAPACITY> queue_indices_{};
    size_t write_index_ = 0, queue_head_ = 0, queue_tail_ = 0;
    std::uint64_t frame_index_ = 0;
    float gain_ = 1.0f;
};

}  // namespace futura::engine::dsp
#endif
