"""Export SpeechBrain's ECAPA speaker encoder (spkrec-ecapa-voxceleb) to ONNX, front end included.

usage: python export_ecapa.py <ecapa dir> <out.onnx> [--frontend stft|dft] [--opset 17] [--int8 <out_int8.onnx>]

--int8 also writes a dynamically quantised copy (ECAPA's convolutions in 8 bits, the front end in float). Its vectors
are measurably off (cosine to SpeechBrain about 0.995, down to about 0.95 on some speech), so the float model is the
one to ship.

<ecapa dir> is a download of huggingface.co/speechbrain/spkrec-ecapa-voxceleb (hyperparams.yaml,
embedding_model.ckpt, ...), revision 0f99f2d0ebe89ac095bcc5903c4dd8f72b367286. Tested with torch 2.7.1 (CPU),
speechbrain 1.1.2, onnx 1.23 and onnxruntime 1.22.0.

The ONNX model takes `waveform` [1, N] (float32, 16 kHz mono, N dynamic) and gives `embedding` [1, 192], equal to
`EncoderClassifier.encode_batch(wav)[0]` for a single full-length waveform: SpeechBrain's Fbank (25 ms Hamming window,
n_fft 400, hop 10 ms, centred with zero padding, 80 triangular mel bands, 10*log10 with top_db 80 over the whole
sequence), the per-sentence mean normalisation, then ECAPA-TDNN. The vector is not normalised; L2-normalise it before
use.

--frontend stft uses torch.stft (the ONNX STFT op, opset >= 17). --frontend dft (the default) computes the same
transform as a strided Conv1d with constant cos/sin weights, so the graph has only plain ops.
After export the script compares ONNX Runtime with SpeechBrain on generated audio and prints the cosines.
"""
import argparse
import math
import tempfile

import numpy as np
import torch
import torch.nn.functional as F

SR = 16000


def load_classifier(ecapa_dir, tmp):
    # SpeechBrain resolves the checkpoints against savedir, so load from a private copy of the folder.
    import os
    import shutil
    from speechbrain.inference.speaker import EncoderClassifier
    d = os.path.join(tmp, 'ecapa')
    shutil.copytree(ecapa_dir, d)
    return EncoderClassifier.from_hparams(source=d, savedir=d, run_opts={'device': 'cpu'})


def fbank_matrix(fbank_module):
    """The [201, 80] mel matrix SpeechBrain's Filterbank multiplies the power spectrum by."""
    log_mel = fbank_module.log_mel
    fbank_module.log_mel = False
    try:
        with torch.no_grad():
            m = fbank_module(torch.eye(fbank_module.n_stft)[None])[0]
    finally:
        fbank_module.log_mel = log_mel
    return m


def se_forward(self, x, lengths=None):
    # SEBlock with a full-length single input: the masked mean is the plain mean.
    s = x.mean(dim=2, keepdim=True)
    s = self.relu(self.conv1(s))
    s = self.sigmoid(self.conv2(s))
    return s * x


def asp_forward(self, x, lengths=None):
    # AttentiveStatisticsPooling with an all-ones mask, written without the mask.
    eps = self.eps

    def stats(x, w):
        mean = (w * x).sum(2)
        std = torch.sqrt((w * (x - mean.unsqueeze(2)).pow(2)).sum(2).clamp(eps))
        return mean, std

    if self.global_context:
        mean = x.mean(dim=2, keepdim=True)
        std = torch.sqrt((x - mean).pow(2).mean(dim=2, keepdim=True).clamp(eps))
        attn = torch.cat([x, mean.expand_as(x), std.expand_as(x)], dim=1)
    else:
        attn = x
    attn = self.conv(self.tanh(self.tdnn(attn)))
    attn = F.softmax(attn, dim=2)
    mean, std = stats(x, attn)
    return torch.cat((mean, std), dim=1).unsqueeze(2)


