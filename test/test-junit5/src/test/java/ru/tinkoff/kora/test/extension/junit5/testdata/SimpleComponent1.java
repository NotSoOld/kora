package ru.tinkoff.kora.test.extension.junit5.testdata;

import ru.tinkoff.kora.common.Component;

@Component
public class SimpleComponent1 {

    public String get() {
        return "1";
    }
}