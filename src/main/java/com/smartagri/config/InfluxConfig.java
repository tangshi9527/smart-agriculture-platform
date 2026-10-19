package com.smartagri.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * InfluxDB 2.x 客户端配置。
 */
@Configuration
public class InfluxConfig {

    @Value("${influx.url:http://localhost:8086}")
    private String url;
    @Value("${influx.token:my-token}")
    private String token;
    @Value("${influx.org:smart-agri}")
    private String org;
    @Value("${influx.bucket:agri-raw}")
    private String bucket;

    @Bean
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
    }
}
