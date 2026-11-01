//! Turning a picture of the screen into something small enough to send to a phone, with nothing but this file.
//!
//! A picture goes through three steps:
//!
//! 1. [`scale_bgra`] shrinks it (averaging, so text stays readable) to the width the phone asked for.
//! 2. Every row of the 24-bit picture is "sub"-filtered: each byte becomes its difference from the byte one pixel to
//!    the left. Flat areas turn into runs of zeros, which compress very well.
//! 3. The filtered bytes are compressed into a standard zlib stream ([`zlib_compress`]), which Android opens with
//!    `java.util.zip.Inflater`.
//!
//! Every picture is complete in itself. A lost piece of one picture costs that picture, never the ones after it.

use beckon_protocol::{MAX_SCREEN_CHUNKS, MAX_SCREEN_CHUNK_BYTES};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// A picture of the screen ready to be filtered: 3 bytes (red, green, blue) a pixel, rows from the top.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rgb {
    pub width: usize,
    pub height: usize,
    pub bytes: Vec<u8>,
}

/// Shrinks a picture of `width` by `height` pixels (4 bytes a pixel: blue, green, red, unused) to at most `max_width`
/// pixels wide, keeping its shape, and drops the unused byte. `None` when the input is not that big, or is empty.
pub fn scale_bgra(src: &[u8], width: usize, height: usize, max_width: usize) -> Option<Rgb> {
    if width == 0 || height == 0 || max_width == 0 || src.len() < width * height * 4 {
        return None;
    }
    let dw = width.min(max_width);
    let dh = if dw == width {
        height
    } else {
        (height * dw).div_ceil(width).max(1)
    };
    let mut out = Vec::with_capacity(dw * dh * 3);
    for dy in 0..dh {
        let y0 = dy * height / dh;
        let y1 = ((dy + 1) * height / dh).max(y0 + 1).min(height);
        for dx in 0..dw {
            let x0 = dx * width / dw;
            let x1 = ((dx + 1) * width / dw).max(x0 + 1).min(width);
            let (mut r, mut g, mut b) = (0u32, 0u32, 0u32);
            for y in y0..y1 {
                let row = y * width * 4;
                for x in x0..x1 {
                    let p = row + x * 4;
                    b += u32::from(src[p]);
                    g += u32::from(src[p + 1]);
                    r += u32::from(src[p + 2]);
                }
            }
            let n = ((y1 - y0) * (x1 - x0)) as u32;
            out.push((r / n) as u8);
            out.push((g / n) as u8);
            out.push((b / n) as u8);
        }
    }
    Some(Rgb {
        width: dw,
        height: dh,
        bytes: out,
    })
}

