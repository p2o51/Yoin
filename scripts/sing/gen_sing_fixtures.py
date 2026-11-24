#!/usr/bin/env python3
"""
Reference fixtures for the Android Sing engine's unit tests (app/src/test/.../player/sing/).

Runs the Lyra-Sing-Lite reference maths (Prism lyra-sing-lite/infer_and_eval.py + the training
code's torch.stft/istft) on a synthetic clip and writes little-endian float32 arrays the Kotlin tests
read back. A synthetic clip, not a song excerpt: the fixtures are committed to a public repo.

Needs numpy, scipy, torch and onnxruntime (e.g. the vocal-sep-bench venv):
  python gen_sing_fixtures.py --out ../../app/src/test/resources/sing \
      [--model /path/lyra_sing_lite_fp32.onnx] [--clip some.wav --clip-out DIR]

Semantics (what the Kotlin side must match):
  * resample: scipy.signal.resample_poly (Kaiser beta 5, half length 10*max(up, down)), float64 maths.
  * STFT: torch.stft(n_fft=1024, hop=256, periodic Hann, center=True, pad_mode="reflect",
    normalized=False, onesided=True). iSTFT: torch.istft(..., center=True, length=n).
  * separation: bins 1..512, chunks of 256 frames, start every `step` frames (160), the last chunk
    pinned to the end, symmetric np.hanning(256) crossfade floored at 1e-4, per-chunk RMS
    normalisation sqrt(mean(x^2) + 1e-6), DC restored as 0, V back to the source rate,
    I = X - V on the same decoded samples.
"""
import argparse
from math import gcd
from pathlib import Path

import numpy as np
import torch
from scipy.signal import resample_poly

CORE_SR = 22050
N_FFT = 1024
HOP = 256
T_CHUNK = 256


