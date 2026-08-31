package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class WeatherService implements WeatherServiceI {

    @Value("${weather.api.key:your-api-key}")
    private String apiKey;

    @Value("${weather.api.url:https://restapi.amap.com/v3/weather/weatherInfo}")
    private String apiUrl;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WeatherService() {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 查询指定城市的天气信息
     *
     * @param city 城市名称，如 "深圳"
     * @return 格式化后的天气描述
     */
    @Override
    public String getWeather(String city) {
        try {
            log.info("🌤️ 正在查询 {} 的天气...", city);
            String response = webClient.get()
                    .uri(apiUrl + "?city={city}&key={key}", city, apiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> Mono.error(new RuntimeException("API 调用失败: " + clientResponse.statusCode())))
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                return "未获取到天气信息";
            }

            // 解析 JSON 响应
            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();

            if (!"1".equals(status)) {
                String info = root.path("info").asText();
                log.warn("天气 API 返回错误: {}", info);
                return "天气查询失败: " + info;
            }

            JsonNode lives = root.path("lives");
            if (lives.isArray() && lives.size() > 0) {
                JsonNode live = lives.get(0);
                String province = live.path("province").asText();
                String cityName = live.path("city").asText();
                String weather = live.path("weather").asText();
                String temperature = live.path("temperature").asText();
                String windDirection = live.path("winddirection").asText();
                String windPower = live.path("windpower").asText();
                String humidity = live.path("humidity").asText();

                return String.format("📍 %s %s\n🌡️ 温度：%s℃\n☁️ 天气：%s\n💨 风向：%s，风力：%s级\n💧 湿度：%s%%",
                        province, cityName, temperature, weather, windDirection, windPower, humidity);
            }

            return "未解析到天气数据";

        } catch (Exception e) {
            log.error("查询天气失败: {}", e.getMessage(), e);
            return "查询天气失败: " + e.getMessage();
        }
    }
}