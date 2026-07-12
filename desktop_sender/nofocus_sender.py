#!/usr/bin/env python3
"""Secure, low-latency PC system-audio sender for NoFocus Player."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import platform
import queue
import socket
import struct
import sys
import threading
import time

MAGIC = b"NFP2"
VERSION = 2
TYPE_HELLO = 1
TYPE_AUDIO = 2
PORT = 39821
SAMPLE_RATE = 48_000
CHANNELS = 2
BYTES_PER_SAMPLE = 2
FRAMES_PER_PACKET = 240  # 5 ms; safely below a normal Ethernet/Wi-Fi MTU.
CAPTURE_BATCH_FRAMES = 480  # WASAPI/CoreAudio/PulseAudio commonly schedule at 10 ms.
HELLO_STRUCT = struct.Struct("!4sBBHQIBBH24s")
AUDIO_HEADER = struct.Struct("!4sBBHQIQHH")


def pairing_key(code: str) -> bytes:
    normalized = code.replace("-", "").strip().upper()
    if len(normalized) < 16:
        raise ValueError("pairing code must contain 16 characters")
    return hashlib.sha256(normalized.encode("ascii")).digest()


def hello_packet(key: bytes, session_id: int, sender_name: str, frames: int = FRAMES_PER_PACKET) -> bytes:
    encoded_name = sender_name.encode("utf-8")[:24]
    body = HELLO_STRUCT.pack(
        MAGIC, VERSION, TYPE_HELLO, 64, session_id, SAMPLE_RATE, CHANNELS,
        BYTES_PER_SAMPLE, frames, encoded_name.ljust(24, b"\0")
    )
    return body + hmac.new(key, body, hashlib.sha256).digest()[:16]


def audio_packet(aesgcm, session_id: int, sequence: int, timestamp_frames: int, pcm: bytes,
                 frames: int = FRAMES_PER_PACKET) -> bytes:
    if sequence > 0xFFFFFFFF:
        raise OverflowError("session packet counter exhausted; restart the sender")
    encrypted_length = len(pcm) + 16
    header = AUDIO_HEADER.pack(
        MAGIC, VERSION, TYPE_AUDIO, AUDIO_HEADER.size, session_id, sequence,
        timestamp_frames, frames, encrypted_length
    )
    nonce = struct.pack("!QI", session_id, sequence)
    return header + aesgcm.encrypt(nonce, pcm, header)


def load_dependencies():
    try:
        import numpy as np
        import soundcard as sc
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as error:
        print("Missing desktop dependencies. Run:", file=sys.stderr)
        print(f"  {sys.executable} -m pip install -r requirements.txt", file=sys.stderr)
        raise SystemExit(2) from error
    return np, sc, AESGCM


def choose_loopback(sc, device_name: str | None):
    if device_name:
        matches = [item for item in sc.all_microphones(include_loopback=True)
                   if device_name.lower() in item.name.lower()]
        if not matches:
            raise RuntimeError(f"No loopback device matched {device_name!r}; use --list-devices")
        return matches[0]

    speaker = sc.default_speaker()
    if speaker is None:
        raise RuntimeError("No default speaker was found")
    matches = [item for item in sc.all_microphones(include_loopback=True)
               if item.isloopback and (speaker.name in item.name or item.name in speaker.name)]
    if matches:
        return matches[0]
    # soundcard maps the speaker ID to its monitor/loopback source on Windows and PulseAudio.
    return sc.get_microphone(speaker.id, include_loopback=True)


def list_devices(sc) -> None:
    print("Speakers:")
    for item in sc.all_speakers():
        print(f"  {item.name}  [id={item.id}]")
    print("Capture and loopback devices:")
    for item in sc.all_microphones(include_loopback=True):
        kind = "loopback" if item.isloopback else "input"
        print(f"  {item.name} ({kind})  [id={item.id}]")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Stream PC system audio to NoFocus Player")
    parser.add_argument("--host", help="phone IPv4 address shown in the app")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--code", help="pairing code shown in the app")
    parser.add_argument("--device", help="substring of a specific loopback device name")
    parser.add_argument("--name", default=platform.node() or "Computer", help="sender name shown on the phone")
    parser.add_argument("--duration", type=float, help="stop after this many seconds (useful for diagnostics)")
    parser.add_argument("--list-devices", action="store_true")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    np, sc, AESGCM = load_dependencies()
    if args.list_devices:
        list_devices(sc)
        return 0
    if not args.host or not args.code:
        raise SystemExit("--host and --code are required (copy them from the phone app)")

    key = pairing_key(args.code)
    aesgcm = AESGCM(key)
    session_id = int.from_bytes(os.urandom(8), "big")
    hello = hello_packet(key, session_id, args.name)
    target = (args.host, args.port)
    capture = choose_loopback(sc, args.device)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
    try:
        udp.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)  # Expedited forwarding when the LAN honors DSCP.
    except OSError:
        pass

    print(f"Capturing: {capture.name}")
    print(f"Sending encrypted 48 kHz stereo PCM to {args.host}:{args.port}")
    print("Press Ctrl+C to stop.")
    sequence = 0
    timestamp_frames = 0
    next_hello = 0.0
    stop_at = time.monotonic() + args.duration if args.duration else None
    started_at = time.monotonic()
    pcm_queue = queue.Queue(maxsize=4)
    capture_errors = queue.Queue(maxsize=1)
    stop_capture = threading.Event()

    def enqueue_latest(pcm):
        while True:
            try:
                pcm_queue.put_nowait(pcm)
                return
            except queue.Full:
                try:
                    pcm_queue.get_nowait()
                except queue.Empty:
                    pass

    def capture_loop():
        try:
            with capture.recorder(samplerate=SAMPLE_RATE, channels=CHANNELS,
                                  blocksize=CAPTURE_BATCH_FRAMES) as recorder:
                while not stop_capture.is_set():
                    samples = np.asarray(recorder.record(numframes=CAPTURE_BATCH_FRAMES))
                    if samples.ndim == 1:
                        samples = np.column_stack((samples, samples))
                    if samples.shape[1] == 1:
                        samples = np.repeat(samples, 2, axis=1)
                    samples = np.clip(samples[:, :2], -1.0, 1.0)
                    for offset in range(0, CAPTURE_BATCH_FRAMES, FRAMES_PER_PACKET):
                        chunk = samples[offset:offset + FRAMES_PER_PACKET]
                        enqueue_latest((chunk * 32767.0).astype("<i2", copy=False).tobytes())
        except Exception as error:
            try:
                capture_errors.put_nowait(error)
            except queue.Full:
                pass

    capture_thread = threading.Thread(target=capture_loop, name="NoFocus capture", daemon=True)
    capture_thread.start()
    packet_period = FRAMES_PER_PACKET / SAMPLE_RATE
    next_packet_at = time.monotonic()
    silence = bytes(FRAMES_PER_PACKET * CHANNELS * BYTES_PER_SAMPLE)
    previous_datagram = None
    try:
        while True:
            now = time.monotonic()
            if stop_at is not None and now >= stop_at:
                elapsed = now - started_at
                print(f"Diagnostic duration complete: {sequence} frames sent at {sequence / elapsed:.2f} packets/s.")
                return 0
            if not capture_errors.empty():
                raise capture_errors.get_nowait()
            if now >= next_hello:
                udp.sendto(hello, target)
                next_hello = now + 2.0
            if now - next_packet_at > packet_period * 2:
                next_packet_at = now
            delay = next_packet_at - now
            if delay > 0:
                time.sleep(delay)
            try:
                pcm = pcm_queue.get_nowait()
            except queue.Empty:
                pcm = silence
            datagram = audio_packet(aesgcm, session_id, sequence, timestamp_frames, pcm)
            udp.sendto(datagram, target)
            # Repeat the previous 5 ms frame with time diversity. A receiver with a 10 ms jitter
            # target can recover one lost Wi-Fi datagram without waiting for a round trip.
            if previous_datagram is not None:
                udp.sendto(previous_datagram, target)
            previous_datagram = datagram
            sequence += 1
            timestamp_frames += FRAMES_PER_PACKET
            next_packet_at += packet_period
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0
    finally:
        stop_capture.set()
        capture_thread.join(timeout=1.0)
        udp.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        print("Check the phone address/code and use --list-devices to inspect capture devices.", file=sys.stderr)
        raise SystemExit(1)
