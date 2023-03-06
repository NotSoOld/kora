package ru.tinkoff.kora.test.extension.junit5;

import com.typesafe.config.Config;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.tinkoff.kora.config.annotation.processor.processor.ConfigRootAnnotationProcessor;
import ru.tinkoff.kora.config.annotation.processor.processor.ConfigSourceAnnotationProcessor;
import ru.tinkoff.kora.test.extension.junit5.testdata.SimpleApplication;
import ru.tinkoff.kora.test.extension.junit5.testdata.SimpleComponent1;

@KoraAppTest(
    application = SimpleApplication.class,
    components = {SimpleComponent1.class},
    processors = {
        ConfigRootAnnotationProcessor.class,
        ConfigSourceAnnotationProcessor.class
    },
    configFiles = "config/reference-env.conf",      // 3
    config = """
            myconfig {
              myinnerconfig {
                second = 2
              }
            }
        """)                                        // 4
public class ConfigMethodFileWithRawWithFileAnnotationWithRawAnnotationJUnitExtensionTests extends Assertions implements KoraAppTestConfig {

    @Override
    public @NotNull KoraConfigModification config() {
        return KoraConfigModification.ofConfigFile("reference-raw.conf")   // 1
            .mergeWithConfig("""
                            myconfig {
                              myinnerconfig {
                                myproperty = 1
                                fourth = 4
                              }
                            }
                """);                                                       // 2
    }

    @Test
    void parameterConfigFromMethodInjected(@TestComponent Config config) {
        assertEquals("Config(SimpleConfigObject({\"myconfig\":{\"myinnerconfig\":{\"fourth\":4,\"myproperty\":1,\"second\":2,\"third\":3}}}))", config.toString());
    }
}
