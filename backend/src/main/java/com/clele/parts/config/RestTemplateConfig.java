package com.clele.parts.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000); // web search can take up to ~15 s
        return new RestTemplate(factory);
    }

    /**
     * Dedicated template for bulk datasheet downloads.
     *
     * <p>Backed by Apache HttpClient rather than the default {@link SimpleClientHttpRequestFactory},
     * because the JDK's {@code HttpURLConnection} underneath it <em>silently refuses to follow a
     * redirect that changes protocol</em>. Most stored datasheet URLs are {@code http://} links that
     * redirect to {@code https://}, so that path returns HTTP 200 with the redirect interstitial's
     * HTML in the body — indistinguishable from a real download except that it is not a PDF.
     * Apache HttpClient follows the hop and returns the file.
     *
     * <p>Timeouts are also raised: vendor PDF hosts are frequently slow and the files run to tens of
     * megabytes, which the 30 s read timeout on the shared template truncates.
     */
    @Bean
    public RestTemplate datasheetRestTemplate() {
        // HttpClient 5 splits these: the connect timeout belongs to the connection manager, the
        // response timeout to the request config.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(120))
                .setRedirectsEnabled(true)
                .setMaxRedirects(10)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    /**
     * Dedicated template for model calls that carry a whole document.
     *
     * <p>The datasheet extraction sends a ~20k-token excerpt and waits for a few thousand tokens
     * back, which routinely runs past the 30 s read timeout on the shared {@link #restTemplate()}.
     * That timeout does not surface as "the model is slow" — it surfaces as a read failure on a
     * request that was already billed, so the user pays and gets nothing.
     */
    @Bean
    public RestTemplate aiDocumentRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(180_000);
        return new RestTemplate(factory);
    }

    /** Dedicated template for local Ollama calls — CPU inference of a 3B model can be slow. */
    @Bean
    public RestTemplate ollamaRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);
        return new RestTemplate(factory);
    }
}
