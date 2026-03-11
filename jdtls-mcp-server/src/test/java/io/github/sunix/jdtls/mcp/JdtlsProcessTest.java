package io.github.sunix.jdtls.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JdtlsProcess}.
 */
class JdtlsProcessTest {

    @Test
    void startFailsWhenJdtlsHomeIsInvalid() {
        JdtlsProcess process = new JdtlsProcess(
                Path.of("/nonexistent/jdtls"),
                Path.of(System.getProperty("java.io.tmpdir")));

        assertThatThrownBy(process::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugins directory not found");
    }

    @Test
    void getOutputStreamFailsWhenNotStarted() {
        JdtlsProcess process = new JdtlsProcess(
                Path.of("/some/jdtls"),
                Path.of(System.getProperty("java.io.tmpdir")));

        assertThatThrownBy(process::getOutputStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void getInputStreamFailsWhenNotStarted() {
        JdtlsProcess process = new JdtlsProcess(
                Path.of("/some/jdtls"),
                Path.of(System.getProperty("java.io.tmpdir")));

        assertThatThrownBy(process::getInputStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void isAliveReturnsFalseBeforeStart() {
        JdtlsProcess process = new JdtlsProcess(
                Path.of("/some/jdtls"),
                Path.of(System.getProperty("java.io.tmpdir")));

        org.assertj.core.api.Assertions.assertThat(process.isAlive()).isFalse();
    }
}
