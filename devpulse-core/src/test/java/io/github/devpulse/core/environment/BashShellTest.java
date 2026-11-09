package io.github.devpulse.core.environment;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@Disabled
public class BashShellTest {

    @Test
    void testDiscover() throws Exception {
        var bash = BashShell.discover(null);
        System.out.println("Discovered Bash executable: " + bash.executable());
    }

    @Test
    void testScript() throws Exception{
        var bash = BashShell.discover(null);
//        assertThat(bash).isNotNull();
//        var processBuilder = bash.script(Path.of("src/test/resources/test.sh"));
//        assertThat(processBuilder).isNotNull();

        List<String> TOOLS = List.of("git", "java");
        var scan = """
                for tool in %s; do
                    location=$(command -v "$tool") || location=
                    printf '%%s\\t%%s\\n' "$tool" "$location"
                done
                """.formatted(String.join(" ", TOOLS));
        var root = Path.of(".").toAbsolutePath().normalize();
        var output = bash.capture(scan, root);



    }

}