/// A short fingerprint of a picture, to tell whether the screen changed since the last one (FNV-1a).
pub fn fingerprint(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for &byte in bytes {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// The "sub" filter: each byte minus the byte of the pixel to its left (the first pixel of a row stays as it is).
pub fn sub_filter(rgb: &Rgb) -> Vec<u8> {
    let row_bytes = rgb.width * 3;
    let mut out = Vec::with_capacity(rgb.bytes.len());
    for row in rgb.bytes.chunks(row_bytes.max(1)) {
        for (i, &byte) in row.iter().enumerate() {
            out.push(if i < 3 {
                byte
            } else {
                byte.wrapping_sub(row[i - 3])
            });
        }
    }
    out
}

/// A whole picture ready to send: filtered and compressed.
pub fn encode_picture(rgb: &Rgb) -> Vec<u8> {
    zlib_compress(&sub_filter(rgb))
}

/// Cuts a compressed picture into the pieces one datagram each carries. `None` when it would take too many.
pub fn split_picture(data: &[u8]) -> Option<Vec<&[u8]>> {
    let pieces: Vec<&[u8]> = data.chunks(MAX_SCREEN_CHUNK_BYTES).collect();
    if pieces.is_empty() || pieces.len() > usize::from(MAX_SCREEN_CHUNKS) {
        None
    } else {
        Some(pieces)
    }
}

// ---- who may look --------------------------------------------------------------------------------------------

/// The file whose presence in the config directory lets paired phones see the screen. The owner of the PC makes it
/// (or removes it) with the console command `screen on` / `screen off`, so a phone cannot give itself the right.
pub const ALLOW_FILE: &str = "allow-screen-view";
/// An environment variable set to `1` does the same, for a PC that starts Beckon from a script.
pub const ALLOW_ENV: &str = "BECKON_ALLOW_SCREEN";

/// Whether the owner of this PC has let phones see the screen. Off unless asked for.
pub fn allowed(config_dir: &Path) -> bool {
    allowed_with(std::env::var_os(ALLOW_ENV).as_deref(), config_dir)
}

fn allowed_with(env: Option<&std::ffi::OsStr>, config_dir: &Path) -> bool {
    env.is_some_and(|value| value == "1") || config_dir.join(ALLOW_FILE).is_file()
}

/// Lets phones see the screen (`true`) or stops them (`false`). Takes effect at once, also for a phone that is
/// looking: its pictures stop within a second.
pub fn set_allowed(config_dir: &Path, on: bool) -> std::io::Result<()> {
    let file = config_dir.join(ALLOW_FILE);
    if on {
        std::fs::create_dir_all(config_dir)?;
        std::fs::write(
            file,
            b"Phones that are paired with this PC may see its screen.\n",
        )
    } else {
        match std::fs::remove_file(file) {
            Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error),
            _ => Ok(()),
        }
    }
}

// ---- one phone's view ----------------------------------------------------------------------------------------

/// A phone is told to stop, or heard from no more, after this long without being asked again.
pub const ASK_AGAIN_WITHIN: Duration = Duration::from_secs(12);
/// A picture that has not changed is sent again after this long anyway, so that a lost one is made good.
pub const RESEND_AFTER: Duration = Duration::from_secs(2);

/// What is running for one phone: what it asked for, when it last asked, and whether it is to stop.
#[derive(Debug)]
pub struct ScreenControl {
    pub max_width: u16,
    pub fps: u8,
    stop: AtomicBool,
    asked: Mutex<Instant>,
    size: AtomicU32,
}

impl ScreenControl {
    pub fn new(max_width: u16, fps: u8, now: Instant) -> Arc<Self> {
        Arc::new(Self {
            max_width,
            fps,
            stop: AtomicBool::new(false),
            asked: Mutex::new(now),
            size: AtomicU32::new(0),
        })
    }

    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }

    pub fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }

    /// The phone asked again: it is still looking.
    pub fn touch(&self, now: Instant) {
        if let Ok(mut asked) = self.asked.lock() {
            *asked = now;
        }
    }

    /// Whether the phone has gone quiet for longer than it should.
    pub fn forgotten(&self, now: Instant) -> bool {
        self.asked.lock().map_or(true, |asked| {
            now.saturating_duration_since(*asked) > ASK_AGAIN_WITHIN
        })
    }

    pub fn set_size(&self, width: u16, height: u16) {
        self.size.store(
            (u32::from(width) << 16) | u32::from(height),
            Ordering::Relaxed,
        );
    }

    /// The size of the pictures, once there is a first one.
    pub fn size(&self) -> Option<(u16, u16)> {
        match self.size.load(Ordering::Relaxed) {
            0 => None,
            packed => Some(((packed >> 16) as u16, (packed & 0xFFFF) as u16)),
        }
    }

    /// Whether a request of `max_width` and `fps` asks for what this is already doing.
    pub fn same_as(&self, max_width: u16, fps: u8) -> bool {
        self.max_width == max_width && self.fps == fps
    }
}

/// A picture ready to be cut into pieces and sent.
#[derive(Debug, PartialEq, Eq)]
pub struct Picture {
    pub id: u32,
    pub width: u16,
    pub height: u16,
    /// The zlib stream (see the top of this file).
    pub data: Vec<u8>,
}

/// Decides, picture by picture, what goes to a phone: nothing while the screen does not change, a complete picture
/// when it does, and the same picture again every [`RESEND_AFTER`].
#[derive(Debug, Default)]
pub struct Streamer {
    last_print: Option<u64>,
    last_sent: Option<Instant>,
    next_id: u32,
}

/// Why a picture of the screen could not be made.
#[derive(Debug, PartialEq, Eq)]
pub enum PrepareError {
    /// The system gave a picture that does not hold together.
    BadImage,
    /// The picture would take more pieces than one may be cut into.
    TooBig,
}

