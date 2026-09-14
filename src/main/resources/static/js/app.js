/* ============================================
   采购智能助手 - 共享布局脚本

   处理：当前用户显示、退出登录、管理菜单显隐、移动端抽屉开关。

   注意（两条硬约束）：
   1. 整体包在 IIFE 中。7 个页面都在顶层声明了 `const token` / `const originalFetch`，
      而经典脚本共享同一个全局词法环境 —— 这里若同样声明 const，第二个脚本会在
      实例化阶段抛 "Identifier 'token' has already been declared"，导致页面脚本
      一行都不执行。
   2. 只使用原生 DOM / localStorage，不得引用 bootstrap.*、echarts 或 Swal。
      history.html 只加载 SweetAlert2，完全不加载 Bootstrap bundle JS。
   ============================================ */
(function () {
    'use strict';

    var MOBILE_QUERY = '(max-width: 991.98px)';

    function init() {
        // 注意：body 上的 is-authed / is-admin（驱动菜单显隐）**不在这里设置**。
        // 它们由 templates/fragments/layout.html 侧边栏片段开头的内联脚本尽早写入 ——
        // app.js 在 body 末尾执行，那时菜单已经渲染完，游客会先看到完整菜单再收起。
        // 菜单显隐的判定只有那一处。

        // ---------- 当前用户 ----------
        var userEl = document.getElementById('currentUser');
        if (userEl) {
            userEl.textContent =
                localStorage.getItem('realName') || localStorage.getItem('username') || '用户';
        }

        // ---------- 退出登录 ----------
        var logoutBtn = document.getElementById('logoutBtn');
        if (logoutBtn && logoutBtn.dataset.bound !== '1') {
            logoutBtn.dataset.bound = '1';
            logoutBtn.addEventListener('click', function (e) {
                e.preventDefault();
                localStorage.clear();
                window.location.href = '/login';
            });
        }

        initDrawer();
    }

    function initDrawer() {
        var sidebar = document.getElementById('sidebar');
        var toggle = document.getElementById('drawerToggle');
        var backdrop = document.getElementById('sidebarBackdrop');
        if (!sidebar || !toggle || !backdrop) return;

        function setOpen(open) {
            document.body.classList.toggle('drawer-open', open);
            toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
        }

        toggle.addEventListener('click', function () {
            setOpen(!document.body.classList.contains('drawer-open'));
        });

        backdrop.addEventListener('click', function () { setOpen(false); });

        // 点菜单项后先收起抽屉（随即发生页面跳转）
        sidebar.addEventListener('click', function (e) {
            if (e.target && e.target.closest && e.target.closest('a.nav-link')) {
                setOpen(false);
            }
        });

        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') setOpen(false);
        });

        // 跨断点复位：从窄屏拉宽到桌面时清掉残留的 drawer-open，
        // 否则滚动锁（在 media query 内）和 aria-expanded 会不一致。
        var mq = window.matchMedia(MOBILE_QUERY);
        var onChange = function (e) { if (!e.matches) setOpen(false); };
        if (mq.addEventListener) {
            mq.addEventListener('change', onChange);
        } else if (mq.addListener) {
            mq.addListener(onChange);   // 旧版 Safari
        }

        setOpen(false);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
