use std::f32::consts::PI;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::process::{Child, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;

pub const BANDS: usize = 64;
const FFT_SIZE: usize = 1024;
const SAMPLE_RATE: f32 = 48_000.0;
/// Stereo, signed 16-bit little-endian.
const FRAME_BYTES: u64 = 4;
const WINDOW_BYTES: u64 = FFT_SIZE as u64 * FRAME_BYTES;

/// Real spectrum for the visualizer. A second, silent mpv decodes the current
/// track to a raw PCM file (`--ao=pcm`, wave header disabled). Decoding is much
/// faster than realtime, so the analyser simply reads the window that matches
/// the main player's position. Seeking therefore needs no extra synchronisation.
#[derive(Clone)]
pub struct Visualizer {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    child: Option<Child>,
    file: PathBuf,
    path: String,
    generation: u64,
    ready: bool,
    smoothed: [f32; BANDS],
    band_max: [f32; BANDS],
}

impl Visualizer {
    pub fn new() -> Self {
        let file = std::env::temp_dir().join(format!(
            "onyx-visualizer-{}.pcm",
            std::process::id()
        ));
        Self {
            inner: Arc::new(Mutex::new(Inner {
                child: None,
                file,
                path: String::new(),
                generation: 0,
                ready: false,
                smoothed: [0.0; BANDS],
                band_max: [-120.0; BANDS],
            })),
        }
    }

    /// Starts decoding `path` in the background. Reuses an already decoded
    /// track. `proxy` is only used for YouTube/HTTP streams.
    pub fn prepare(&self, path: &str, proxy: Option<&str>) -> Result<(), String> {
        if path.trim().is_empty() {
            self.stop();
            return Ok(());
        }
        let mpv = crate::player::find_mpv()
            .ok_or("mpv.exe не найден — визуализатор недоступен")?;

        let (file, generation, child) = {
            let mut inner = self.inner.lock().map_err(|error| error.to_string())?;
            if inner.path == path && (inner.child.is_some() || inner.ready) {
                return Ok(());
            }
            if let Some(mut child) = inner.child.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            let _ = fs::remove_file(&inner.file);
            inner.path = path.to_string();
            inner.generation += 1;
            inner.ready = false;
            inner.smoothed = [0.0; BANDS];
            inner.band_max = [-120.0; BANDS];
            (inner.file.clone(), inner.generation, ())
        };
        let _ = child;

        let file_arg = format!("--ao-pcm-file={}", file.display());
        let mut command = crate::process::hidden_command(&mpv);
        command.args([
            "--no-config",
            "--no-video",
            "--really-quiet",
            "--ao=pcm",
            file_arg.as_str(),
            "--ao-pcm-waveheader=no",
            "--audio-format=s16",
            "--audio-samplerate=48000",
            "--audio-channels=stereo",
            "--volume=100",
        ]);
        if path.starts_with("http") {
            command.args([
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "--referrer=https://www.youtube.com/",
            ]);
        }
        if let Some(proxy) = proxy {
            command.arg(format!("--http-proxy={proxy}"));
        }
        command
            .arg(path)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        let child = command
            .spawn()
            .map_err(|error| format!("Не удалось запустить mpv для визуализатора: {error}"))?;

        {
            let mut inner = self.inner.lock().map_err(|error| error.to_string())?;
            if inner.generation != generation {
                return Ok(());
            }
            inner.child = Some(child);
        }

        let inner = Arc::clone(&self.inner);
        thread::spawn(move || {
            // Wait until the decoder finishes (or is killed by a newer track).
            loop {
                thread::sleep(std::time::Duration::from_millis(80));
                let mut guard = match inner.lock() {
                    Ok(guard) => guard,
                    Err(_) => return,
                };
                if guard.generation != generation {
                    return;
                }
                let Some(child) = guard.child.as_mut() else {
                    return;
                };
                match child.try_wait() {
                    Ok(Some(_)) => {
                        guard.child = None;
                        guard.ready = true;
                        return;
                    }
                    Ok(None) => {}
                    Err(_) => {
                        guard.child = None;
                        guard.ready = true;
                        return;
                    }
                }
            }
        });
        Ok(())
    }

    pub fn stop(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(mut child) = inner.child.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            inner.generation += 1;
            inner.path.clear();
            inner.ready = false;
            inner.smoothed = [0.0; BANDS];
            inner.band_max = [-120.0; BANDS];
            let _ = fs::remove_file(&inner.file);
        }
    }

    /// Spectrum for the given playback position. Returns 64 bands in 0..1.
    pub fn bands(&self, position_ms: u64, playing: bool) -> Vec<f32> {
        let mut inner = match self.inner.lock() {
            Ok(inner) => inner,
            Err(_) => return vec![0.0; BANDS],
        };
        if !playing || inner.path.is_empty() {
            for value in inner.smoothed.iter_mut() {
                *value *= 0.80;
            }
            return inner.smoothed.to_vec();
        }

        let offset = position_ms.saturating_mul(48) * FRAME_BYTES;
        let mut buffer = vec![0u8; WINDOW_BYTES as usize];
        let read = (|| -> Option<()> {
            let mut file = File::open(&inner.file).ok()?;
            file.seek(SeekFrom::Start(offset)).ok()?;
            file.read_exact(&mut buffer).ok()?;
            Some(())
        })();
        if read.is_none() {
            // The decoder has not reached this position yet (or the track ended).
            for value in inner.smoothed.iter_mut() {
                *value *= 0.86;
            }
            return inner.smoothed.to_vec();
        }

        let mut re = [0f32; FFT_SIZE];
        let mut im = [0f32; FFT_SIZE];
        for index in 0..FFT_SIZE {
            let base = index * 4;
            let left = i16::from_le_bytes([buffer[base], buffer[base + 1]]) as f32 / 32768.0;
            let right =
                i16::from_le_bytes([buffer[base + 2], buffer[base + 3]]) as f32 / 32768.0;
            let window = 0.5 - 0.5 * ((2.0 * PI * index as f32) / (FFT_SIZE as f32 - 1.0)).cos();
            re[index] = (left + right) * 0.5 * window;
        }
        fft(&mut re, &mut im);

        let nyquist = FFT_SIZE / 2;
        let mut raw_db = [0f32; BANDS];
        let mut max_db = -120f32;
        for band in 0..BANDS {
            let f0 = 40.0 * 400.0f32.powf(band as f32 / BANDS as f32);
            let f1 = 40.0 * 400.0f32.powf((band + 1) as f32 / BANDS as f32);
            let i0 = ((f0 * FFT_SIZE as f32 / SAMPLE_RATE) as usize).clamp(1, nyquist - 1);
            let i1 = ((f1 * FFT_SIZE as f32 / SAMPLE_RATE) as usize).clamp(i0 + 1, nyquist - 1);
            let mut energy = 0f32;
            let mut count = 0usize;
            for bin in i0..i1 {
                energy += re[bin] * re[bin] + im[bin] * im[bin];
                count += 1;
            }
            let rms = (energy / count.max(1) as f32).sqrt();
            let db = 20.0 * (rms + 1e-7).log10();
            raw_db[band] = db;
            max_db = max_db.max(db);
        }

        if max_db < -72.0 {
            for value in inner.smoothed.iter_mut() {
                *value *= 0.86;
            }
            return inner.smoothed.to_vec();
        }

        for band in 0..BANDS {
            inner.band_max[band] = raw_db[band].max(inner.band_max[band] - 0.06);
            let reference = inner.band_max[band];
            let absolute = if reference < -55.0 {
                0.0
            } else {
                ((raw_db[band] - (reference - 32.0)) / 32.0).clamp(0.0, 1.0)
            };
            let tilt = 0.92 + 0.16 * band as f32 / BANDS as f32;
            let target = (absolute * tilt).clamp(0.0, 1.0);
            inner.smoothed[band] = if target > inner.smoothed[band] {
                inner.smoothed[band] + (target - inner.smoothed[band]) * 0.22
            } else {
                inner.smoothed[band] * 0.90
            };
        }
        inner.smoothed.to_vec()
    }
}