impl Streamer {
    /// Looks at a new picture of the screen (`bgra`, `width` by `height`, 4 bytes a pixel) and returns what to send:
    /// `None` when it is the same as the last one sent and that was recent enough.
    pub fn prepare(
        &mut self,
        bgra: &[u8],
        width: usize,
        height: usize,
        max_width: usize,
        now: Instant,
    ) -> Result<Option<Picture>, PrepareError> {
        let rgb = scale_bgra(bgra, width, height, max_width).ok_or(PrepareError::BadImage)?;
        let print = fingerprint(&rgb.bytes);
        let due = self
            .last_sent
            .is_none_or(|sent| now.saturating_duration_since(sent) >= RESEND_AFTER);
        if self.last_print == Some(print) && !due {
            return Ok(None);
        }
        let data = encode_picture(&rgb);
        if split_picture(&data).is_none() {
            return Err(PrepareError::TooBig);
        }
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);
        self.last_print = Some(print);
        self.last_sent = Some(now);
        Ok(Some(Picture {
            id,
            width: u16::try_from(rgb.width).map_err(|_| PrepareError::BadImage)?,
            height: u16::try_from(rgb.height).map_err(|_| PrepareError::BadImage)?,
            data,
        }))
    }
}

// ---- zlib ------------------------------------------------------------------------------------------------------

const WINDOW: usize = 32 * 1024;
const WINDOW_MASK: usize = WINDOW - 1;
const HASH_BITS: u32 = 15;
const MIN_MATCH: usize = 3;
const MAX_MATCH: usize = 258;
/// How many earlier places with the same three bytes are tried for the longest match.
const MAX_CHAIN: usize = 24;

const LEN_BASE: [u16; 29] = [
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131,
    163, 195, 227, 258,
];
const LEN_EXTRA: [u8; 29] = [
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
];
const DIST_BASE: [u16; 30] = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537,
    2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
];
const DIST_EXTRA: [u8; 30] = [
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13,
    13,
];

struct BitWriter {
    out: Vec<u8>,
    acc: u32,
    bits: u32,
}

impl BitWriter {
    fn new(capacity: usize) -> Self {
        Self {
            out: Vec::with_capacity(capacity),
            acc: 0,
            bits: 0,
        }
    }

    /// Writes the low `count` bits of `value`, least significant first (how deflate packs numbers).
    fn put(&mut self, value: u32, count: u32) {
        self.acc |= value << self.bits;
        self.bits += count;
        while self.bits >= 8 {
            self.out.push(self.acc as u8);
            self.acc >>= 8;
            self.bits -= 8;
        }
    }

    /// Writes a Huffman code, most significant bit first (how deflate packs codes).
    fn put_code(&mut self, code: u32, count: u32) {
        let mut reversed = 0;
        for i in 0..count {
            if code & (1 << i) != 0 {
                reversed |= 1 << (count - 1 - i);
            }
        }
        self.put(reversed, count);
    }

    /// A literal byte or length symbol in the fixed Huffman table of deflate.
    fn put_symbol(&mut self, symbol: u32) {
        match symbol {
            0..=143 => self.put_code(0x30 + symbol, 8),
            144..=255 => self.put_code(0x190 + (symbol - 144), 9),
            256..=279 => self.put_code(symbol - 256, 7),
            _ => self.put_code(0xC0 + (symbol - 280), 8),
        }
    }

    fn put_match(&mut self, length: usize, distance: usize) {
        let li = LEN_BASE
            .iter()
            .rposition(|&base| usize::from(base) <= length)
            .unwrap_or(0);
        self.put_symbol(257 + li as u32);
        self.put(
            (length - usize::from(LEN_BASE[li])) as u32,
            u32::from(LEN_EXTRA[li]),
        );
        let di = DIST_BASE
            .iter()
            .rposition(|&base| usize::from(base) <= distance)
            .unwrap_or(0);
        self.put_code(di as u32, 5);
        self.put(
            (distance - usize::from(DIST_BASE[di])) as u32,
            u32::from(DIST_EXTRA[di]),
        );
    }

    fn finish(mut self) -> Vec<u8> {
        if self.bits > 0 {
            self.out.push(self.acc as u8);
        }
        self.out
    }
}

