package ru.tinkoff.kora.test.extension.junit5;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.tinkoff.kora.test.extension.junit5.testdata.ReplaceComponent;
import ru.tinkoff.kora.test.extension.junit5.testdata.ReplaceComponent2;
import ru.tinkoff.kora.test.extension.junit5.testdata.SimpleComponent1;
import ru.tinkoff.kora.test.extension.junit5.testdata.SimpleReplaceApplication;

@KoraAppTest(
    application = SimpleReplaceApplication.class,
    components = {SimpleComponent1.class})
public class ReplaceComponentJUnitExtensionTests extends Assertions implements KoraAppTestGraph {

    @TestComponent
    private SimpleComponent1 firstComponent;
    @TestComponent
    private ReplaceComponent replaceComponent;

    @Override
    public @NotNull KoraGraphModification graph() {
        return new KoraGraphModification()
            .replaceComponent(ReplaceComponent2::new, ReplaceComponent.class);
    }

    @Test
    void singleComponentInjected() {
        assertEquals("1", firstComponent.get());
    }

    @Test
    void twoComponentsInjected() {
        assertEquals("1", firstComponent.get());
        assertEquals("2", replaceComponent.get());
    }
}
