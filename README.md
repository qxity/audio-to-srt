# Audio ke SRT

Aplikasi Android (Kotlin + Jetpack Compose) untuk mengubah audio/video menjadi subtitle `.srt`
memakai Whisper large-v3 lewat API Groq.

## Alur kerja

1. **Decode** — file apa pun yang didukung Android (mp3, m4a, wav, ogg, opus, flac, mp4, mkv, …)
   didekode dengan MediaCodec menjadi PCM 16 kHz mono (format asli Whisper), dengan filter
   anti-aliasing saat resampling.
2. **Potong** — audio panjang dipotong ±10 menit, tepat di titik paling hening, supaya tidak ada
   kata yang terpotong. Tiap potongan dinormalisasi volumenya.
3. **Transkripsi** — Whisper large-v3, `temperature=0`, timestamp per kata + per segmen.
   Kalimat terakhir potongan sebelumnya dikirim sebagai konteks agar ejaan nama tetap konsisten.
   Batas pemakaian Groq (HTTP 429) ditunggu otomatis lalu dicoba lagi.
4. **Bersihkan** — segmen halusinasi (teks yang muncul saat hening) dibuang; tanda baca dan huruf
   kapital dari teks segmen ditempelkan ke tiap kata.
5. **Susun subtitle** — dipotong per kalimat atau jeda bicara, maks. 42 karakter × 2 baris,
   maks. 6 detik, titik potong diutamakan di koma/jeda, baris dibagi seimbang, durasi minimum &
   kecepatan baca dijaga, tanpa tumpang tindih. Semua bisa diatur di Pengaturan, lalu
   "Susun ulang" tanpa transkripsi ulang.

## Build

**GitHub Actions:** push folder ini ke repo GitHub → tab *Actions* → *Build APK* → unduh artefak
`AudioSrt-debug-apk`.

**Android Studio:** buka folder ini, tunggu Gradle sync, lalu Run.

## Pemakaian

1. Buat API key gratis di https://console.groq.com/keys, tempel di Pengaturan.
2. Pilih file (atau Bagikan dari aplikasi lain ke "Audio ke SRT").
3. Pilih bahasa — jangan pakai deteksi otomatis kalau bahasanya sudah jelas.
4. Isi "Nama & istilah khusus" dengan ejaan yang benar untuk nama orang/tempat/istilah.
5. Buat subtitle → koreksi baris yang perlu → Simpan .srt.

Catatan: audio dikirim ke server Groq. Biarkan aplikasi terbuka selama proses (layar dibuat tetap menyala).
