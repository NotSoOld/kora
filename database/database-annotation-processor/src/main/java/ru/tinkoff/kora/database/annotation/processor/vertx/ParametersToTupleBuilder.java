package ru.tinkoff.kora.database.annotation.processor.vertx;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import ru.tinkoff.kora.annotation.processor.common.CommonUtils;
import ru.tinkoff.kora.database.annotation.processor.DbUtils;
import ru.tinkoff.kora.database.annotation.processor.QueryWithParameters;
import ru.tinkoff.kora.database.annotation.processor.entity.DbEntity;
import ru.tinkoff.kora.database.annotation.processor.model.QueryParameter;

import javax.annotation.Nullable;
import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class ParametersToTupleBuilder {

    private ParametersToTupleBuilder() {}

    private record Param(List<Integer> index, String name, CodeBlock code) {}

    public static void generate(MethodSpec.Builder b, QueryWithParameters sqlWithParameters, ExecutableElement method, List<QueryParameter> parameters, @Nullable QueryParameter batchParam) {
        if (batchParam != null) {
            b.addCode("var _batchParams = new $T<$T>($L.size());\n", ArrayList.class, VertxTypes.TUPLE, batchParam.variable());
            b.addCode("for (var _i = 0; _i < $L.size(); _i++) {$>\n", batchParam.variable());
            for (var parameter : parameters) {
                if (parameter instanceof QueryParameter.BatchParameter) {
                    b.addCode("var _batch_$L = $L.get(_i);\n", parameter.name(), parameter.name());
                }
            }
        }

        var sqlParams = parameters.stream()
            .filter(Predicate.not(p -> p instanceof QueryParameter.ConnectionParameter))
            .map(p -> {
                if (p instanceof QueryParameter.BatchParameter bp) {
                    return Map.entry("_batch_" + bp.name(), bp.parameter());
                } else {
                    return Map.entry(p.name(), p);
                }
            })
            .<Param>mapMulti((e, sink) -> {
                if (e.getValue() instanceof QueryParameter.SimpleParameter simpleParameter) {
                    var nativeType = VertxNativeTypes.find(TypeName.get(simpleParameter.type()));
                    var sqlParameter = Objects.requireNonNull(sqlWithParameters.find(simpleParameter.variable().getSimpleName().toString()));
                    if (nativeType == null) {
                        sink.accept(new Param(
                            sqlParameter.sqlIndexes(),
                            simpleParameter.variable().getSimpleName().toString(),
                            CodeBlock.of("$L.apply($L)", DbUtils.parameterMapperName(method, simpleParameter.variable()), e.getKey())
                        ));
                    } else {
                        sink.accept(new Param(
                            sqlParameter.sqlIndexes(),
                            simpleParameter.variable().getSimpleName().toString(),
                            CodeBlock.of("$L", e.getKey())
                        ));
                    }
                } else {
                    var entityParam = (QueryParameter.EntityParameter) e.getValue();
                    for (var field : entityParam.entity().entityFields()) {
                        var sqlParameter = sqlWithParameters.find(entityParam.variable().getSimpleName() + "." + field.element().getSimpleName());
                        if (sqlParameter == null || sqlParameter.sqlIndexes().isEmpty()) {
                            continue;
                        }

                        var variableName = entityParam.variable().getSimpleName() + "$" + field.element().getSimpleName();
                        var fieldAccessor = entityParam.entity().entityType() == DbEntity.EntityType.RECORD
                            ? e.getKey() + "." + field.element().getSimpleName() + "()"
                            : e.getKey() + ".get" + CommonUtils.capitalize(field.element().getSimpleName().toString()) + "()";
                        var nativeType = VertxNativeTypes.find(TypeName.get(field.typeMirror()));
                        if (nativeType == null) {
                            sink.accept(new Param(
                                sqlParameter.sqlIndexes(),
                                variableName,
                                CodeBlock.of("$L.apply($L)", DbUtils.parameterMapperName(method, entityParam.variable(), field.element().getSimpleName().toString()), fieldAccessor)
                            ));
                        } else {
                            sink.accept(new Param(
                                sqlParameter.sqlIndexes(),
                                variableName,
                                CodeBlock.of("$L", fieldAccessor)
                            ));
                        }
                    }
                }
            })
            .toList();

        if (sqlParams.isEmpty()) {
            b.addCode("var _tuple = $T.tuple();\n", VertxTypes.TUPLE);
        } else {
            b.addCode("var _tuple = $T.of($>\n", VertxTypes.TUPLE);

            var sortedParameters = sqlParams.stream()
                .flatMap(p -> p.index().stream().map(i -> Map.entry(i, p)))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

            for (int i = 0; i < sortedParameters.size(); i++) {
                var sqlParam = sortedParameters.get(i);
                if (i > 0) {
                    b.addCode(",\n");
                }
                b.addCode(sqlParam.code());
            }
            b.addCode("$<\n);\n");
        }

        if (batchParam != null) {
            b.addCode("_batchParams.add(_tuple);");
            b.addCode("$<\n}\n");
        }
    }
}
