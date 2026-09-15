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

    // ============================================================
    // 共享分页栏
    //
    // 四个列表页（用户管理 / 文档管理 / 意见审核 / 历史记录）都需要同一套分页，
    // 之前各写各的、行为还不一样：users 有省略号、documents 把所有页码都铺出来
    // （页数一多就撑爆）、history 压根没有。这里收敛成一份。
    //
    // 两条和之前不同的行为，都是刻意改的：
    //   1. **总页数 ≤ 1 时也渲染**（只是按钮禁用），不再整个清空 ——
    //      之前只有 4 个用户时页面上一点分页痕迹都没有，看起来像"功能没做"。
    //   2. **总是显示"共 N 条 · 第 X/Y 页"**，让人知道到底有多少数据。
    //
    // 挂在 window 上是因为 app.js 整体包在 IIFE 里，而调用方是各页面的内联脚本。
    // ============================================================
    var Pager = {
        DEFAULT_SIZES: [10, 20, 50],

        /**
         * @param {Object} opts
         * @param {string} opts.containerId 容器元素 id（会被整体重绘）
         * @param {number} opts.total       总条数
         * @param {number} opts.page        当前页（从 1 开始）
         * @param {number} opts.size        每页条数
         * @param {number[]} [opts.sizes]   可选的每页条数
         * @param {string} [opts.unit]      计数单位，默认"条"
         * @param {Function} opts.onChange  (page, size) => void
         */
        render: function (opts) {
            var box = document.getElementById(opts.containerId);
            if (!box) return;

            var total = Math.max(0, opts.total || 0);
            var size = Math.max(1, opts.size || 10);
            var totalPages = Math.max(1, Math.ceil(total / size));
            var page = Math.min(Math.max(1, opts.page || 1), totalPages);
            var unit = opts.unit || '条';
            var sizes = opts.sizes && opts.sizes.length ? opts.sizes : Pager.DEFAULT_SIZES;

            // 容器清空重绘。下面所有插进去的值都是数字或固定文案，
            // 没有来自接口的字符串，所以这里用模板串拼是安全的。
            box.innerHTML = '';
            box.className = 'pager-bar';

            var summary = document.createElement('div');
            summary.className = 'pager-summary';
            summary.textContent = '共 ' + total + ' ' + unit + ' · 第 ' + page + '/' + totalPages + ' 页';
            box.appendChild(summary);

            var ul = document.createElement('ul');
            ul.className = 'pagination pagination-sm mb-0';

            // 上一页 / 下一页
            ul.appendChild(Pager._item('上一页', page - 1, page <= 1, false, opts));
            // 页码：首尾固定，当前页附近展开，中间用省略号
            var start = Math.max(2, page - 2);
            var end = Math.min(totalPages - 1, page + 2);
            ul.appendChild(Pager._item('1', 1, false, page === 1, opts));
            if (start > 2) ul.appendChild(Pager._gap());
            for (var i = start; i <= end; i++) {
                ul.appendChild(Pager._item(String(i), i, false, i === page, opts));
            }
            if (end < totalPages - 1) ul.appendChild(Pager._gap());
            // totalPages 为 1 时上面已经渲染过第 1 页，别重复
            if (totalPages > 1) {
                ul.appendChild(Pager._item(String(totalPages), totalPages, false, page === totalPages, opts));
            }
            ul.appendChild(Pager._item('下一页', page + 1, page >= totalPages, false, opts));
            box.appendChild(ul);

            // 每页条数 + 跳页
            var tools = document.createElement('div');
            tools.className = 'pager-tools';

            var sizeSel = document.createElement('select');
            sizeSel.className = 'form-select form-select-sm';
            sizeSel.setAttribute('aria-label', '每页条数');
            sizes.forEach(function (s) {
                var o = document.createElement('option');
                o.value = String(s);
                o.textContent = s;
                if (s === size) o.selected = true;
                sizeSel.appendChild(o);
            });
            sizeSel.addEventListener('change', function () {
                // 换每页条数后回到第 1 页：留在原页码很可能越界
                opts.onChange(1, parseInt(sizeSel.value, 10));
            });
            var sizeLabel1 = document.createElement('span');
            sizeLabel1.textContent = '每页';
            var sizeLabel2 = document.createElement('span');
            sizeLabel2.textContent = '条';
            tools.appendChild(sizeLabel1);
            tools.appendChild(sizeSel);
            tools.appendChild(sizeLabel2);

            if (totalPages > 1) {
                var jump = document.createElement('span');
                jump.className = 'pager-jump';
                var input = document.createElement('input');
                input.type = 'number';
                input.className = 'form-control form-control-sm';
                input.min = '1';
                input.max = String(totalPages);
                input.placeholder = '页码';
                input.setAttribute('aria-label', '跳转到第几页');
                input.addEventListener('keydown', function (e) {
                    if (e.key !== 'Enter') return;
                    var target = parseInt(input.value, 10);
                    if (!target || target < 1 || target > totalPages) {
                        input.value = '';
                        return;
                    }
                    input.value = '';
                    opts.onChange(target, size);
                });
                jump.appendChild(document.createTextNode('跳至'));
                jump.appendChild(input);
                jump.appendChild(document.createTextNode('页'));
                tools.appendChild(jump);
            }

            box.appendChild(tools);
        },

        /** 单个页码按钮。disabled 的渲染成 span，否则点了会触发无意义跳转。 */
        _item: function (label, target, disabled, active, opts) {
            var li = document.createElement('li');
            li.className = 'page-item' + (disabled ? ' disabled' : '') + (active ? ' active' : '');
            if (disabled) {
                var span = document.createElement('span');
                span.className = 'page-link';
                span.textContent = label;
                li.appendChild(span);
            } else {
                var a = document.createElement('a');
                a.className = 'page-link';
                a.href = '#';
                a.textContent = label;
                a.addEventListener('click', function (e) {
                    e.preventDefault();
                    opts.onChange(target, opts.size);
                });
                li.appendChild(a);
            }
            return li;
        },

        _gap: function () {
            var li = document.createElement('li');
            li.className = 'page-item disabled';
            var span = document.createElement('span');
            span.className = 'page-link';
            span.textContent = '…';
            li.appendChild(span);
            return li;
        }
    };

    window.Pager = Pager;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
