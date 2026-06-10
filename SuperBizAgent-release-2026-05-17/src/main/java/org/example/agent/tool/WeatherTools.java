package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 实时天气查询工具
 * 使用 wttr.in 免费天气 API，无需 API Key
 * 支持按城市名称（中/英文）查询实时天气信息
 */
@Component
public class WeatherTools {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_WEATHER = "queryWeather";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private OkHttpClient httpClient;

    private static final String WTTR_BASE_URL = "https://wttr.in";

    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(15))
                .build();
        logger.info("✅ WeatherTools 初始化成功，使用 wttr.in 免费天气 API");
    }

    /**
     * 查询指定城市的实时天气信息
     * 调用 wttr.in API 获取天气数据，包含温度、湿度、风速、天气状况等
     *
     * @param city 城市名称，支持中文（如"北京"）或英文（如"Beijing"）
     * @return 天气信息的 JSON 字符串
     */
    @Tool(description = "Query real-time weather information for a specified city. " +
            "This tool fetches current weather data including temperature, humidity, wind speed, " +
            "and weather conditions. Supports city names in both Chinese (e.g. 北京) and English (e.g. Beijing). " +
            "Use this tool when the user asks about weather, temperature, or climate conditions for any city.")
    public String queryWeather(String city) {
        logger.info("开始查询天气 - 城市: {}", city);

        if (city == null || city.trim().isEmpty()) {
            return buildErrorResponse("城市名称不能为空");
        }

        try {
            String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8);
            String apiUrl = WTTR_BASE_URL + "/" + encodedCity + "?format=j1&lang=zh";

            logger.debug("请求天气 API: {}", apiUrl);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .get()
                    .header("User-Agent", "SuperBizAgent/1.0")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = String.format("天气 API 请求失败: HTTP %d", response.code());
                    logger.warn(errorMsg);
                    return buildErrorResponse(errorMsg);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                logger.debug("天气 API 响应长度: {}", responseBody.length());

                // 解析 wttr.in JSON 响应
                WttrResponse wttrResponse = objectMapper.readValue(responseBody, WttrResponse.class);

                // 提取简化的天气数据
                SimplifiedWeather weather = extractWeather(city.trim(), wttrResponse);

                String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(weather);
                logger.info("天气查询成功 - 城市: {}, 温度: {}°C", city, weather.getTemperature());

                return jsonResult;
            }
        } catch (Exception e) {
            logger.error("查询天气失败 - 城市: {}", city, e);
            return buildErrorResponse("查询失败: " + e.getMessage());
        }
    }

    /**
     * 从 wttr.in 原始响应中提取简化的天气数据
     */
    private SimplifiedWeather extractWeather(String city, WttrResponse wttrResponse) {
        SimplifiedWeather weather = new SimplifiedWeather();
        weather.setSuccess(true);
        weather.setCity(city);

        try {
            if (wttrResponse == null || wttrResponse.getCurrentCondition() == null
                    || wttrResponse.getCurrentCondition().isEmpty()) {
                weather.setSuccess(false);
                weather.setMessage("城市未找到，请检查城市名称是否正确");
                return weather;
            }

            // 当前天气状况
            List<WttrCurrentCondition> conditions = wttrResponse.getCurrentCondition();
            WttrCurrentCondition current = conditions.get(0);

            weather.setTemperature(current.getTempC() + "°C");
            weather.setFeelsLike(current.getFeelsLikeC() + "°C");
            weather.setHumidity(current.getHumidity() + "%");
            weather.setWeatherDesc(current.getWeatherDesc().stream()
                    .map(WttrValue::getValue)
                    .findFirst().orElse("未知"));
            weather.setWindSpeed(current.getWindSpeedKmph() + " km/h");
            weather.setWindDirection(current.getWindDir16Point());
            weather.setVisibility(current.getVisibility() + " km");
            weather.setPressure(current.getPressure() + " hPa");
            weather.setCloudCover(current.getCloudCover() + "%");
            weather.setUvIndex(current.getUvIndex());

            // 城市所在的最近气象站
            if (wttrResponse.getNearestArea() != null && !wttrResponse.getNearestArea().isEmpty()) {
                WttrNearestArea area = wttrResponse.getNearestArea().get(0);
                weather.setAreaName(area.getAreaName().stream()
                        .map(WttrValue::getValue)
                        .findFirst().orElse(city));
                weather.setCountry(area.getCountry().stream()
                        .map(WttrValue::getValue)
                        .findFirst().orElse(""));
            }

            // 今日预报（最高/最低温度）
            if (wttrResponse.getWeather() != null && !wttrResponse.getWeather().isEmpty()) {
                WttrWeatherDay today = wttrResponse.getWeather().get(0);
                weather.setMaxTemp(today.getMaxTempC() + "°C");
                weather.setMinTemp(today.getMinTempC() + "°C");

                // 日出日落
                if (today.getAstronomy() != null && !today.getAstronomy().isEmpty()) {
                    WttrAstronomy astro = today.getAstronomy().get(0);
                    weather.setSunrise(astro.getSunrise());
                    weather.setSunset(astro.getSunset());
                }
            }

            weather.setMessage(String.format("成功获取 %s 的实时天气数据", city));

        } catch (Exception e) {
            logger.warn("解析天气数据部分失败", e);
            weather.setSuccess(false);
            weather.setMessage("天气数据解析失败: " + e.getMessage());
        }

        return weather;
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message) {
        try {
            SimplifiedWeather weather = new SimplifiedWeather();
            weather.setSuccess(false);
            weather.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(weather);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }

    // ==================== wttr.in JSON 数据模型 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrResponse {
        @JsonProperty("current_condition")
        private List<WttrCurrentCondition> currentCondition;

        @JsonProperty("nearest_area")
        private List<WttrNearestArea> nearestArea;

        @JsonProperty("weather")
        private List<WttrWeatherDay> weather;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrCurrentCondition {
        @JsonProperty("temp_C")
        private String tempC;

        @JsonProperty("FeelsLikeC")
        private String feelsLikeC;

        @JsonProperty("humidity")
        private String humidity;

        @JsonProperty("weatherDesc")
        private List<WttrValue> weatherDesc;

        @JsonProperty("windspeedKmph")
        private String windSpeedKmph;

        @JsonProperty("winddir16Point")
        private String windDir16Point;

        @JsonProperty("visibility")
        private String visibility;

        @JsonProperty("pressure")
        private String pressure;

        @JsonProperty("cloudcover")
        private String cloudCover;

        @JsonProperty("uvIndex")
        private String uvIndex;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrNearestArea {
        @JsonProperty("areaName")
        private List<WttrValue> areaName;

        @JsonProperty("country")
        private List<WttrValue> country;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrWeatherDay {
        @JsonProperty("maxtempC")
        private String maxTempC;

        @JsonProperty("mintempC")
        private String minTempC;

        @JsonProperty("astronomy")
        private List<WttrAstronomy> astronomy;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrAstronomy {
        @JsonProperty("sunrise")
        private String sunrise;

        @JsonProperty("sunset")
        private String sunset;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WttrValue {
        @JsonProperty("value")
        private String value;
    }

    /**
     * 简化的天气信息输出
     */
    @Data
    public static class SimplifiedWeather {
        @JsonProperty("success")
        private boolean success;

        @JsonProperty("city")
        private String city;

        @JsonProperty("area_name")
        private String areaName;

        @JsonProperty("country")
        private String country;

        @JsonProperty("temperature")
        private String temperature;

        @JsonProperty("feels_like")
        private String feelsLike;

        @JsonProperty("max_temp")
        private String maxTemp;

        @JsonProperty("min_temp")
        private String minTemp;

        @JsonProperty("humidity")
        private String humidity;

        @JsonProperty("weather_desc")
        private String weatherDesc;

        @JsonProperty("wind_speed")
        private String windSpeed;

        @JsonProperty("wind_direction")
        private String windDirection;

        @JsonProperty("visibility")
        private String visibility;

        @JsonProperty("pressure")
        private String pressure;

        @JsonProperty("cloud_cover")
        private String cloudCover;

        @JsonProperty("uv_index")
        private String uvIndex;

        @JsonProperty("sunrise")
        private String sunrise;

        @JsonProperty("sunset")
        private String sunset;

        @JsonProperty("message")
        private String message;
    }
}