def synth(n: int, sr: int) -> np.ndarray:
    """Deterministic stereo test signal; SingFixtures.synth in Kotlin computes the same samples."""
    t = np.arange(n, dtype=np.float64) / sr
    state = 12345
    noise = np.empty(n, dtype=np.float64)
    for i in range(n):
        state = (state * 1103515245 + 12345) % 2147483648
        noise[i] = state / 2147483648.0 - 0.5
    two_pi = 2.0 * np.pi
    left = (0.25 * np.sin(two_pi * 110.0 * t)
            + 0.15 * np.sin(two_pi * 440.0 * t) * (0.5 + 0.5 * np.sin(two_pi * 2.0 * t))
            + 0.2 * np.sin(two_pi * 600.0 * t + 3.0 * np.sin(two_pi * 5.0 * t))
            + 0.04 * noise)
    right = (0.25 * np.sin(two_pi * 110.0 * t + 0.7)
             + 0.1 * np.sin(two_pi * 1375.0 * t)
             + 0.2 * np.sin(two_pi * 600.0 * t + 3.0 * np.sin(two_pi * 5.0 * t))
             - 0.04 * noise)
    return np.stack([left, right], axis=1).astype(np.float32)


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return x.astype(np.float32)
    g = gcd(src, dst)
    return resample_poly(x, dst // g, src // g, axis=0).astype(np.float32)


def write(path: Path, a: np.ndarray):
    np.ascontiguousarray(a, dtype="<f4").tofile(path)
    print(f"  {path.name}: {a.shape} {path.stat().st_size} bytes")


def separate(mix: np.ndarray, sr: int, sess, step: int = 160):
    x22 = resample(mix, sr, CORE_SR)
    n22 = len(x22)
    win = torch.hann_window(N_FFT, periodic=True, dtype=torch.float32)
    spec = torch.stft(torch.from_numpy(np.ascontiguousarray(x22.T)), N_FFT, HOP, N_FFT, win,
                      center=True, pad_mode="reflect", normalized=False, onesided=True, return_complex=True).numpy()
    hi_l, hi_r = spec[0, 1:, :], spec[1, 1:, :]
    f_hi, t_tot = hi_l.shape
    acc = np.zeros((4, f_hi, t_tot), dtype=np.float32)
    wacc = np.zeros((t_tot,), dtype=np.float32)
    wt = np.maximum(np.hanning(T_CHUNK).astype(np.float32), 1e-4)
    starts = list(range(0, max(1, t_tot - T_CHUNK + 1), step))
    if starts[-1] + T_CHUNK < t_tot:
        starts.append(max(0, t_tot - T_CHUNK))
    for st in starts:
        sl_l, sl_r = hi_l[:, st:st + T_CHUNK], hi_r[:, st:st + T_CHUNK]
        pad = T_CHUNK - sl_l.shape[1]
        if pad > 0:
            sl_l = np.pad(sl_l, ((0, 0), (0, pad)))
            sl_r = np.pad(sl_r, ((0, 0), (0, pad)))
        x4 = np.stack([sl_l.real, sl_l.imag, sl_r.real, sl_r.imag], axis=0)[None].astype(np.float32)
        rms = float(np.sqrt(np.mean(x4 ** 2) + 1e-6))
        pred = sess.run(None, {"mix_spec": x4 / rms})[0][0] * rms
        valid = T_CHUNK - pad
        acc[:, :, st:st + valid] += pred[:, :, :valid] * wt[:valid]
        wacc[st:st + valid] += wt[:valid]
    acc /= np.maximum(wacc[None, None, :], 1e-8)
    zeros = np.zeros((1, t_tot), dtype=np.complex64)
    v_l = np.vstack([zeros, acc[0] + 1j * acc[1]])
    v_r = np.vstack([zeros, acc[2] + 1j * acc[3]])
    full = torch.from_numpy(np.stack([v_l, v_r]).astype(np.complex64))
    v22 = torch.istft(full, N_FFT, HOP, N_FFT, win, center=True, length=n22).numpy().T
    v_full = resample(v22, CORE_SR, sr)
    n_f = min(len(mix), len(v_full))
    v_full = v_full[:n_f]
    return mix[:n_f] - v_full, v_full, len(starts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--model")
    ap.add_argument("--clip", help="optional real clip (wav/flac) for a local, uncommitted parity run")
    ap.add_argument("--clip-out")
    ap.add_argument("--clip-start", type=float, default=0.0, help="seconds into --clip")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    # 1. Resampler: a 0.5 s synthetic clip through the four rates the engine meets.
    clip = synth(22050, 44100)  # 0.5 s at 44.1 kHz
    left = clip[:, 0]
    write(out / "clip_44k_stereo_planar.f32", clip.T)
    x22 = resample(clip, 44100, CORE_SR)
    write(out / "resample_44100_to_22050_planar.f32", x22.T)
    write(out / "resample_22050_to_44100_left.f32", resample(x22[:, 0:1], CORE_SR, 44100)[:, 0])
    write(out / "resample_48000_to_22050_left.f32", resample(left[:, None], 48000, CORE_SR)[:, 0])
    write(out / "resample_22050_to_48000_left.f32", resample(x22[:, 0:1], CORE_SR, 48000)[:, 0])

    # 2. STFT / iSTFT in float64 (the exact transform; Kotlin computes in double).
    win64 = torch.hann_window(N_FFT, periodic=True, dtype=torch.float64)
    sig = torch.from_numpy(x22[:, 0].astype(np.float64))
    spec = torch.stft(sig, N_FFT, HOP, N_FFT, win64, center=True, pad_mode="reflect",
                      normalized=False, onesided=True, return_complex=True)  # [513, T]
    st = spec.numpy().T  # [T, 513]
    write(out / "stft_left_re_im.f32", np.stack([st.real, st.imag], axis=-1))  # [T, 513, 2]
    # A deterministic "model-like" edit: mask, DC zeroed; Kotlin builds the same mask.
    f = np.arange(513)[:, None]
    t = np.arange(spec.shape[1])[None, :]
    mask = 0.5 + 0.4 * np.sin(0.01 * f + 0.1 * t)
    edited = spec.numpy() * mask
    edited[0, :] = 0
    y = torch.istft(torch.from_numpy(edited), N_FFT, HOP, N_FFT, win64, center=True, length=len(sig)).numpy()
    write(out / "istft_masked_left.f32", y)

    # 3. Full model, 3 s synthetic clip at 44.1 kHz; V subsampled every 4th frame to keep the file small.
    if args.model:
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        sess = ort.InferenceSession(args.model, sess_options=opts, providers=["CPUExecutionProvider"])
        mix3 = synth(3 * 44100, 44100)
        inst, voc, n_chunks = separate(mix3, 44100, sess)
        print(f"  model: {n_chunks} chunks, |V| rms {np.sqrt(np.mean(voc**2)):.4f}")
        write(out / "model_synth3s_vocal_planar_stride4.f32", voc[::4].T)
        if args.clip and args.clip_out:
            import soundfile as sf
            a, sr = sf.read(args.clip, always_2d=True, dtype="float32")
            s0 = int(args.clip_start * sr)
            a = a[s0: s0 + 3 * sr]
            a = a[:, :2] if a.shape[1] >= 2 else np.repeat(a, 2, axis=1)
            co = Path(args.clip_out)
            co.mkdir(parents=True, exist_ok=True)
            inst, voc, _ = separate(a, sr, sess)
            write(co / "clip_planar.f32", a.T)
            write(co / "vocal_planar.f32", voc.T)
            (co / "rate.txt").write_text(str(sr))


if __name__ == "__main__":
    main()
