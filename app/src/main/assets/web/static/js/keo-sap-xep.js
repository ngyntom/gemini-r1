// Kéo để sắp xếp các ô trong 1 lưới (chế độ sửa trang Thiết bị, kiểu màn hình chính iOS). Dùng chung cho
// Voicebot (templates/pages/devices.html) và loa R1 (GeminiR1 assets/web/index.html) - file giống hệt nhau.
// Pointer Events cho cả chuột lẫn cảm ứng: kéo-thả HTML5 (draggable) không chạy với ngón tay trên iOS.
//
// ganKeoSapXep(luoi, { chon, boQua, xong })
//   chon  - selector các ô con trực tiếp của lưới (vd ".ha-dash-card")
//   boQua - selector phần tử bấm vào thì KHÔNG kéo (vd nút xoá trên ô)
//   xong(dsO) - gọi sau khi thả, dsO = các ô theo thứ tự mới (chỉ khi thứ tự thật sự đổi)
// Chạm rồi thả mà chưa nhích quá 8 px = bấm thường (click vẫn chạy). Lưới cần "touch-action: none" lúc sửa
// (CSS .ha-dash-dang-sua) để trình duyệt không giành cú vuốt làm cuộn trang.
(function () {
  window.ganKeoSapXep = function (luoi, opts) {
    if (luoi.dataset.keoSapXep) return;            // lưới vẽ lại mỗi lần, mỗi phần tử chỉ gắn 1 lần
    luoi.dataset.keoSapXep = '1';
    const cacO = () => [...luoi.children].filter((x) => x.matches(opts.chon));

    luoi.addEventListener('pointerdown', (e) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      const o = e.target.closest(opts.chon);
      if (!o || o.parentElement !== luoi || (opts.boQua && e.target.closest(opts.boQua))) return;
      const x0 = e.clientX, y0 = e.clientY, thuTuDau = cacO();
      let dangKeo = false, lechX = 0, lechY = 0;
      // Vị trí "thật" của ô trong lưới (bỏ phần dịch theo ngón tay) - đổi chỗ trong DOM thì vị trí này đổi theo
      const viTriGoc = () => { const t = o.style.transform; o.style.transform = ''; const r = o.getBoundingClientRect(); o.style.transform = t; return r; };
      const theoNgon = (ev) => { const g = viTriGoc(); o.style.transform = `translate(${ev.clientX - lechX - g.left}px, ${ev.clientY - lechY - g.top}px)`; };

      const diChuyen = (ev) => {
        if (!dangKeo) {
          if (Math.hypot(ev.clientX - x0, ev.clientY - y0) < 8) return;
          dangKeo = true;
          const g = viTriGoc();
          lechX = x0 - g.left;
          lechY = y0 - g.top;
          o.classList.add('dang-keo');
          try { o.setPointerCapture(e.pointerId); } catch (err) { /* ô đã bị vẽ lại */ }
          if (typeof rungNhe === 'function') rungNhe();
        }
        ev.preventDefault();
        theoNgon(ev);
        // Ô đang kéo có pointer-events:none (CSS .dang-keo) nên elementFromPoint thấy ô nằm dưới ngón tay
        const duoi = document.elementFromPoint(ev.clientX, ev.clientY);
        const dich = duoi && duoi.closest(opts.chon);
        if (dich && dich !== o && dich.parentElement === luoi) {
          const ds = cacO();
          if (ds.indexOf(o) < ds.indexOf(dich)) dich.after(o); else dich.before(o);
          theoNgon(ev);
          if (typeof rungNhe === 'function') rungNhe();
        }
      };
      const tha = () => {
        document.removeEventListener('pointermove', diChuyen);
        document.removeEventListener('pointerup', tha);
        document.removeEventListener('pointercancel', tha);
        if (!dangKeo) return;
        o.style.transform = '';
        o.classList.remove('dang-keo');
        // Nuốt cú click trình duyệt sinh ra ngay sau khi thả - không thì mở luôn bảng sửa của ô
        const nuot = (ev) => { ev.stopPropagation(); ev.preventDefault(); };
        o.addEventListener('click', nuot, { capture: true, once: true });
        setTimeout(() => o.removeEventListener('click', nuot, { capture: true }), 400);
        const moi = cacO();
        if (moi.some((x, i) => x !== thuTuDau[i])) opts.xong(moi);
      };
      document.addEventListener('pointermove', diChuyen, { passive: false });
      document.addEventListener('pointerup', tha);
      document.addEventListener('pointercancel', tha);
    });
  };
})();