fn hash3(data: &[u8], at: usize) -> usize {
    let v = u32::from(data[at]) | (u32::from(data[at + 1]) << 8) | (u32::from(data[at + 2]) << 16);
    (v.wrapping_mul(0x9E37_79B1) >> (32 - HASH_BITS)) as usize
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for block in data.chunks(5552) {
        for &byte in block {
            a += u32::from(byte);
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    (b << 16) | a
}

/// Compresses `data` into a zlib stream (one deflate block with the fixed Huffman codes, and matches found by a
/// hash chain over the last 32 KiB). It does not compress as hard as `zlib` at its best, but needs nothing else.
pub fn zlib_compress(data: &[u8]) -> Vec<u8> {
    let mut writer = BitWriter::new(data.len() / 4 + 64);
    writer.put(1, 1); // the last (and only) block
    writer.put(1, 2); // fixed Huffman codes

    let n = data.len();
    let mut head = vec![u32::MAX; 1 << HASH_BITS];
    let mut prev = vec![u32::MAX; WINDOW];
    let mut pos = 0usize;
    while pos < n {
        let mut best_len = 0usize;
        let mut best_dist = 0usize;
        if pos + MIN_MATCH <= n {
            let h = hash3(data, pos);
            let mut candidate = head[h];
            let limit = (n - pos).min(MAX_MATCH);
            let mut tries = 0;
            while candidate != u32::MAX && tries < MAX_CHAIN {
                let c = candidate as usize;
                if c >= pos || pos - c > WINDOW {
                    break;
                }
                let mut len = 0;
                while len < limit && data[c + len] == data[pos + len] {
                    len += 1;
                }
                if len > best_len {
                    best_len = len;
                    best_dist = pos - c;
                    if len == limit {
                        break;
                    }
                }
                let next = prev[c & WINDOW_MASK];
                // Positions only ever go down along a chain; one that does not has been written over.
                if next != u32::MAX && next as usize >= c {
                    break;
                }
                candidate = next;
                tries += 1;
            }
        }
        let step = if best_len >= MIN_MATCH {
            writer.put_match(best_len, best_dist);
            best_len
        } else {
            writer.put_symbol(u32::from(data[pos]));
            1
        };
        // Every position that was passed over can start a later match.
        for p in pos..pos + step {
            if p + MIN_MATCH <= n {
                let h = hash3(data, p);
                prev[p & WINDOW_MASK] = head[h];
                head[h] = p as u32;
            }
        }
        pos += step;
    }
    writer.put_symbol(256);
    let body = writer.finish();

    let mut out = Vec::with_capacity(body.len() + 6);
    out.extend_from_slice(&[0x78, 0x9C]);
    out.extend_from_slice(&body);
    out.extend_from_slice(&adler32(data).to_be_bytes());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A small reader of the streams this file writes (and of stored blocks), to check the compressor against
    /// something other than itself. Real zlib reads them too: the Android side relies on that.
    fn inflate(zlib: &[u8]) -> Vec<u8> {
        assert_eq!(&zlib[..2], &[0x78, 0x9C]);
        let body = &zlib[2..zlib.len() - 4];
        let mut bit = 0usize;
        let mut take = |count: usize| -> u32 {
            let mut value = 0u32;
            for i in 0..count {
                let byte = body[bit / 8];
                value |= u32::from((byte >> (bit % 8)) & 1) << i;
                bit += 1;
            }
            value
        };
        let mut out: Vec<u8> = Vec::new();
        assert_eq!(take(1), 1);
        assert_eq!(take(2), 1);
        loop {
            // Read the Huffman code one bit at a time, most significant first.
            let mut code = 0u32;
            for _ in 0..7 {
                code = (code << 1) | take(1);
            }
            let symbol = if code <= 0b001_0111 {
                256 + code
            } else {
                code = (code << 1) | take(1);
                if (0x30..=0xBF).contains(&code) {
                    code - 0x30
                } else if (0xC0..=0xC7).contains(&code) {
                    280 + code - 0xC0
                } else {
                    code = (code << 1) | take(1);
                    assert!((0x190..=0x1FF).contains(&code), "bad code {code:#x}");
                    144 + code - 0x190
                }
            };
            match symbol {
                256 => break,
                0..=255 => out.push(symbol as u8),
                _ => {
                    let li = (symbol - 257) as usize;
                    let length =
                        usize::from(LEN_BASE[li]) + take(usize::from(LEN_EXTRA[li])) as usize;
                    let mut dcode = 0u32;
                    for _ in 0..5 {
                        dcode = (dcode << 1) | take(1);
                    }
                    let di = dcode as usize;
                    let distance =
                        usize::from(DIST_BASE[di]) + take(usize::from(DIST_EXTRA[di])) as usize;
                    for _ in 0..length {
                        let byte = out[out.len() - distance];
                        out.push(byte);
                    }
                }
            }
        }
        out
    }

    fn noise(len: usize, seed: u32) -> Vec<u8> {
        let mut state = seed;
        (0..len)
            .map(|_| {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                (state >> 24) as u8
            })
            .collect()
    }

    #[test]
    fn compressed_data_reads_back_exactly() {
        let mut cases: Vec<Vec<u8>> = vec![
            Vec::new(),
            vec![7],
            vec![1, 2],
            vec![0; 10_000],
            b"abcabcabcabcabcabc".to_vec(),
            noise(5000, 1),
        ];
        // Long repeats at a distance, and a stretch that is more than one window long.
        let mut mixed = noise(300, 2);
        mixed.extend(mixed.clone());
        mixed.extend(vec![9; 700]);
        mixed.extend(noise(70_000, 3));
        mixed.extend(&mixed.clone()[..40_000]);
        cases.push(mixed);
        for data in cases {
            let packed = zlib_compress(&data);
            assert_eq!(inflate(&packed), data, "{} bytes", data.len());
        }
    }

    #[test]
    fn a_flat_picture_shrinks_to_almost_nothing() {
        let rgb = Rgb {
            width: 640,
            height: 360,
            bytes: [30u8, 40, 50].repeat(640 * 360),
        };
        let packed = encode_picture(&rgb);
        assert!(packed.len() < 8_000, "{} bytes", packed.len());
        let unfiltered = unfilter(&inflate(&packed), 640, 360);
        assert_eq!(unfiltered, rgb.bytes);
    }

    /// What the phone does after inflating: add each byte to the one a pixel to its left.
    fn unfilter(filtered: &[u8], width: usize, height: usize) -> Vec<u8> {
        let row_bytes = width * 3;
        assert_eq!(filtered.len(), row_bytes * height);
        let mut out = filtered.to_vec();
        for row in out.chunks_mut(row_bytes) {
            for i in 3..row.len() {
                row[i] = row[i].wrapping_add(row[i - 3]);
            }
        }
        out
    }

    #[test]
    fn filtering_is_undone_exactly() {
        let rgb = Rgb {
            width: 37,
            height: 11,
            bytes: noise(37 * 11 * 3, 5),
        };
        assert_eq!(unfilter(&sub_filter(&rgb), 37, 11), rgb.bytes);
    }

    #[test]
    fn scaling_keeps_the_shape_and_averages() {
        // 8x4 picture, left half white, right half black (blue, green, red, unused).
        let mut src = Vec::new();
        for _y in 0..4 {
            for x in 0..8 {
                let v = if x < 4 { 255 } else { 0 };
                src.extend_from_slice(&[v, v, v, 0]);
            }
        }
        let scaled = scale_bgra(&src, 8, 4, 4).unwrap();
        assert_eq!((scaled.width, scaled.height), (4, 2));
        assert_eq!(scaled.bytes.len(), 4 * 2 * 3);
        assert_eq!(&scaled.bytes[..3], &[255, 255, 255]);
        assert_eq!(&scaled.bytes[9..12], &[0, 0, 0]);
        // Already narrow enough: same size, only the unused byte is dropped, and colours go blue-green-red to
        // red-green-blue.
        let same = scale_bgra(&[1, 2, 3, 0, 4, 5, 6, 0], 2, 1, 100).unwrap();
        assert_eq!(same.bytes, vec![3, 2, 1, 6, 5, 4]);
    }

    #[test]
    fn nonsense_sizes_are_refused() {
        assert!(scale_bgra(&[], 0, 0, 10).is_none());
        assert!(scale_bgra(&[0; 8], 2, 2, 10).is_none());
        assert!(scale_bgra(&[0; 16], 2, 2, 0).is_none());
    }

    #[test]
    fn a_picture_is_cut_into_datagram_sized_pieces() {
        let data = vec![1u8; MAX_SCREEN_CHUNK_BYTES * 3 + 5];
        let pieces = split_picture(&data).unwrap();
        assert_eq!(pieces.len(), 4);
        assert!(pieces.iter().all(|p| p.len() <= MAX_SCREEN_CHUNK_BYTES));
        assert_eq!(pieces.concat(), data);
        assert!(split_picture(&[]).is_none());
        let huge = vec![0u8; MAX_SCREEN_CHUNK_BYTES * (usize::from(MAX_SCREEN_CHUNKS) + 1)];
        assert!(split_picture(&huge).is_none());
    }

    #[test]
    fn fingerprints_tell_pictures_apart() {
        assert_eq!(fingerprint(b"same"), fingerprint(b"same"));
        assert_ne!(fingerprint(b"same"), fingerprint(b"sama"));
    }

    fn solid(width: usize, height: usize, shade: u8) -> Vec<u8> {
        [shade, shade, shade, 0].repeat(width * height)
    }

    #[test]
    fn the_streamer_sends_changes_and_repeats_a_still_screen_now_and_then() {
        let mut streamer = Streamer::default();
        let start = Instant::now();
        let first = streamer
            .prepare(&solid(64, 32, 10), 64, 32, 32, start)
            .unwrap()
            .expect("the first picture is always sent");
        assert_eq!((first.id, first.width, first.height), (0, 32, 16));
        // The same screen a moment later: nothing to send.
        let soon = start + Duration::from_millis(500);
        assert_eq!(
            streamer.prepare(&solid(64, 32, 10), 64, 32, 32, soon),
            Ok(None)
        );
        // The screen changed: a new picture with the next number.
        let changed = streamer
            .prepare(&solid(64, 32, 200), 64, 32, 32, soon)
            .unwrap()
            .unwrap();
        assert_eq!(changed.id, 1);
        // Still the same, but long enough since the last one: sent again, so a lost picture is made good.
        let later = soon + RESEND_AFTER;
        let again = streamer
            .prepare(&solid(64, 32, 200), 64, 32, 32, later)
            .unwrap()
            .unwrap();
        assert_eq!(again.id, 2);
        assert_eq!(again.data, changed.data);
    }

    #[test]
    fn the_streamer_refuses_a_picture_that_does_not_hold_together() {
        let mut streamer = Streamer::default();
        assert_eq!(
            streamer.prepare(&[0; 10], 64, 32, 32, Instant::now()),
            Err(PrepareError::BadImage)
        );
    }

    #[test]
    fn a_control_remembers_size_stop_and_who_went_quiet() {
        let start = Instant::now();
        let control = ScreenControl::new(640, 3, start);
        assert_eq!(control.size(), None);
        control.set_size(640, 360);
        assert_eq!(control.size(), Some((640, 360)));
        assert!(control.same_as(640, 3));
        assert!(!control.same_as(640, 4));
        assert!(!control.stopped());
        assert!(!control.forgotten(start + ASK_AGAIN_WITHIN));
        assert!(control.forgotten(start + ASK_AGAIN_WITHIN + Duration::from_secs(1)));
        control.touch(start + Duration::from_secs(10));
        assert!(!control.forgotten(start + Duration::from_secs(20)));
        control.stop();
        assert!(control.stopped());
    }

    #[test]
    fn the_owner_decides_whether_phones_may_look() {
        let dir = std::env::temp_dir().join(format!("beckon_screen_allow_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        assert!(!allowed_with(None, &dir), "off unless asked for");
        set_allowed(&dir, true).unwrap();
        assert!(allowed_with(None, &dir));
        set_allowed(&dir, false).unwrap();
        assert!(!allowed_with(None, &dir));
        // Turning it off when it was never on is not an error.
        set_allowed(&dir, false).unwrap();
        // The environment variable counts only when it is exactly 1.
        assert!(allowed_with(Some(std::ffi::OsStr::new("1")), &dir));
        assert!(!allowed_with(Some(std::ffi::OsStr::new("0")), &dir));
        assert!(!allowed_with(Some(std::ffi::OsStr::new("yes")), &dir));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn dump_for_an_outside_check() {
        // Written only when asked, so that real zlib can be pointed at the same bytes.
        let Ok(dir) = std::env::var("BECKON_SCREEN_DUMP") else {
            return;
        };
        let rgb = Rgb {
            width: 64,
            height: 48,
            bytes: (0..64 * 48 * 3)
                .map(|i| ((i / 3 % 64) * 4 + (i % 3) * 50 + (i / 192 % 7) * 9) as u8)
                .collect(),
        };
        std::fs::write(format!("{dir}/picture.raw"), &rgb.bytes).unwrap();
        std::fs::write(format!("{dir}/picture.z"), encode_picture(&rgb)).unwrap();
        let big = noise(200_000, 11);
        std::fs::write(format!("{dir}/noise.raw"), &big).unwrap();
        std::fs::write(format!("{dir}/noise.z"), zlib_compress(&big)).unwrap();
    }
}