impl Drop for Visualizer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= n {
        let angle = -2.0 * PI / len as f32;
        let w_re = angle.cos();
        let w_im = angle.sin();
        let mut i = 0usize;
        while i < n {
            let mut cur_re = 1.0f32;
            let mut cur_im = 0.0f32;
            for k in 0..len / 2 {
                let u_re = re[i + k];
                let u_im = im[i + k];
                let v_re = re[i + k + len / 2] * cur_re - im[i + k + len / 2] * cur_im;
                let v_im = re[i + k + len / 2] * cur_im + im[i + k + len / 2] * cur_re;
                re[i + k] = u_re + v_re;
                im[i + k] = u_im + v_im;
                re[i + k + len / 2] = u_re - v_re;
                im[i + k + len / 2] = u_im - v_im;
                let next_re = cur_re * w_re - cur_im * w_im;
                cur_im = cur_re * w_im + cur_im * w_re;
                cur_re = next_re;
            }
            i += len;
        }
        len <<= 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fft_of_a_sine_peaks_at_its_bin() {
        let mut re = [0f32; FFT_SIZE];
        let mut im = [0f32; FFT_SIZE];
        let bin = 64;
        for (index, value) in re.iter_mut().enumerate() {
            *value = (2.0 * PI * bin as f32 * index as f32 / FFT_SIZE as f32).sin();
        }
        fft(&mut re, &mut im);
        let magnitudes: Vec<f32> = (0..FFT_SIZE / 2)
            .map(|index| re[index] * re[index] + im[index] * im[index])
            .collect();
        let peak = magnitudes
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .map(|(index, _)| index)
            .unwrap();
        assert!((peak as i32 - bin as i32).abs() <= 1, "peak at {peak}");
    }
}
