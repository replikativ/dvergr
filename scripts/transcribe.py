#!/usr/bin/env python3
"""Local STT via faster-whisper (the same engine jitsi's skynet uses).

Usage: transcribe.py <audio-file> [model-size]
Prints the transcript to stdout; exits non-zero on failure.
Model downloads to ~/.cache/huggingface on first use
(tiny=75MB, base=145MB, small=460MB)."""
import sys

def main():
    if len(sys.argv) < 2:
        print("usage: transcribe.py <audio-file> [model-size]", file=sys.stderr)
        return 2
    path = sys.argv[1]
    model_size = sys.argv[2] if len(sys.argv) > 2 else "base"
    from faster_whisper import WhisperModel
    model = WhisperModel(model_size, device="cpu", compute_type="int8")
    # No VAD: telegram voice notes are intentional speech; VAD eats short
    # clips. Whisper hallucinates on silence ("You", "Thank you.") — use
    # a no-speech heuristic instead: all segments low avg_logprob/high
    # no_speech_prob means silence.
    segments, _info = model.transcribe(path, vad_filter=False, beam_size=5)
    segs = list(segments)
    speechy = [s for s in segs if s.no_speech_prob < 0.6]
    text = " ".join(s.text.strip() for s in speechy).strip()
    print(text)
    return 0 if text else 1

if __name__ == "__main__":
    sys.exit(main())
