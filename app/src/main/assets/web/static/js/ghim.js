// Giữ navbar / tabbar đứng đúng chỗ khi bàn phím ảo mở (iOS) - chép từ static/js/common.js của Voicebot
// trên Pi (ghimNavbarDungYen / boGhimNavbar / vongLapGhimNavbar). Khi focus 1 ô nhập, WebKit iOS tạm bỏ
// position:fixed và tự cuộn CẢ tài liệu (thật ra là <body>, F7 đặt body cuộn) để lộ ô đang gõ -> navbar,
// tabbar trôi lên lưng chừng màn hình. Chỉ cần dịch (translate3d) chúng ngược lại đúng khoảng đã cuộn.
// Khung nhập chat của loa là .chat-bar (Pi dùng .messagebar): bàn phím mở thì bỏ khoảng chừa cho tabbar.
(function () {
  const CAC_PHAN_TU_CAN_GHIM = '.navbar, .tabbar';
  const CHIEU_CAO_MAN_HINH_GOC = window.innerHeight; // đo lúc tải trang, chắc chắn chưa có bàn phím
  let dangCoInputFocus = false, ghimRafId = null, doLechDaVe = null, barBottomGoc = null, barDichDaVe = null;

  function ghimNavbarDungYen() {
    const doLech = document.body.scrollTop || document.documentElement.scrollTop || window.scrollY || 0;
    if (doLech !== doLechDaVe) {
      doLechDaVe = doLech;
      // translate3d (không phải translateY) để giữ lớp GPU sẵn có của F7, tránh chớp / nảy
      document.querySelectorAll(CAC_PHAN_TU_CAN_GHIM).forEach((el) => {
        el.style.transform = doLech ? ('translate3d(0,' + doLech + 'px,0)') : '';
      });
    }
    const bar = document.querySelector('#view-chat .chat-bar');
    if (bar) {
      if (barBottomGoc === null) barBottomGoc = parseFloat(getComputedStyle(bar).bottom) || 0;
      const banPhimDangMo = (CHIEU_CAO_MAN_HINH_GOC - window.innerHeight) > 40;
      const dich = banPhimDangMo ? barBottomGoc : 0; // tabbar bị bàn phím che: khoảng chừa cho nó hoá thừa
      if (dich !== barDichDaVe) {
        barDichDaVe = dich;
        bar.style.transform = dich ? ('translate3d(0,' + dich + 'px,0)') : '';
      }
    }
  }

  function boGhimNavbar() {
    doLechDaVe = barBottomGoc = barDichDaVe = null;
    document.querySelectorAll(CAC_PHAN_TU_CAN_GHIM).forEach((el) => { el.style.transform = ''; });
    const bar = document.querySelector('#view-chat .chat-bar');
    if (bar) bar.style.transform = '';
  }

  // rAF chạy liên tục lúc có ô nhập đang focus: iOS cuộn tài liệu dần dần, canh vài mốc thời gian không kịp
  function vongLapGhimNavbar() {
    if (!dangCoInputFocus) { ghimRafId = null; return; }
    ghimNavbarDungYen();
    ghimRafId = requestAnimationFrame(vongLapGhimNavbar);
  }

  document.addEventListener('focusin', (e) => {
    if (!e.target.matches('input, textarea')) return;
    dangCoInputFocus = true;
    if (ghimRafId === null) ghimRafId = requestAnimationFrame(vongLapGhimNavbar);
  });
  document.addEventListener('focusout', (e) => {
    if (!e.target.matches('input, textarea')) return;
    dangCoInputFocus = false;
    setTimeout(boGhimNavbar, 350); // chờ bàn phím đóng xong mới bỏ ghim, kẻo navbar giật lên 1 nhịp
  });
})();
