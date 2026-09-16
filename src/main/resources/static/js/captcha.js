/**
 * 图形验证码控件。登录 / 注册 / 忘记密码三个页面的发码按钮共用。
 *
 * 为什么单独抽一个文件（这三个页面本来是各写各的内联 script）：
 * 验证码是**一次性**的，所以"发送后不管成功失败都要换一张图"这条规则必须一致 ——
 * 复制三份必然漂移，而漂移的表现是"某个页面第二次点必然报图形验证码错误"，很难查。
 *
 * 用法：
 *   const captcha = createCaptcha({ imgId: 'captchaImg', inputId: 'captchaCode' });
 *   await captcha.refresh();                       // 页面加载 / 进入第二步时签发
 *   Object.assign(body, captcha.payload());        // 发码请求体里带上这两个字段
 *   await captcha.refresh();                       // 每次发完（无论成败）都换一张
 *
 * payload() 在"还没签发成功"时返回 null —— 调用方必须先拦一道并提示用户点图刷新，
 * 直接发 null 给后端只会换来一句看不懂的"图形验证码错误或已过期"。
 */
(function () {
    'use strict';

    function noop() {
    }

    function createCaptcha(opts) {
        opts = opts || {};
        var img = document.getElementById(opts.imgId);
        var input = document.getElementById(opts.inputId);
        var onError = typeof opts.onError === 'function' ? opts.onError : noop;

        // 元素找不到时退化成空实现，而不是抛异常 —— 一个页面的 id 写错
        // 不该让整个脚本挂掉（后面的倒计时、表单提交都还在同一个 <script> 里）。
        if (!img || !input) {
            console.error('[captcha] 找不到元素，图形验证码不可用：', opts);
            return {
                refresh: function () { return Promise.resolve(false); },
                payload: function () { return null; },
                reset: noop
            };
        }

        var captchaId = null;
        var loading = false;

        if (!img.getAttribute('title')) {
            img.setAttribute('title', '点击刷新');
        }
        img.addEventListener('click', function () {
            refresh();
        });

        /**
         * 换一张新图。
         *
         * 失败时**保留旧图与旧 id**，让用户还能用手里那张继续 —— 刷新失败最常见的原因是
         * 签发限流（429），此时把图清空只会让用户彻底没法发码。
         */
        function refresh() {
            if (loading) {
                return Promise.resolve(false);
            }
            loading = true;
            img.style.opacity = '0.4';

            return fetch('/api/auth/captcha', { cache: 'no-store' })
                .then(function (resp) {
                    return resp.json();
                })
                .then(function (data) {
                    var payload = data && data.data;
                    if (!data || !data.success || !payload || !payload.captchaId || !payload.image) {
                        onError((data && data.errMessage) || '验证码加载失败，请点击图片重试');
                        return false;
                    }
                    captchaId = payload.captchaId;
                    img.src = payload.image;
                    // 换图必须清空输入：用户很容易把上一张的答案再提交一次，
                    // 那种失败看起来像是"我明明输对了"。
                    input.value = '';
                    return true;
                })
                .catch(function () {
                    onError('验证码加载失败，请检查网络后点击图片重试');
                    return false;
                })
                .then(function (ok) {
                    loading = false;
                    img.style.opacity = '1';
                    return ok;
                });
        }

        return {
            refresh: refresh,
            /** 还没签发成功时返回 null，调用方必须拦一道。 */
            payload: function () {
                if (!captchaId) {
                    return null;
                }
                return { captchaId: captchaId, captchaCode: input.value.trim() };
            },
            /** 回到"未签发"状态（比如退出登录第二步）。下次要用得重新 refresh。 */
            reset: function () {
                captchaId = null;
                input.value = '';
            }
        };
    }

    window.createCaptcha = createCaptcha;
})();
