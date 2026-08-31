package com.example.springai.tool;

import com.example.springai.service.WeatherServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WeatherTool {

    @Autowired
    private WeatherServiceI weatherServiceI;

    @Tool(name = "getWeather", description = "查询指定城市的实时天气信息，包括温度、天气状况、风力、湿度等")
    public String getWeather(
            @ToolParam(description = "城市名称，如：深圳、北京、上海") String city) {
        log.info("查询指定城市的实时天气信息，包括温度、天气状况、风力、湿度等——city:{}", city);
        return weatherServiceI.getWeather(city);
    }
}