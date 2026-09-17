package com.aesoftwaresolutions.solid.iam;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final OrgAccessInterceptor orgAccess;

    WebConfig(OrgAccessInterceptor orgAccess) {
        this.orgAccess = orgAccess;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(orgAccess).addPathPatterns("/api/v1/orgs/*/**");
    }
}