class EcapaWaveform(torch.nn.Module):
    def __init__(self, clf, frontend):
        super().__init__()
        stft = clf.mods.compute_features.compute_STFT
        fb = clf.mods.compute_features.compute_fbanks
        mvn = clf.mods.mean_var_norm
        assert stft.center and stft.pad_mode == 'constant' and not stft.normalized_stft and stft.onesided
        assert stft.n_fft == stft.win_length, 'the DFT front end assumes n_fft == win_length'
        assert fb.log_mel and fb.power_spectrogram == 2 and fb.freeze
        assert mvn.norm_type == 'sentence' and mvn.std_norm is False
        self.frontend = frontend
        self.n_fft, self.hop = stft.n_fft, stft.hop_length
        self.amin, self.top_db = fb.amin, fb.top_db
        self.db_offset = fb.multiplier * fb.db_multiplier
        window = stft.window.to(torch.float64)
        self.register_buffer('window', window.float())
        self.register_buffer('mel', fbank_matrix(fb))
        n = torch.arange(self.n_fft, dtype=torch.float64)
        k = torch.arange(self.n_fft // 2 + 1, dtype=torch.float64)
        ang = 2 * math.pi * k[:, None] * n[None, :] / self.n_fft
        w = torch.cat([torch.cos(ang) * window, -torch.sin(ang) * window], 0)
        self.register_buffer('dft', w.float()[:, None, :])
        self.ecapa = clf.mods.embedding_model
        for m in self.ecapa.modules():
            name = type(m).__name__
            if name == 'SEBlock':
                m.forward = se_forward.__get__(m)
            elif name == 'AttentiveStatisticsPooling':
                m.forward = asp_forward.__get__(m)

    def power(self, wav):
        if self.frontend == 'stft':
            s = torch.stft(wav, self.n_fft, self.hop, self.n_fft, self.window, center=True, pad_mode='constant',
                           normalized=False, onesided=True, return_complex=False)
            return s.pow(2).sum(-1).transpose(1, 2)  # [1, T, 201]
        x = F.pad(wav[:, None, :], (self.n_fft // 2, self.n_fft // 2))
        y = F.conv1d(x, self.dft, stride=self.hop)  # [1, 402, T]
        h = self.n_fft // 2 + 1
        return (y[:, :h] ** 2 + y[:, h:] ** 2).transpose(1, 2)

    def forward(self, waveform):
        p = self.power(waveform)
        fb = torch.matmul(p, self.mel)
        db = 10.0 * torch.log10(torch.clamp(fb, min=self.amin)) - self.db_offset
        db = torch.maximum(db, db.amax(dim=(1, 2), keepdim=True) - self.top_db)
        db = db - db.mean(dim=1, keepdim=True)
        return self.ecapa(db)[:, 0, :]


def cos(a, b):
    a, b = np.asarray(a, np.float64).ravel(), np.asarray(b, np.float64).ravel()
    return float(a @ b / np.linalg.norm(a) / np.linalg.norm(b))


def self_check(clf, onnx_path):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    s = ort.InferenceSession(onnx_path, so, providers=['CPUExecutionProvider'])
    g = torch.Generator().manual_seed(0)
    worst = 1.0
    for secs in (1.0, 3.0, 7.3):
        n = int(secs * SR)
        t = torch.arange(n) / SR
        wav = 0.05 * torch.randn(n, generator=g) + 0.2 * torch.sin(2 * math.pi * 220 * t) * torch.sin(2 * math.pi * 3 * t)
        with torch.no_grad():
            ref = clf.encode_batch(wav[None])[0][0].numpy()
        got = s.run(None, {'waveform': wav[None].numpy().astype(np.float32)})[0][0]
        c = cos(ref, got)
        worst = min(worst, c)
        print(f'self-check {secs:4.1f} s: cosine {c:.6f}')
    return worst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('ecapa_dir')
    ap.add_argument('out')
    ap.add_argument('--frontend', choices=('stft', 'dft'), default='dft')
    ap.add_argument('--opset', type=int, default=17)
    ap.add_argument('--int8', help='also write a dynamically quantised int8 copy here')
    a = ap.parse_args()
    torch.set_num_threads(4)
    with tempfile.TemporaryDirectory() as tmp:
        clf = load_classifier(a.ecapa_dir, tmp)
        clf.mods.eval()
        model = EcapaWaveform(clf, a.frontend).eval()
        dummy = torch.randn(1, 3 * SR) * 0.1
        with torch.no_grad():
            torch.onnx.export(model, (dummy,), a.out, input_names=['waveform'], output_names=['embedding'],
                              dynamic_axes={'waveform': {1: 'samples'}}, opset_version=a.opset,
                              do_constant_folding=True, dynamo=False)
        import onnx
        m = onnx.load(a.out)
        m.graph.output[0].type.tensor_type.shape.dim[0].dim_value = 1  # the tracer leaves a named batch dim
        onnx.checker.check_model(m)
        onnx.save(m, a.out)
        print('wrote', a.out)
        worst = self_check(clf, a.out)
        if a.int8:
            from onnxruntime.quantization import QuantType, quantize_dynamic
            # The front end stays float: quantising the mel MatMul's power-spectrum input wrecks the vector.
            # ConvInteger on the CPU runs uint8 weights only.
            m = onnx.load(a.out)
            front = [n.name for n in m.graph.node if n.op_type in ('Conv', 'MatMul') and not n.name.startswith('/ecapa')]
            quantize_dynamic(a.out, a.int8, weight_type=QuantType.QUInt8, nodes_to_exclude=front)
            print('wrote', a.int8)
            worst = min(worst, self_check(clf, a.int8))
        print(f'worst self-check cosine {worst:.6f}')


if __name__ == '__main__':
    main()
