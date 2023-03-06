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
    })
public class ConfigMethodFileJUnitExtensionTests extends Assertions implements KoraAppTestConfig {

    @Override
    public @NotNull KoraConfigModification config() {
        return KoraConfigModification.ofConfigFile("reference-raw.conf");
    }

    @Test
    void parameterConfigFromMethodInjected(@TestComponent Config config) {
        assertEquals("Config(SimpleConfigObject({\"myconfig\":{\"myinnerconfig\":{\"third\":3}}}))", config.toString());
    }
}
