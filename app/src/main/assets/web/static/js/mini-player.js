// Chép từ static/js/mini-player.js của Voicebot trên Pi (đổi chữ "Loa Pi" thành "Loa này").
// Mini player ôm sát tabbar (markup trong templates/index.html, CSS trong
// app.css "Mini player") + trình phát MỞ RỘNG (F7 Sheet #mini-player-sheet,
// vuốt lên/chạm để mở). Nhận trạng thái từ trang Nhạc: SSE loa Pi (sự kiện
// "nhac-pi") hoặc bài đang phát trên máy ("nhac-tren-may"). Mọi nút gửi lệnh
// "nhac-lenh" cho trang Nhạc xử lý - trang đó biết đang phát trên Loa này hay
// Máy này (togglePlayPause/skipMusic/stopMusic/seekMusic trong music.html).
(function () {
  const el = document.getElementById('mini-player');
  if (!el) return;
  const $ = (id) => document.getElementById(id);

  let trangThai = { state: 'stopped' };
  let mocDongBo = 0;          // Date.now() lúc nhận trạng thái gần nhất
  // Đang phát trên MÁY NÀY - ưu tiên hiện bài đó thay cho trạng thái loa Pi.
  let trenMay = null;
  let trangThaiPi = { state: 'stopped' };
  let dangKeoTua = false;
  let keoTuaLuc = 0;           // lần cuối có cử động kéo (thoát kẹt "đang kéo" sau 3s)
  let dangKeoAmLuong = false;
  let sheet = null;
  let henDongSheet = null;
  let kenhBaiDangPhat = '';     // tên kênh bài đang phát (từ trang Nhạc)
  // Tự ẩn mini player: mở lại app (đã thoát hẳn) mà nhạc chỉ đang TẠM DỪNG thì
  // không hiện - phải phát trong phiên này mới hiện; tạm dừng quá AN_SAU_MS
  // cũng ẩn. Phát lại là hiện ngay.
  const AN_SAU_MS = 5 * 60 * 1000;
  let daPhatTrongPhien = false;
  let tamDungLuc = 0;

  function fmt(s) {
    s = Math.max(0, Math.round(s || 0));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }

  function giayDaPhat() {
    const e = trangThai.elapsed || 0;
    return trangThai.state === 'playing' && !trangThai.dangTai ? e + (Date.now() - mocDongBo) / 1000 : e;
  }

  function lenh(ten, them) {
    window.dispatchEvent(new CustomEvent('nhac-lenh', { detail: Object.assign({ lenh: ten }, them || {}) }));
  }

  // Tabbar bị ẩn (máy tính dùng thanh bên - html.device-desktop) thì không hiện
  // (desktop đã có thanh phát kiểu Tidal trong trang Nhạc).
  function tabbarDangHien() {
    const tb = $('mobile-tabbar');
    return !!tb && getComputedStyle(tb).display !== 'none';
  }

  function conDangDung() {
    const st = trangThai.state;
    if (st === 'playing' || trangThai.dangTai || trangThai.dangChuyen) return true;
    return st === 'paused' && daPhatTrongPhien && Date.now() - tamDungLuc < AN_SAU_MS;
  }

  function capNhatHien() {
    const hien = trangThai.state !== 'stopped' && conDangDung() && tabbarDangHien();
    el.style.display = hien ? '' : 'none';
    // Báo CSS gộp khung kính tabbar + chừa chỗ cuối trang (xem app.css).
    document.documentElement.classList.toggle('co-mini-player', hien);
    // Chỉ đóng trình phát toàn màn hình khi "dừng" KÉO DÀI (>1.5s) - lúc đổi
    // thiết bị có tích tắc trạng thái trung gian "dừng" làm đóng nhầm (đã gặp).
    clearTimeout(henDongSheet);
    if (!hien && sheet && sheet.opened) {
      henDongSheet = setTimeout(() => {
        if (trangThai.state === 'stopped' && sheet && sheet.opened) sheet.close();
      }, 1500);
    }
  }

  function veTienTrinh() {
    const d = trangThai.duration;
    const da = giayDaPhat();
    $('mini-player-tien-trinh').style.width = (d > 0 ? Math.min(100, (da / d) * 100) : 0) + '%';
    const tai = typeof trangThai.buffered === 'number' && d > 0 ? Math.min(100, (trangThai.buffered / d) * 100) : 0;
    $('mini-player-da-tai').style.width = tai + '%';
    $('mps-tua').style.setProperty('--tai', tai + '%');
    const nhan = trangThai.dangChuyen ? 'Đang chuyển sang ' + (trangThai.noi || 'Loa này')
      : trangThai.dangTai ? 'Đang tải…'
      : (trangThai.state === 'playing' ? 'Đang phát' : 'Tạm dừng');
    $('mini-player-phu').textContent = d > 0 ? nhan + ' · ' + fmt(da) + ' / ' + fmt(d) : nhan;
    if (dangKeoTua && Date.now() - keoTuaLuc > 3000) dangKeoTua = false;
    if (!dangKeoTua) {
      const tua = $('mps-tua');
      tua.max = d > 0 ? Math.round(d) : 100;
      tua.value = Math.round(da);
      toPhanDaPhat(tua);
      $('mps-da-phat').textContent = fmt(da);
    }
    // Kiểu Tidal: bên phải là thời gian CÒN LẠI (-m:ss).
    $('mps-tong').textContent = d > 0 ? '-' + fmt(Math.max(0, d - da)) : '--:--';
  }

  // Tô phần đã nghe của thanh tiến trình mảnh (CSS #mps-tua dùng --pt).
  function toPhanDaPhat(tua) {
    const max = +tua.max || 100;
    tua.style.setProperty('--pt', Math.min(100, (+tua.value / max) * 100) + '%');
  }

  function datAnh(img, id) {
    // mqdefault (16:9, không viền đen như hqdefault 4:3) - CSS cắt vuông giữa ảnh.
    const src = id ? 'https://i.ytimg.com/vi/' + encodeURIComponent(id) + '/mqdefault.jpg' : '';
    if (img.dataset.src === src) return;
    img.dataset.src = src;
    img.style.display = 'none';
    if (src) {
      img.onload = () => { img.style.display = ''; };
      img.src = src;
    } else {
      img.removeAttribute('src');
    }
  }

  function apDung(data) {
    if ((data && data.id) !== trangThai.id) dangKeoTua = false;     // bài mới: thanh chạy lại từ đầu
    trangThai = data || { state: 'stopped' };
    mocDongBo = Date.now();
    if (trangThai.state === 'playing' || trangThai.dangTai || trangThai.dangChuyen) { daPhatTrongPhien = true; tamDungLuc = 0; }
    else if (trangThai.state === 'paused') { if (!tamDungLuc) tamDungLuc = Date.now(); }
    else tamDungLuc = 0;
    const choi = trangThai.state === 'playing';
    $('mini-player-ten').textContent = trangThai.title || '';
    $('mini-player-playpause-icon').textContent = choi ? 'pause_fill' : 'play_fill';
    datAnh(el.querySelector('.mini-player-anh img'), trangThai.id);
    // Trình phát mở rộng
    $('mps-ten').textContent = trangThai.title || '';
    // Dòng dưới tên bài = tên kênh (trang Nhạc báo qua "nhac-tim"), chưa có
    // thì trạng thái; đầu sheet ghi nơi phát (kiểu "PLAYING FROM" của Tidal).
    $('mps-phu').textContent = trangThai.dangChuyen ? 'Đang chuyển sang ' + (trangThai.noi || 'Loa này')
      : trangThai.dangTai ? 'Đang tải…'
      : (kenhBaiDangPhat || (choi ? 'Đang phát' : 'Tạm dừng'));
    $('mps-nguon').textContent = trangThai.dangChuyen ? 'Đang chuyển sang ' + (trangThai.noi || 'Loa này')
      : (trangThai.trenMay ? (trangThai.tenMay || 'Máy này') : (trangThai.noi_phat || 'Loa này'));
    $('mps-phat-icon').textContent = choi ? 'pause_fill' : 'play_fill';
    $('mps-phat-icon').classList.toggle('icon-play-bu', !choi);
    datAnh($('mps-anh'), trangThai.id);
    nhuomNen(trangThai.id);
    $('mps-noi-phat').classList.toggle('dang-cast', !trangThai.trenMay || (trangThai.tenMay && trangThai.tenMay !== 'Máy này'));
    $('mps-noi-phat-chu').textContent = trangThai.trenMay ? (trangThai.tenMay || 'Máy này') : (trangThai.noi_phat || 'Loa này');
    // Âm lượng chỉ dành cho LOA PI (trên iPhone chỉnh bằng phím cứng).
    $('mps-am-luong-nut').style.display = trangThai.trenMay ? 'none' : '';
    if (typeof trangThaiPi.volume === 'number') hienAmLuong(trangThaiPi.volume, true);
    veTienTrinh();
    capNhatHien();
  }

  // Nền trình phát mở rộng nhuộm theo màu chủ đạo của ảnh bìa (kiểu Tidal):
  // vẽ ảnh nhỏ 24x24 lên canvas (i.ytimg.com cho CORS "*"), lấy trung bình
  // có trọng số theo độ đậm màu, hạ độ sáng cho chữ trắng luôn đọc rõ. URL có
  // "?mau" để không đụng bản ảnh thường đã tải (khác chế độ CORS).
  let idNen = null;
  function nhuomNen(id) {
    if (id === idNen) return;
    idNen = id;
    const sh = $('mini-player-sheet');
    if (!id) { sh.style.removeProperty('--mps-nen'); return; }
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      if (idNen !== id) return;
      try {
        const cv = document.createElement('canvas');
        cv.width = cv.height = 24;
        const x = cv.getContext('2d');
        x.drawImage(img, 0, 0, 24, 24);
        const d = x.getImageData(0, 0, 24, 24).data;
        let r = 0, g = 0, b = 0, w = 0;
        for (let k = 0; k < d.length; k += 4) {
          const mx = Math.max(d[k], d[k + 1], d[k + 2]), mn = Math.min(d[k], d[k + 1], d[k + 2]);
          const tr = 0.15 + (mx - mn) / 255;           // màu đậm (bão hoà) nặng ký hơn xám
          r += d[k] * tr; g += d[k + 1] * tr; b += d[k + 2] * tr; w += tr;
        }
        r /= w; g /= w; b /= w;
        // Đổi sang HSL, giữ sắc độ, ép độ sáng 24-30% để chữ trắng nổi.
        const R = r / 255, G = g / 255, B = b / 255, M = Math.max(R, G, B), m = Math.min(R, G, B);
        let h = 0, sat = 0; const l = (M + m) / 2;
        if (M !== m) {
          const dd = M - m;
          sat = l > 0.5 ? dd / (2 - M - m) : dd / (M + m);
          h = M === R ? (G - B) / dd + (G < B ? 6 : 0) : M === G ? (B - R) / dd + 2 : (R - G) / dd + 4;
          h *= 60;
        }
        const ll = Math.min(30, Math.max(24, l * 100 * 0.6));
        sh.style.setProperty('--mps-nen', 'hsl(' + Math.round(h) + ' ' + Math.round(Math.min(60, sat * 100)) + '% ' + Math.round(ll) + '%)');
      } catch (e) { sh.style.removeProperty('--mps-nen'); }
    };
    img.src = 'https://i.ytimg.com/vi/' + encodeURIComponent(id) + '/mqdefault.jpg?mau';
  }

  // ---------- Mini player: chạm/vuốt lên = mở trình phát mở rộng ----------
  function moRong() {
    if (!sheet) {
      sheet = f7app.sheet.create({ el: '#mini-player-sheet', swipeToClose: true, backdrop: true, closeByBackdropClick: true });
    }
    sheet.open();
  }

  // Mini player nằm TRONG .toolbar-pane - tabbar iOS của F7 9 bắt chạm trên
  // pane để kéo vệt sáng giữa các tab, có thể nuốt mất chạm ở đây. Chặn sự
  // kiện BẮT ĐẦU chạm lan lên tabbar (click vẫn bắn bình thường).
  let diemDauY = null;
  ['touchstart', 'pointerdown', 'mousedown'].forEach((ten) => {
    el.addEventListener(ten, (e) => {
      e.stopPropagation();
      if (ten !== 'mousedown') diemDauY = (e.touches ? e.touches[0] : e).clientY;
    }, { passive: true });
  });
  el.addEventListener('touchmove', (e) => {
    if (diemDauY == null) return;
    if (diemDauY - e.touches[0].clientY > 30) {     // vuốt LÊN > 30px
      diemDauY = null;
      moRong();
    }
  }, { passive: true });
  el.addEventListener('touchend', () => { diemDauY = null; }, { passive: true });

  $('mini-player-mo').addEventListener('click', (e) => { e.preventDefault(); moRong(); });
  $('mini-player-playpause').addEventListener('click', (e) => { e.preventDefault(); rungNhe(); lenh('playpause'); });
  $('mini-player-next').addEventListener('click', (e) => { e.preventDefault(); rungNhe(); lenh('next'); });

  // ---------- Nút trong trình phát mở rộng ----------
  const bam = (id, fn) => $(id).addEventListener('click', (e) => { e.preventDefault(); rungNhe(); fn(); });
  bam('mps-phat', () => lenh('playpause'));
  bam('mps-truoc', () => lenh('back'));
  bam('mps-tiep', () => lenh('next'));
  bam('mps-dong', () => { if (sheet) sheet.close(); });
  // ⋯ = menu bài đang phát kiểu Tidal (moMenuBai của trang Nhạc, có "Dừng phát").
  bam('mps-them', () => lenh('menu', { nut: $('mps-them') }));
  bam('mps-chia-se', () => lenh('chia-se'));       // chạy NGAY trong lúc chạm (navigator.share cần)
  bam('mps-tron', () => lenh('tron'));
  window.addEventListener('nhac-tron', (e) => $('mps-tron').classList.toggle('dang-bat', !!e.detail));
  window.addEventListener('nhac-chat-luong', (e) => { $('mps-chat-luong').textContent = e.detail || ''; });
  bam('mps-noi-phat', () => lenh('noi-phat'));     // chạy NGAY trong lúc chạm (iOS cần cho play())
  bam('mps-tim', () => lenh('tim'));
  bam('mps-lap', () => lenh('lap'));
  // Đang tải bài (trang Nhạc báo) -> nút Phát/Tạm dừng hiện vòng xoay.
  window.addEventListener('nhac-dang-tai', (e) => {
    $('mini-player-playpause').classList.toggle('dang-tai', !!e.detail);
    $('mps-phat').classList.toggle('dang-tai', !!e.detail);
  });
  // Chế độ lặp do trang Nhạc giữ (doiCheDoLap) - chỉ vẽ lại icon.
  window.addEventListener('nhac-lap', (e) => {
    const m = e.detail;
    $('mps-lap').classList.toggle('dang-bat', m !== 'tat');
    $('mps-lap-icon').textContent = m === 'mot' ? 'repeat_1' : 'repeat';
    $('mps-lap').title = m === 'mot' ? 'Lặp lại 1 bài' : m === 'tat_ca' ? 'Lặp lại toàn danh sách' : 'Lặp lại: tắt';
  });

  const tua = $('mps-tua');
  // Kéo tua: đang kéo thì thanh ngừng tự cập nhật (khỏi giật dưới ngón tay),
  // thả tay thì tua tới chỗ thả. Trước đây CHỈ thoát "đang kéo" ở sự kiện
  // "change" - trong sheet (vuốt xuống để đóng) iPhone có lúc KHÔNG bắn
  // "change" vì sheet giữ thao tác vuốt -> kẹt "đang kéo" mãi: không tua,
  // đổi bài khác thanh cũng đứng im (đã gặp thật). Giờ: chặn sheet giành thao
  // tác trên thanh, thả tay ở BẤT KỲ đâu cũng tính là thả, quá 3s không kéo
  // thì tự thoát, đổi bài cũng thoát.
  function thaTua() {
    if (!dangKeoTua) return;
    dangKeoTua = false;
    lenh('seek', { giay: +tua.value });
  }
  tua.addEventListener('input', () => {
    dangKeoTua = true;
    keoTuaLuc = Date.now();
    toPhanDaPhat(tua);
    $('mps-da-phat').textContent = fmt(+tua.value);
  });
  tua.addEventListener('change', thaTua);
  ['touchstart', 'pointerdown', 'mousedown'].forEach((ten) => tua.addEventListener(ten, (e) => e.stopPropagation(), { passive: true }));
  ['pointerup', 'touchend', 'touchcancel', 'mouseup'].forEach((ten) => document.addEventListener(ten, () => setTimeout(thaTua, 0), true));

  // ---------- Âm lượng loa Pi: nút bên phải "Danh sách" -> popover thanh
  // trượt DỌC (F7 Range vertical, cùng kiểu popover ở trang Nhạc) ----------
  let volRange = null;
  let volPopover = null;
  let henAmLuong = null;
  let amLuongTruocMute = null;
  let amLuongHienTai = 50;

  function iconAmLuong(v) {
    return v <= 0 ? 'speaker_slash_fill' : v < 34 ? 'speaker_1_fill' : v < 67 ? 'speaker_2_fill' : 'speaker_3_fill';
  }

  // tuServer: giá trị đồng bộ từ Pi - bỏ qua lúc đang tự kéo (khỏi giằng co).
  function hienAmLuong(v, tuServer) {
    if (tuServer && dangKeoAmLuong) return;
    amLuongHienTai = Math.round(v || 0);
    $('mps-am-luong-icon').textContent = iconAmLuong(amLuongHienTai);
    $('mps-am-luong-so').textContent = amLuongHienTai + '%';
    $('mps-vol-so').textContent = amLuongHienTai + '%';
    $('mps-vol-mute-icon').textContent = amLuongHienTai > 0 ? 'speaker_slash_fill' : 'speaker_2_fill';
    if (volRange && volRange.value !== amLuongHienTai) volRange.setValue(amLuongHienTai);
  }

  function guiAmLuong(v) {
    clearTimeout(henAmLuong);
    henAmLuong = setTimeout(() => {
      api('/api/music/volume', { method: 'POST', body: JSON.stringify({ level: v }) }).catch((e) => baoLoi(e));
    }, 250);
  }

  function datAmLuong(v) {
    v = Math.max(0, Math.min(100, Math.round(v)));
    hienAmLuong(v);
    guiAmLuong(v);
  }

  bam('mps-am-luong-nut', () => {
    if (!volRange) {
      volRange = f7app.range.create({
        el: '#mps-vol-range', vertical: true, min: 0, max: 100, step: 1,
        value: amLuongHienTai, label: false, draggableBar: false,
        on: { change(r, v) { if (dangKeoAmLuong) { hienAmLuong(v); guiAmLuong(v); } } },
      });
      // Đang kéo tay: chặn đồng bộ từ Pi đè giá trị.
      $('mps-vol-range').addEventListener('pointerdown', () => { dangKeoAmLuong = true; });
      document.addEventListener('pointerup', () => { setTimeout(() => { dangKeoAmLuong = false; }, 800); });
    }
    if (!volPopover) {
      volPopover = f7app.popover.create({
        el: '#mps-am-luong-popover', targetEl: '#mps-am-luong-nut',
        // Range tạo lúc popover còn ẩn (kích thước 0) -> mở xong tính lại.
        on: { opened() { volRange.calcSize(); volRange.setValue(amLuongHienTai); } },
      });
    }
    volPopover.open('#mps-am-luong-nut');
  });
  bam('mps-vol-tang', () => datAmLuong(amLuongHienTai + Math.round(100 / (window.volMax || 15))));
  bam('mps-vol-giam', () => datAmLuong(amLuongHienTai - Math.round(100 / (window.volMax || 15))));
  bam('mps-vol-mute', () => {
    if (amLuongHienTai > 0) { amLuongTruocMute = amLuongHienTai; datAmLuong(0); }
    else datAmLuong(amLuongTruocMute || 50);
  });

  // Trang Nhạc báo bài đang phát có trong yêu thích hay không.
  window.addEventListener('nhac-tim', (e) => {
    const co = !!(e.detail && e.detail.co);
    kenhBaiDangPhat = (e.detail && e.detail.kenh) || '';
    if (kenhBaiDangPhat && !trangThai.dangChuyen) $('mps-phu').textContent = kenhBaiDangPhat;
    $('mps-tim-icon').textContent = co ? 'heart_fill' : 'heart';
    $('mps-tim').style.color = co ? 'var(--f7-color-red)' : '';
    $('mps-tim').style.visibility = e.detail && e.detail.coBai ? '' : 'hidden';
  });

  // ---------- Nguồn trạng thái ----------
  // Loa Pi: lấy từ SSE CỦA TRANG NHẠC (music.html phát sự kiện "nhac-pi") -
  // KHÔNG tự mở SSE riêng: WebKit iPhone chỉ cho ~6 kết nối tới cùng host.
  window.addEventListener('nhac-pi', (e) => {
    trangThaiPi = e.detail || { state: 'stopped' };
    if (!trenMay) apDung(trangThaiPi);
  });
  window.addEventListener('nhac-tren-may', (e) => {
    const st = e.detail || {};
    trenMay = st.state && st.state !== 'stopped' ? st : null;
    apDung(trenMay || trangThaiPi);
  });

  window.addEventListener('resize', capNhatHien);
  setInterval(() => { if (trangThai.state === 'playing') veTienTrinh(); }, 1000);
  setInterval(() => { if (trangThai.state === 'paused') capNhatHien(); }, 15000);   // hết hạn tạm dừng -> ẩn
})();
