package com.clele.parts.config;

import java.io.IOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled React/Vite frontend (copied into classpath:/static/ by the Maven build)
 * from the same web container as the API. Static files are served directly; any other path
 * that is not an API call falls back to index.html so client-side (BrowserRouter) routes work
 * on deep links and refreshes.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Let the API (and other backend routes) 404 normally instead of returning the SPA shell.
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        // SPA fallback: hand the client router its entry point.
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
