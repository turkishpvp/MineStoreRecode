package me.chrommob.minestore.common.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import me.chrommob.minestore.api.generic.AuthData;
import me.chrommob.minestore.api.web.Result;
import me.chrommob.minestore.api.web.WebApiAccessor;
import me.chrommob.minestore.api.web.WebContext;
import me.chrommob.minestore.api.web.WebRequest;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class ApiHandler {
    private final Gson gson = new Gson();
    private final AuthData authData;
    public ApiHandler(AuthData authData) {
        this.authData = authData;
        WebApiAccessor.registerRequestHandler(this::request);
    }

    public <V> Result<V, WebContext> request(WebRequest<V> request) {
        URL url;
        if (request.getCustomUrl() == null) {
            if (request.requiresApiKey()) {
                url = authData.createUrl(request.getPath(), request.getParams());
            } else {
                url = authData.createNonKeyUrl(request.getPath(), request.getParams());
            }
        } else {
            url = authData.createUrl(request.getCustomUrl(), request.getPath(), request.getParams());
        }
        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setRequestMethod(request.getType().name().toUpperCase());
            urlConnection.setRequestProperty("Content-Type", "application/json");
            // Laravel answers a failed FormRequest with a 302 back to the
            // referrer unless the client says it wants JSON. The connection
            // then followed that redirect to the storefront, got a 200 HTML
            // page and reported a parse error instead of the 422 that says
            // which field was wrong. Ask for JSON and never follow redirects:
            // no endpoint this plugin calls answers with one on success.
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                urlConnection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (request.getBody() != null) {
                urlConnection.setDoOutput(true);
                try (OutputStream os = urlConnection.getOutputStream()) {
                    os.write(request.getBody());
                }
            }
            int responseCode = urlConnection.getResponseCode();
            if (responseCode / 100 != 2) {
                boolean isCloudflare = urlConnection.getHeaderField("cf-cache-status") == null && "cloudflare".equalsIgnoreCase(urlConnection.getHeaderField("server"));
                if (urlConnection.getErrorStream() != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder responseString = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseString.append(line);
                        }
                        return new Result<>(null, new WebContext(isCloudflare, request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields()), true);
                    }
                } else {
                    return new Result<>(null, new WebContext(isCloudflare, request, url.toString(), responseCode, "No error stream", urlConnection.getHeaderFields()), true);
                }
            }
            InputStream is = urlConnection.getInputStream();
            if ("gzip".equals(urlConnection.getContentEncoding())) {
                is = new GZIPInputStream(is);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder responseString = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseString.append(line);
                }
                if (String.class.equals(request.getClazz())) {
                    V castedString = request.getClazz().cast(responseString.toString());
                    return new Result<>(castedString, new WebContext(request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields()), false);
                }
                try {
                    if (request.getTypeToken() == null) {
                        if (request.getClazz() == Void.class) {
                            return new Result<V,WebContext>(null, new WebContext(request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields()), false);
                        }
                        return new Result<>(gson.fromJson(responseString.toString(), request.getClazz()), new WebContext(request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields()), false);
                    }
                    return new Result<>(gson.fromJson(responseString.toString(), request.getTypeToken().getType()), new WebContext(request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields()), false);
                } catch (JsonSyntaxException e) {
                    return new Result<>(null, new WebContext(request, url.toString(), responseCode, responseString.toString(), urlConnection.getHeaderFields(), e), true);
                }
            }
        } catch (IOException e) {
            return new Result<>(null, new WebContext(request, url.toString(), e), true);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }
}
