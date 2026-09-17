// utils/api.js - 统一的 wx.request 封装
//
// 做三件事：
// 1. 自动加 Authorization: Bearer 头（除登录接口外）。
// 2. 解包后端的统一信封 { success, errCode, errMessage, data }：
//    - success:true  → resolve(data)
//    - success:false → reject({ errCode, errMessage })（业务失败仍是 HTTP 200）
//    - HTTP 401      → 清登录态、跳登录页（token 过期）
//    - HTTP 5xx / 网络错误 → reject({ errMessage })
// 3. 状态码如实处理：不要只看 status、也不要只看 success，两者都要看（与网页端约定一致）。
//
// 不在本封装里处理：
//   - SSE 流（/api/rag/chat/stream）—— wx.request 没有 EventSource，第一阶段用非流式。
//   - 文件下载（/api/documents/download/{id}）—— 走 wx.downloadFile。
//   - 文件上传（/api/documents/upload/metadata）—— 走 wx.uploadFile，但 headers 同样要带 Authorization。
const cfg = require('./config');
const auth = require('./auth');

function buildUrl(path) {
  if (/^https?:\/\//i.test(path)) return path;
  return cfg.apiBase() + path;
}

/**
 * 发起一个 JSON 请求，返回 Promise<data>。
 * @param {Object} opts
 * @param {string} opts.url       路径（相对 apiBase 或绝对 URL）
 * @param {string} [opts.method]  GET / POST / PUT / DELETE
 * @param {Object} [opts.data]    请求参数
 * @param {Object} [opts.header]  额外的请求头
 * @param {boolean} [opts.skipAuth] 不自动加 Authorization 头（登录接口用）
 * @param {function} [opts.onTask] 收到 wx.request 返回的 RequestTask，可用于 abort()
 */
function request(opts) {
  const url = buildUrl(opts.url);
  const method = (opts.method || 'GET').toUpperCase();
  const header = opts.skipAuth
    ? Object.assign({ 'Content-Type': 'application/json' }, opts.header || {})
    : auth.authHeader(opts.header);

  return new Promise((resolve, reject) => {
    const task = wx.request({
      url,
      method,
      data: opts.data,
      header,
      timeout: 30000,
      success(res) {
        const status = res.statusCode;
        // 401：token 失效 —— 清登录态、跳登录页，不再 resolve。
        if (status === 401) {
          auth.clear();
          // 用 reLaunch 而不是 redirectTo，避免 redirectTo 后用户按返回键回到受保护页。
          wx.reLaunch({ url: '/pages/login/login' });
          reject({ errCode: 401, errMessage: '登录已过期，请重新登录' });
          return;
        }
        // 403：权限不足 —— 给一句清楚的提示，不强制跳转。
        if (status === 403) {
          reject({ errCode: 403, errMessage: '权限不足' });
          return;
        }
        // 5xx：服务端故障 —— 统一文案，不暴露 stack。
        if (status >= 500) {
          reject({ errCode: status, errMessage: '服务暂时不可用，请稍后重试' });
          return;
        }

        const body = res.data || {};
        // 兼容性：万一某个接口没走统一信封（直接返回裸数据），也支持一下。
        if (typeof body !== 'object' || body === null) {
          resolve(body);
          return;
        }
        if (body.success === true) {
          resolve(body.data);
          return;
        }
        if (body.success === false) {
          reject({
            errCode: body.errCode || 'BIZ',
            errMessage: body.errMessage || '操作失败'
          });
          return;
        }
        // 既不是 true 也不是 false，说明不是统一信封 —— 当裸数据处理。
        resolve(body);
      },
      fail(err) {
        // wx.request fail：DNS、超时、断网、abort 都落在这里。
        // abort 时 errMsg 是 "request:fail abort"，给一个特殊 errCode 让调用方区分。
        const isAbort = err && err.errMsg && /abort/i.test(err.errMsg);
        reject({
          errCode: isAbort ? 'ABORTED' : 'NETWORK',
          errMessage: isAbort ? '已取消' : '网络错误，请检查网络后重试'
        });
      }
    });
    // 把 RequestTask 交给调用方，需要时可以 abort()
    if (typeof opts.onTask === 'function') opts.onTask(task);
  });
}

/** GET 请求的快捷方法。 */
function get(url, data, opts) {
  return request(Object.assign({ url, method: 'GET', data }, opts || {}));
}
function post(url, data, opts) {
  return request(Object.assign({ url, method: 'POST', data }, opts || {}));
}
function put(url, data, opts) {
  return request(Object.assign({ url, method: 'PUT', data }, opts || {}));
}
function del(url, data, opts) {
  return request(Object.assign({ url, method: 'DELETE', data }, opts || {}));
}

/**
 * 上传文件到 /api/documents/upload/metadata（multipart）。
 * @param {Object} p
 * @param {string} p.filePath  wx.chooseMessageFile / chooseImage 返回的临时路径
 * @param {Object} p.formData  对应后端 @RequestParam 的额外字段
 */
function uploadFile(p) {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: buildUrl('/api/documents/upload/metadata'),
      filePath: p.filePath,
      name: 'file',                 // 后端 @RequestParam("file")
      formData: p.formData || {},
      header: auth.authHeader(),
      timeout: 60000,
      success(res) {
        // wx.uploadFile 的 data 是字符串，需要自己 parse。
        let body = {};
        try {
          body = JSON.parse(res.data || '{}');
        } catch (e) {
          // 二进制下载或非 JSON —— 当裸数据处理
          resolve(res.data);
          return;
        }
        if (res.statusCode === 401) {
          auth.clear();
          wx.reLaunch({ url: '/pages/login/login' });
          reject({ errCode: 401, errMessage: '登录已过期' });
          return;
        }
        if (res.statusCode >= 500) {
          reject({ errCode: res.statusCode, errMessage: '服务暂时不可用' });
          return;
        }
        if (body.success === true) resolve(body.data);
        else reject({ errCode: body.errCode, errMessage: body.errMessage || '上传失败' });
      },
      fail(err) {
        reject({ errCode: 'NETWORK', errMessage: '上传失败，请检查网络' });
      }
    });
  });
}

/**
 * 下载文件（文档下载走这条）。
 * @param {string} path   后端路径，如 /api/documents/download/123
 * @returns Promise<tempFilePath>
 */
function downloadFile(path) {
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: buildUrl(path),
      header: { 'Authorization': 'Bearer ' + auth.getToken() },
      success(res) {
        if (res.statusCode === 200) resolve(res.tempFilePath);
        else if (res.statusCode === 401) {
          auth.clear();
          wx.reLaunch({ url: '/pages/login/login' });
          reject({ errMessage: '登录已过期' });
        } else {
          reject({ errMessage: '下载失败（' + res.statusCode + '）' });
        }
      },
      fail() {
        reject({ errMessage: '下载失败，请检查网络' });
      }
    });
  });
}

/** 把 reject 出来的错误转成 toast 提示，避免每个页面都写一遍。 */
function toastError(err) {
  const msg = (err && err.errMessage) || '操作失败';
  wx.showToast({ title: msg, icon: 'none', duration: 2200 });
}

module.exports = {
  request,
  get,
  post,
  put,
  del,
  uploadFile,
  downloadFile,
  toastError
};
