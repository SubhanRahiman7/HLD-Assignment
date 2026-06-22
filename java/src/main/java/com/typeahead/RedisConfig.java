package com.typeahead;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@ConditionalOnExpression("'${spring.data.redis.url:}' != ''")
public class RedisConfig {

 @Value("${spring.data.redis.url:redis://localhost:6379}")
 private String redisUrl;

 @Bean
 public RedisConnectionFactory redisConnectionFactory() {
 RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
 String url = redisUrl.replace("redis://", "");
 String host = url.contains(":") ? url.substring(0, url.lastIndexOf(":")) : url;
 int port = url.contains(":") ? Integer.parseInt(url.substring(url.lastIndexOf(":") + 1)) : 6379;
 cfg.setHostName(host);
 cfg.setPort(port);
 return new LettuceConnectionFactory(cfg);
 }

 @Bean
 public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory cf) {
 RedisTemplate<String, String> t = new RedisTemplate<>();
 t.setConnectionFactory(cf);
 t.setKeySerializer(new StringRedisSerializer());
 t.setValueSerializer(new StringRedisSerializer());
 t.setHashKeySerializer(new StringRedisSerializer());
 t.setHashValueSerializer(new StringRedisSerializer());
 t.afterPropertiesSet();
 return t;
 }
}