package dev.pixflow.lab.payment.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PixFlowLiveSecurityTest {

    @Test
    void rendersApiValuesOnlyThroughTextNodes() throws Exception {
        var source = Files.readString(Path.of("src/main/resources/static/lab/app.js"));

        assertThat(source).doesNotContain("innerHTML", "insertAdjacentHTML");
        assertThat(source).contains("textContent", "document.createTextNode", "replaceChildren");
    }
}
