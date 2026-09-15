package com.example.springai.service;

import com.example.springai.common.IpGeo;

/**
 * IP 归属地解析。
 *
 * <p><b>契约：永不抛异常、永不返回 null。</b>任何失败都返回 {@link IpGeo#UNKNOWN}。
 * 这条契约是硬性的 —— 两个调用方一个在提问埋点里、一个在登录流程里，
 * 都不该因为一个第三方定位服务挂掉而受影响，尤其登录那条路会把所有人挡在门外。
 *
 * <p>实现按服务商切换，见 {@code app.geo.provider}。
 */
public interface IpGeoServiceI {

    /**
     * 解析 IP 归属地。阻塞式，实现内部已带缓存 / 限流 / 熔断。
     *
     * @param ip 完整客户端 IP，可为 null（返回 UNKNOWN）
     * @return 解析结果；失败或无意义时为 {@link IpGeo#UNKNOWN}，不会为 null
     */
    IpGeo lookup(String ip);
}
