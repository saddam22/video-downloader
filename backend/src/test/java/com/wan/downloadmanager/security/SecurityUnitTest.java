package com.wan.downloadmanager.security;

import com.wan.downloadmanager.exception.WdmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUnitTest {
    final UrlPolicy strict = new UrlPolicy(false, false);
    final UrlPolicy lan = new UrlPolicy(false, true);

    @Test
    void ssrfTargetsAreRefused() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "::1", "169.254.169.254", "0.0.0.0", "224.0.0.1", "fe80::1"}) {
            assertThatThrownBy(() -> lan.checkAddress(InetAddress.getByName(ip))).as(ip).isInstanceOf(WdmException.class);
        }
        for (String ip : new String[]{"10.0.0.5", "192.168.1.10", "172.16.3.4", "100.64.1.1", "fd00::1"}) {
            assertThatThrownBy(() -> strict.checkAddress(InetAddress.getByName(ip))).as(ip).isInstanceOf(WdmException.class);
            lan.checkAddress(InetAddress.getByName(ip)); // allowed by default (LAN / NAS downloads)
        }
        lan.checkAddress(InetAddress.getByName("93.184.216.34"));
        assertThatThrownBy(() -> lan.validate("http://localhost:8080/x")).isInstanceOf(WdmException.class);
    }

    @Test
    void onlyHttpUrlsWithoutCredentials() {
        assertThatThrownBy(() -> lan.parse("file:///etc/passwd")).hasMessageContaining("http");
        assertThatThrownBy(() -> lan.parse("https://u:p@example.com/")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> lan.parse("https:///nohost")).isInstanceOf(WdmException.class);
        assertThatThrownBy(() -> lan.parse("https://example.com/" + "a".repeat(9000))).hasMessageContaining("long");
        assertThat(lan.parse(" https://example.com/a/../b.zip ").toString()).isEqualTo("https://example.com/b.zip");
    }

    @Test
    void fileNamesAreSanitised() {
        assertThat(SafeFiles.sanitize("../../etc/passwd")).doesNotContain("/").doesNotStartWith(".");
        assertThat(SafeFiles.sanitize("CON.txt")).isEqualTo("_CON.txt");
        assertThat(SafeFiles.sanitize("a<b>c:d\"e|f?g*h.zip")).isEqualTo("a_b_c_d_e_f_g_h.zip");
        assertThat(SafeFiles.sanitize("trailing. . ")).isEqualTo("trailing");
        assertThat(SafeFiles.sanitize("")).isEqualTo("download");
        assertThat(SafeFiles.sanitize("x".repeat(400) + ".mp4")).hasSize(SafeFiles.MAX_NAME).endsWith(".mp4");
    }

    @Test
    void contentDispositionNames() {
        URI u = URI.create("https://example.com/path/file%20name.zip?x=1");
        assertThat(SafeFiles.fileNameFrom(null, u)).isEqualTo("file name.zip");
        assertThat(SafeFiles.fileNameFrom("attachment; filename=\"report.pdf\"", u)).isEqualTo("report.pdf");
        assertThat(SafeFiles.fileNameFrom("attachment; filename*=UTF-8''%E0%A6%AC%E0%A6%BE%E0%A6%82%E0%A6%B2%E0%A6%BE.txt", u)).isEqualTo("বাংলা.txt");
        assertThat(SafeFiles.fileNameFrom("attachment; filename=\"../../evil.exe\"", u)).doesNotContain("/");
    }

    @Test
    void pathTraversalAndOverwriteProtection(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> SafeFiles.inside(dir, "../outside")).isInstanceOf(WdmException.class);
        Files.writeString(dir.resolve("a.zip"), "x");
        Files.writeString(dir.resolve("a (1).zip"), "x");
        assertThat(SafeFiles.uniqueTarget(dir, "a.zip").getFileName().toString()).isEqualTo("a (2).zip");
    }

    @Test
    void secretsAreRedacted() {
        String s = Redactor.redact("GET https://x.com/f?id=1&token=abc123&X-Amz-Signature=deadbeef Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.sig");
        assertThat(s).doesNotContain("abc123").doesNotContain("deadbeef").doesNotContain("eyJzdWIi").contains("id=1");
    }
}
