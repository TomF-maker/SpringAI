package com.example.springai.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(
            @Value("${spring.ai.vectorstore.qdrant.host:124.221.251.183}") String host,
            @Value("${spring.ai.vectorstore.qdrant.port:6334}") int port) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build()
        );
    }
}