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

    /**
     * 全局 401 处理：token 失效时把用户送回登录页。
     *
     * <p>在此之前**没有任何地方处理 401** —— 每个页面的 fetch 拦截器只负责加
     * Authorization 头。token 过期后用户会卡在页面上，每个请求都失败，而
     * localStorage 里 token 还在，连刷新都不会跳转，只能自己手动去 /login。
     * token 有效期改成 12 小时之后这种情况会更常遇到。
     *
     * <p>包在 window.fetch 外面是可行的：各页面的拦截器是在本文件之后才捕获
     * window.fetch 的，所以调用链是「页面拦截器 → 这里 → 原生 fetch」，
     * 响应会先经过这里。顺序反过来也成立（谁在最外层谁先看到响应），不用担心加载顺序。
     */
    function installUnauthorizedHandler() {
        var originalFetch = window.fetch;
        window.fetch = function () {
            var url = arguments[0];
            return originalFetch.apply(this, arguments).then(function (response) {
                if (response.status !== 401) {
                    return response;
                }
                // 排除 /api/auth/**：那里的 401 是「用户名或密码错误」，
                // 是业务结果而不是登录过期。不排除的话，在登录页输错密码会触发
                // 强制跳转，错误提示一闪就没了。
                if (typeof url === 'string' && url.indexOf('/api/auth/') !== -1) {
                    return response;
                }
                // 已经在登录页就不要再跳一次，避免自循环
                if (window.location.pathname === '/login') {
                    return response;
                }
                localStorage.clear();
                window.location.href = '/login';
                return response;
            });
        };
    }

    installUnauthorizedHandler();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
