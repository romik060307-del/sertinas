# RKM Server + RKM Chat — Android API 19+

Paket ini berisi APK-project Android untuk tablet server dan halaman web chat yang disajikan langsung dari tablet. Server ringan menggunakan Java HTTP pada port **8080**; tidak memerlukan Node.js, VPS, database cloud, atau server kedua. RK Tunnel Lite yang sudah ada dapat memublikasikan port 8080.

## Fitur yang disiapkan
- Daftar dan masuk akun dengan nomor telepon dan kata sandi (tanpa OTP).
- Profil per akun: nama, info, foto profil.
- Chat pribadi antarnomor yang terdaftar; pesan tersimpan pada tablet.
- Kirim gambar terkompresi (batas server sekitar 180 KB per gambar).
- Edit/hapus pesan teks yang dikirim oleh akun sendiri.
- Status teks yang kedaluwarsa setelah 24 jam.
- Sinyal panggilan suara WebRTC dan riwayat panggilan lokal pada browser/perangkat.
- Kontak dan nama simpanan per perangkat/browser; data pesan dan akun disimpan oleh aplikasi server di tablet.
- Halaman web responsif untuk HP dan desktop, disajikan di `/` pada port 8080.

## Bangun APK melalui GitHub Actions

**Cara paling sesuai untuk repository RK Server yang sudah kamu punya:**
1. Unggah ZIP ini ke root repository GitHub dengan nama `RKM-Server-Source.zip` (jika workflow lama hanya mengenali nama lama, gunakan `RK-Server-Source.zip`).
2. Unduh file workflow `RKM-Server-build-apk.yml` yang disediakan bersama paket ini. Di repository, ganti file `.github/workflows/build-apk.yml` dengan isi file tersebut. Workflow di ZIP yang masih berada di dalam folder `rk-server-android/` sendiri belum aktif sampai dipasang pada folder `.github/workflows` di root repository.
3. Commit perubahan, lalu buka **Actions** → **Build RKM Server APK** → **Run workflow**.
4. Jika build hijau, buka hasil run → bagian **Artifacts** → unduh `RKM-Server-APK`.
5. Ekstrak artifact dan instal `app-debug.apk` pada tablet Android. Proyek menargetkan minimum Android 4.4 / API 19.

Alternatif: ekstrak folder `rk-server-android` dan unggah seluruh **isinya** ke root repository; pastikan folder `.github/workflows` berada langsung di root. Kemudian jalankan workflow.

## Cara menggunakan
1. Sambungkan tablet ke Wi-Fi lalu buka aplikasi **RKM Server**.
2. Tekan **MULAI SERVER** dan pastikan alamat lokal port 8080 terbuka dari HP lain pada Wi-Fi yang sama.
3. Jalankan **RK Tunnel Lite** seperti biasa, arahkan ke `127.0.0.1:8080` atau alamat lokal port 8080 sesuai setelan tunnel kamu. Jangan ubah konfigurasi tunnel yang sudah terbukti bekerja.
4. Buka tautan HTTPS publik `trycloudflare.com` terbaru dari browser HP. Halaman web chat datang dari server tablet yang sama; tidak perlu hosting web terpisah.
5. Setiap orang pilih **Buat akun**, daftarkan nomor 8–15 angka, nama dan kata sandi minimal 6 karakter. Orang kedua membuat akun memakai nomor berbeda. Berikutnya gunakan **Masuk** memakai nomor dan kata sandi yang sama. Jangan mendaftar ulang nomor yang sudah ada.
6. Tambahkan teman dengan nomor yang sudah terdaftar. Ketuk kontak untuk chat. Gunakan tanda `+` pada ruang chat untuk memilih dan mengirim gambar.

## Catatan penting
- Ini adalah prototipe ringan; bukan pengganti WhatsApp dan belum memiliki enkripsi end-to-end, OTP, push notification saat browser ditutup, atau TURN relay khusus. Panggilan suara membutuhkan browser modern dengan HTTPS, izin mikrofon, koneksi WebRTC yang bisa saling terhubung, dan kedua pengguna membuka web saat panggilan. Jaringan tertentu memerlukan TURN sehingga panggilan mungkin gagal walau chat teks bekerja.
- Status versi ini berupa teks; gambar didukung untuk pesan chat dan foto profil.
- Akun/pesan/media/status tersimpan di penyimpanan privat aplikasi pada tablet. Menghapus data aplikasi atau uninstall dapat menghilangkan data tersebut. Simpan cadangan sebelum menghapus.
- Kata sandi di-hash di server; tetap gunakan kata sandi unik. Jangan kirim informasi sensitif: ini belum diaudit keamanan secara profesional.
- Tautan Quick Tunnel Cloudflare biasanya sementara dan bisa berubah saat tunnel dimulai ulang. Server tablet dan tunnel harus tetap aktif.
- Aku belum menjalankan build Android di emulator/perangkat dari lingkungan ini. Pastikan workflow GitHub berhasil, lalu tes dua akun menggunakan dua HP/jaringan berbeda sebelum dipakai sungguhan.
