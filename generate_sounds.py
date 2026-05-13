import wave
import struct
import math
import subprocess
import os
import random

sample_rate = 44100

def generate_heartbeat(duration_sec):
    samples = []
    for i in range(int(sample_rate * duration_sec)):
        t = i / sample_rate
        # Simple heartbeat: low frequency thumps
        beat1 = math.sin(2 * math.pi * 50 * t) * math.exp(-15 * (t % 0.8))
        beat2 = math.sin(2 * math.pi * 50 * t) * math.exp(-15 * ((t - 0.2) % 0.8))
        signal = beat1 + beat2
        samples.append(signal)
    
    # Normalize
    max_val = max(abs(s) for s in samples)
    if max_val > 0:
        samples = [s / max_val for s in samples]
    return samples

def generate_whoosh(duration_sec):
    samples = []
    for i in range(int(sample_rate * duration_sec)):
        t = i / sample_rate
        noise = random.uniform(-1, 1)
        envelope = math.exp(3 * t) - 1
        signal = noise * envelope
        samples.append(signal)
        
    # Apply a simple lowpass filter effect by smoothing
    smoothed = []
    window = 10
    for i in range(len(samples)):
        start = max(0, i - window // 2)
        end = min(len(samples), i + window // 2)
        smoothed.append(sum(samples[start:end]) / (end - start))
        
    # Normalize
    max_val = max(abs(s) for s in smoothed)
    if max_val > 0:
        smoothed = [s / max_val for s in smoothed]
    return smoothed

def generate_eerie_tone(duration_sec):
    samples = []
    for i in range(int(sample_rate * duration_sec)):
        t = i / sample_rate
        tone1 = math.sin(2 * math.pi * 440 * t)
        tone2 = math.sin(2 * math.pi * 452 * t)
        tone3 = math.sin(2 * math.pi * 880 * t)
        tremolo = 0.5 + 0.5 * math.sin(2 * math.pi * 5 * t)
        signal = (tone1 + tone2 + tone3) * tremolo
        fade_in = max(0, min(t / 2.0, 1))
        signal = signal * fade_in
        samples.append(signal)
        
    # Normalize
    max_val = max(abs(s) for s in samples)
    if max_val > 0:
        samples = [s / max_val for s in samples]
    return samples

def save_as_ogg(samples, filename):
    wav_filename = filename.replace('.ogg', '.wav')
    
    with wave.open(wav_filename, 'w') as wav_file:
        wav_file.setnchannels(1) # Mono
        wav_file.setsampwidth(2) # 16-bit
        wav_file.setframerate(sample_rate)
        
        for sample in samples:
            value = int(sample * 32767.0)
            data = struct.pack('<h', value)
            wav_file.writeframesraw(data)
            
    # Convert to ogg using ffmpeg
    subprocess.run(['ffmpeg', '-y', '-i', wav_filename, '-c:a', 'libvorbis', '-q:a', '4', filename], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.remove(wav_filename)
    print(f"Generated {filename}")

os.makedirs('src/main/resources/assets/rbd/sounds', exist_ok=True)

save_as_ogg(generate_heartbeat(3.0), 'src/main/resources/assets/rbd/sounds/heartbeat_fast.ogg')
save_as_ogg(generate_whoosh(1.5), 'src/main/resources/assets/rbd/sounds/reverse_whoosh.ogg')
save_as_ogg(generate_eerie_tone(5.0), 'src/main/resources/assets/rbd/sounds/call_of_the_witch.ogg')
