package org.pih.warehouse.core

import groovy.json.JsonSlurper
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

import javax.servlet.http.HttpServletRequest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin HTTP client that proxies Document-entity operations to document-service.
 * Forwards the current request's obx_token cookie per spec §4.4 so document-service
 * validates with the same identity as the originating user. Dies when document-service
 * becomes the only consumer of these methods (i.e., when Grails callers themselves migrate
 * in their own slices — most by Phase 8-11).
 */
class DocumentClient {

    String baseUrl = System.getenv('DOCUMENT_SERVICE_URL') ?: 'http://document-service:8081'

    // Hoisted RestTemplate (T8b-I4, T8b-M3): avoid allocating a fresh one (with default
    // unbounded timeouts) on every upload. Timeouts mirror the openConn() HttpURLConnection
    // values so non-2xx multipart uploads cannot hang a Grails worker indefinitely.
    // Grails 3 ships an older Spring Boot whose RestTemplateBuilder lacks the Duration overload,
    // so we build the RequestFactory directly — simpler and avoids a runtime MissingMethodException.
    private final org.springframework.web.client.RestTemplate restTemplate = buildRestTemplate()

    private static org.springframework.web.client.RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory()
        factory.connectTimeout = 5_000
        factory.readTimeout = 30_000
        return new org.springframework.web.client.RestTemplate(factory)
    }

    private String currentObxToken() {
        try {
            HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).request
            return req.cookies?.find { it.name == 'obx_token' }?.value
        } catch (IllegalStateException ignored) {
            // No request context (e.g. background job, bootstrap); proceed without token.
            return null
        }
    }

    private HttpURLConnection openConn(String path, String method = 'GET') {
        HttpURLConnection conn = (HttpURLConnection) new URL("${baseUrl}${path}").openConnection()
        conn.requestMethod = method
        String token = currentObxToken()
        if (token) conn.setRequestProperty('Cookie', "obx_token=${token}")
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        return conn
    }

    /**
     * Drains the error stream and disconnects an HttpURLConnection on non-2xx (T8b-I3).
     * Reading the error stream is what returns the socket to the keep-alive pool; without
     * this, repeated 4xx/5xx responses leak descriptors. `disconnect()` is a hint to close
     * the underlying socket — safe to call after the drain regardless of stream state.
     */
    private void drainAndDisconnect(HttpURLConnection conn) {
        try { conn.errorStream?.bytes } catch (Exception ignored) { /* nothing */ } finally { conn.disconnect() }
    }

    Map fetchById(String id) {
        if (!id) return null
        HttpURLConnection conn = openConn("/api/documents/${id}")
        if (conn.responseCode == 404) { drainAndDisconnect(conn); return null }
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET /${id} returned ${conn.responseCode}")
        }
        return (Map) new JsonSlurper().parse(conn.inputStream)
    }

    byte[] fetchContent(String id) {
        HttpURLConnection conn = openConn("/api/documents/${id}/content")
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET /${id}/content returned ${conn.responseCode}")
        }
        return conn.inputStream.bytes
    }

    List<Map> findByCode(String code) {
        HttpURLConnection conn = openConn("/api/documents?code=${URLEncoder.encode(code, 'UTF-8')}")
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET ?code=${code} returned ${conn.responseCode}")
        }
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    /**
     * Scalar lookup mirroring Grails' Document.findByName(String) — returns the first match or null.
     * Backed by /api/documents?name= which returns a single Document body or 404.
     */
    Map findByName(String name) {
        HttpURLConnection conn = openConn("/api/documents?name=${URLEncoder.encode(name, 'UTF-8')}")
        if (conn.responseCode == 404) { drainAndDisconnect(conn); return null }
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET ?name=${name} returned ${conn.responseCode}")
        }
        return (Map) new JsonSlurper().parse(conn.inputStream)
    }

    List<Map> findByTypeIds(List<String> typeIds) {
        String csv = typeIds.collect { URLEncoder.encode(it, 'UTF-8') }.join(',')
        HttpURLConnection conn = openConn("/api/documents?typeIds=${csv}")
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET ?typeIds returned ${conn.responseCode}")
        }
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    List<Map> nonTemplateDocumentTypes() {
        HttpURLConnection conn = openConn("/api/documents/types/non-template")
        if (conn.responseCode != 200) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service GET /types/non-template returned ${conn.responseCode}")
        }
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    Map create(String name, String filename, String contentType, byte[] fileContents, String documentTypeId = null) {
        def headers = new org.springframework.http.HttpHeaders()
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
        String token = currentObxToken()
        if (token) headers.add('Cookie', "obx_token=${token}")
        def body = new org.springframework.util.LinkedMultiValueMap<String, Object>()
        body.add('file', new org.springframework.core.io.ByteArrayResource(fileContents) {
            @Override String getFilename() { filename }
        })
        body.add('name', name)
        if (documentTypeId) body.add('documentTypeId', documentTypeId)
        def resp = restTemplate.exchange(
            "${baseUrl}/api/documents",
            org.springframework.http.HttpMethod.POST,
            new org.springframework.http.HttpEntity(body, headers),
            Map
        )
        return resp.body
    }

    void delete(String id) {
        HttpURLConnection conn = openConn("/api/documents/${id}", 'DELETE')
        if (conn.responseCode != 204) {
            drainAndDisconnect(conn)
            throw new RuntimeException("document-service DELETE /${id} returned ${conn.responseCode}")
        }
    }
}
