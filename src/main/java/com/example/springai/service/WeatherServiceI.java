package com.example.springai.service;

public interface WeatherServiceI {

    /**
     * 查询指定城市的天气信息
     *
     * @param city 城市名称，如 "深圳"
     * @return 格式化后的天气描述
     */
    String getWeather(String city);
}
