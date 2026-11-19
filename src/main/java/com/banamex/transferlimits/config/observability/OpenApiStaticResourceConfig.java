package com.banamex.transferlimits.config.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the contract-first OpenAPI document ({@code classpath:/openapi/}) under {@code
 * /openapi/**} so Swagger UI can render it ({@code springdoc.swagger-ui.url}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiStaticResourceConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/openapi/**").addResourceLocations("classpath:/openapi/");
  }
}
