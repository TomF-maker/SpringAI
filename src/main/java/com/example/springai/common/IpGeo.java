package com.example.springai.common;

/**
 * IP 归属地解析结果，不可变值对象。
 *
 * <p>三个字段都允许为 null，且 **null 是有意义的**，不是错误：
 * <ul>
 *   <li>内网 / 保留地址 —— 根本不该去外呼，直接返回 {@link #UNKNOWN}；</li>
 *   <li>服务商查得到这个 IP，但库里没有它的省市（境外 IP、新分配网段）—— 这是**合法答案**，
 *       会被正常缓存，重问一次只是白烧配额；</li>
 *   <li>外呼失败 —— 同样返回 {@link #UNKNOWN}，但**不缓存**（见 AbstractIpGeoService）。</li>
 * </ul>
 * 所以调用方不能靠"是不是 UNKNOWN"反推故障，那两种情况的区别在缓存层，不在这里。
 *
 * <p>{@link #display()} 是给页面/报表直接用的合并串。刻意不提供 setter：这个对象会在线程池之间传递，
 * 可变对象在那种场景下迟早被人就地改掉，而改它的地方离读它的地方很远。
 */
public final class IpGeo {

    /** 无法解析时的唯一实例。用同一份实例，调用方可以直接 == 比较。 */
    public static final IpGeo UNKNOWN = new IpGeo(null, null, null);

    private final String country;
    private final String province;
    private final String city;

    public IpGeo(String country, String province, String city) {
        this.country = blankToNull(country);
        this.province = blankToNull(province);
        this.city = blankToNull(city);
    }

    public String getCountry() {
        return country;
    }

    public String getProvince() {
        return province;
    }

    public String getCity() {
        return city;
    }

    /** 是否至少解析出了省或市。只有国家不算解析成功（高德对境外 IP 会给空省市）。 */
    public boolean isResolved() {
        return province != null || city != null;
    }

    /** 合并展示串，如 {@code 中国 广东省 深圳市}；全空时返回 null（而不是空串，方便 SQL 判 NULL）。 */
    public String display() {
        StringBuilder sb = new StringBuilder();
        if (country != null) {
            sb.append(country);
        }
        if (province != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(province);
        }
        if (city != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(city);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public String toString() {
        return "IpGeo{" + display() + '}';
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
