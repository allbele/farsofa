#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Generates the narration of a video with Gemini text-to-speech, one file per line and language.

Borrows the approach of the owner's other video projects: lines go to the model in packets, since
a packet keeps one voice from start to end, and each packet is then cut back into lines by aligning
a transcription with the script. Runs with the Python environment of that project, which has the
libraries and the API keys (the keys are read from its .env and never printed).

Usage: <venv>/bin/python tools/narracao.py marketing/video-1/falas.json [language ...]
"""
import json
import os
import re
import subprocess
import sys
import time
import wave
from pathlib import Path

OTHER = Path.home() / "Documents/Mateus/MrTop5/pipeline"
sys.path.insert(0, str(OTHER / "tools"))
import alinhamento  # noqa: E402
from google import genai  # noqa: E402
from google.genai import errors, types  # noqa: E402

# The other project keeps its first keys for its own daily videos; start from the last ones here.
KEYS = ["GEMINI_API_KEY_ACC4", "GEMINI_API_KEY_ACC3", "GEMINI_API_KEY_ACC2"]
PACKET_LINES = 6  # about a minute of speech: longer packets make the model improvise text
FINISH = ("silenceremove=start_periods=1:start_threshold=-40dB:detection=rms,areverse,"
          "silenceremove=start_periods=1:start_threshold=-50dB:detection=rms,areverse,"
          "afade=t=in:d=0.01,areverse,afade=t=in:d=0.12,areverse,highpass=f=90")

env = {line.split("=", 1)[0].strip(): line.split("=", 1)[1].strip()
       for line in (OTHER / ".env").read_text().splitlines() if "=" in line}
queue = [name for name in KEYS if env.get(name)]
if not queue:
    sys.exit("no Gemini key available")
client = genai.Client(api_key=env[queue[0]], http_options=types.HttpOptions(timeout=600_000))


def speak(text: str, style: str, config: dict) -> tuple[bytes, int]:
    global client
    content = [types.Content(role="user", parts=[types.Part(text=f"## Transcript:\n{text}", speech_metadata=types.SpeechMetadata(style=style))])]
    settings = types.GenerateContentConfig(
        temperature=config["temperatura"], response_modalities=["audio"],
        speech_config=types.SpeechConfig(voice_config=types.VoiceConfig(prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=config["voz"]))),
    )
    for _ in range(6):
        try:
            answer = client.models.generate_content(model=config["modelo"], contents=content, config=settings)
            data = answer.candidates[0].content.parts[0].inline_data
            rate = re.search(r"rate=(\d+)", data.mime_type or "")
            return data.data, int(rate.group(1)) if rate else 24000
        except errors.APIError as error:
            if "PerDay" in str(error):
                if len(queue) == 1:
                    sys.exit("today's free quota is used up on every key")
                queue.pop(0)
                client = genai.Client(api_key=env[queue[0]], http_options=types.HttpOptions(timeout=600_000))
                print(f"  quota used up, next key ({len(queue)} left)", flush=True)
                continue
            if error.code == 429 or (error.code and error.code >= 500):
                wait = re.search(r"retry in ([\d.]+)s", str(error))
                time.sleep(float(wait.group(1)) + 1 if wait else 60)
                continue
            raise
    sys.exit("no answer from Gemini")


def main():
    script = Path(sys.argv[1])
    config = json.loads(script.read_text())
    wanted = sys.argv[2:] or list(config["idiomas"])
    out = script.parent / "voz"
    for language in wanted:
        spec = config["idiomas"][language]
        alinhamento.IDIOMA = spec["whisper"]
        folder = out / language
        folder.mkdir(parents=True, exist_ok=True)
        lines = spec["falas"]
        report = []
        for start in range(0, len(lines), PACKET_LINES):
            packet = lines[start:start + PACKET_LINES]
            names = [folder / f"{start + i + 1:02d}.mp3" for i in range(len(packet))]
            if all(name.exists() for name in names):
                continue
            for attempt in range(3):
                print(f"{language}: lines {start + 1}-{start + len(packet)}, attempt {attempt + 1}", flush=True)
                pcm, rate = speak("\n<long pause>\n".join(packet), spec["estilo"], config)
                raw = folder / f"packet-{start + 1:02d}.wav"
                with wave.open(str(raw), "wb") as file:
                    file.setnchannels(1)
                    file.setsampwidth(2)
                    file.setframerate(rate)
                    file.writeframes(pcm)
                windows = alinhamento.cortes(str(raw), packet)
                if any(window is None for window in windows):
                    print("  a line is missing from the audio, asking again", flush=True)
                    continue
                problems = []
                for text, name, (begin, end) in zip(packet, names, windows):
                    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-ss", f"{max(0, begin - 0.05):.3f}", "-to", f"{end + 0.25:.3f}",
                                    "-af", FINISH, "-ar", "44100", "-b:a", "160k", str(name)], check=True)
                    heard = " ".join(piece.text for piece in alinhamento.transcrever(str(name)))
                    match = alinhamento.similaridade(text, heard)
                    extra = len(alinhamento.norm(heard)) - len(alinhamento.norm(text))
                    if match < 0.75 or extra > 4:
                        problems.append(f"{name.name}: {match:.0%}, {extra:+d} words: {heard.strip()[:90]}")
                if not problems:
                    raw.unlink()
                    break
                print("  not what the script says:\n   " + "\n   ".join(problems), flush=True)
                for name in names:
                    name.unlink(missing_ok=True)
            else:
                sys.exit(f"{language}: lines {start + 1}+ could not be generated cleanly")
        for index in range(len(lines)):
            name = folder / f"{index + 1:02d}.mp3"
            seconds = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(name)],
                                           capture_output=True, text=True).stdout)
            report.append({"fala": index + 1, "segundos": round(seconds, 1), "texto": lines[index]})
        (folder / "duracoes.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
        print(f"{language}: {len(lines)} lines, {sum(item['segundos'] for item in report):.0f} s of speech", flush=True)


main()
