package com.pulse.boundary;

import com.pulse.boundary.filter.Secured;
import com.pulse.control.JarAnalysisControl;
import com.pulse.entity.AnalysisStatus;
import com.pulse.entity.JarAnalysis;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JarAnalysisResourceTest {

    @Mock
    private JarAnalysisControl control;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Principal principal;

    @Mock
    private EntityPart entityPart;

    private JarAnalysisResource resource;

    @BeforeEach
    void setUp() {
        resource = new JarAnalysisResource(control);
        resource.setSecurityContext(securityContext);
    }

    @Test
    @DisplayName("Class and method annotations meet security and routing specifications")
    void testAnnotations() throws NoSuchMethodException {
        assertThat(JarAnalysisResource.class.isAnnotationPresent(Path.class)).isTrue();
        assertThat(JarAnalysisResource.class.getAnnotation(Path.class).value()).isEqualTo("/jars");
        assertThat(JarAnalysisResource.class.isAnnotationPresent(Secured.class)).isTrue();
        assertThat(JarAnalysisResource.class.isAnnotationPresent(Produces.class)).isTrue();

        Method analyzeMethod = JarAnalysisResource.class.getMethod("analyzeMultipart", List.class, SecurityContext.class);
        assertThat(analyzeMethod.isAnnotationPresent(POST.class)).isTrue();
        assertThat(analyzeMethod.isAnnotationPresent(Consumes.class)).isTrue();
        assertThat(analyzeMethod.getAnnotation(Consumes.class).value()).contains(MediaType.MULTIPART_FORM_DATA);
        assertThat(analyzeMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(analyzeMethod.getAnnotation(RolesAllowed.class).value()).containsExactlyInAnyOrder("ENGINEER", "ADMIN");

        Method getByIdMethod = JarAnalysisResource.class.getMethod("getSessionById", Long.class);
        assertThat(getByIdMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(getByIdMethod.getAnnotation(RolesAllowed.class).value()).containsExactlyInAnyOrder("ENGINEER", "ADMIN");

        Method getAllMethod = JarAnalysisResource.class.getMethod("getAllSessions");
        assertThat(getAllMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(getAllMethod.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("analyze with valid stream and user returns 202 Accepted with Location header")
    void testAnalyzeSuccess() throws Exception {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");
        when(control.submitAsync(any(InputStream.class), eq("app.jar"), eq("engineer_1"))).thenReturn(77L);

        Response response = resource.analyze(new ByteArrayInputStream("dummy-jar-bytes".getBytes()), "app.jar", securityContext);

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        assertThat(response.getLocation()).isEqualTo(URI.create("/api/v1/jars/sessions/77"));
        assertThat(response.getEntity().toString()).contains("\"id\":77");
        verify(control).submitAsync(any(InputStream.class), eq("app.jar"), eq("engineer_1"));
    }

    @Test
    @DisplayName("analyzeMultipart extracts file and returns 202 Accepted")
    void testAnalyzeMultipartSuccess() throws Exception {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");
        when(entityPart.getName()).thenReturn("file");
        when(entityPart.getFileName()).thenReturn(Optional.of("sample.jar"));
        when(entityPart.getContent()).thenReturn(new ByteArrayInputStream("fake-data".getBytes()));
        when(control.submitAsync(any(InputStream.class), eq("sample.jar"), eq("engineer_1"))).thenReturn(88L);

        Response response = resource.analyzeMultipart(List.of(entityPart), securityContext);

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        assertThat(response.getLocation()).isEqualTo(URI.create("/api/v1/jars/sessions/88"));
    }

    @Test
    @DisplayName("analyzeMultipart returns 400 Bad Request when parts list is null or empty")
    void testAnalyzeMultipartEmpty() {
        Response response = resource.analyzeMultipart(Collections.emptyList(), securityContext);
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    @DisplayName("getSessionById returns 200 with JarAnalysis when found")
    void testGetSessionByIdFound() {
        JarAnalysis analysis = new JarAnalysis("test.jar", "engineer_1");
        analysis.setId(5L);
        analysis.setStatus(AnalysisStatus.COMPLETED);
        when(control.findById(5L)).thenReturn(Optional.of(analysis));

        Response response = resource.getSessionById(5L);
        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isSameAs(analysis);
    }

    @Test
    @DisplayName("getSessionById returns 404 when session not found")
    void testGetSessionByIdNotFound() {
        when(control.findById(999L)).thenReturn(Optional.empty());

        Response response = resource.getSessionById(999L);
        assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    @DisplayName("getAllSessions returns 200 with list of sessions")
    void testGetAllSessions() {
        JarAnalysis ja = new JarAnalysis("test.jar", "engineer_1");
        when(control.findAll()).thenReturn(List.of(ja));

        Response response = resource.getAllSessions();
        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat((List<?>) response.getEntity()).hasSize(1);
    }
}
