package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.tinkoff.kora.application.graph.ApplicationGraphDraw
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependency
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.declaration.ModuleDeclaration
import ru.tinkoff.kora.kora.app.ksp.exception.NewRoundException
import ru.tinkoff.kora.kora.app.ksp.interceptor.ComponentInterceptors
import ru.tinkoff.kora.ksp.common.*
import ru.tinkoff.kora.ksp.common.AnnotationUtils.findAnnotation
import ru.tinkoff.kora.ksp.common.AnnotationUtils.isAnnotationPresent
import ru.tinkoff.kora.ksp.common.KspCommonUtils.generated
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier
import javax.annotation.processing.SupportedOptions

@SupportedOptions("koraLogLevel")
class KoraAppProcessor(
    environment: SymbolProcessorEnvironment
) : BaseSymbolProcessor(environment) {

    private val processedDeclarations = hashMapOf<String, Pair<KSClassDeclaration, ProcessingState>>()

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val log: Logger = LoggerFactory.getLogger(KoraAppProcessor::class.java)
    private val appParts = mutableSetOf<KSClassDeclaration>() // todo split in two
    private val annotatedModules = mutableListOf<KSClassDeclaration>()
    private val components = mutableSetOf<KSClassDeclaration>()

    private lateinit var resolver: Resolver
    private var ctx: ProcessingContext? = null

    override fun finish() {
        for (element in processedDeclarations.entries) {
            val (declaration, processingResult) = element.value
            when (processingResult) {
                is ProcessingState.Failed -> {
                    processingResult.exception.printError(kspLogger)
                    throw processingResult.exception
                }

                is ProcessingState.NewRoundRequired -> {
                    val message = "Component was expected to be generated by extension %s but was not: %s/%s".format(processingResult.source, processingResult.type, processingResult.tag)
                    kspLogger.error(message)
                    throw RuntimeException(message)
                }

                is ProcessingState.Ok -> this.write(declaration, processingResult.allModules, processingResult.components)
                is ProcessingState.None -> throw IllegalStateException()
                is ProcessingState.Processing -> throw IllegalStateException()
            }
        }
        try {
            generateAppParts()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    override fun processRound(resolver: Resolver): List<KSAnnotated> {
        this.resolver = resolver

        if (ctx == null) {
            ctx = ProcessingContext(resolver, kspLogger, codeGenerator)
        } else {
            ctx!!.resolver = resolver
        }

        this.processGenerated(resolver)
        val newModules = this.processModules(resolver)
        val newComponents = this.processComponents(resolver)
        this.processAppParts(resolver)
        if (newModules || newComponents) {
            val newDeclarations = hashMapOf<String, Pair<KSClassDeclaration, ProcessingState>>()
            for ((k, v) in processedDeclarations) {
                val none = parseNone(v.first)
                newDeclarations[k] = v.first to none
            }
            processedDeclarations.clear()
            processedDeclarations.putAll(newDeclarations)
        }
        val results = linkedMapOf<String, Pair<KSClassDeclaration, ProcessingState>>()

        val koraAppElements = resolver.getSymbolsWithAnnotation(CommonClassNames.koraApp.canonicalName).toList()
        val unableToProcess = koraAppElements.filterNot { it.validate() }.toMutableList()
        for (symbol in koraAppElements) {
            symbol.visitClass { declaration ->
                if (declaration.classKind == ClassKind.INTERFACE) {
                    log.info("Kora app found: {}", declaration.toClassName().canonicalName)
                    val key = declaration.qualifiedName!!.asString()
                    processedDeclarations.computeIfAbsent(key) {
                        declaration to parseNone(declaration)
                    }
                } else {
                    kspLogger.error("@KoraApp can be placed only on interfaces", declaration)
                }
            }
        }

        for (annotatedClass in processedDeclarations.entries) {
            var (declaration, processingResult) = annotatedClass.value
            val actualKey = annotatedClass.key
            try {
                if (processingResult is ProcessingState.Ok) {
                    continue
                }
                if (processingResult is ProcessingState.Failed) {
                    processingResult = parseNone(declaration)
                }
                if (processingResult is ProcessingState.None) {
                    processingResult = this.processNone(processingResult)
                }
                if (processingResult is ProcessingState.NewRoundRequired) {
                    processingResult = processingResult.processing
                }
                if (processingResult !is ProcessingState.Processing) {
                    results[actualKey] = declaration to processingResult
                } else {
                    val result = this.processProcessing(processingResult)
                    results[actualKey] = declaration to result
                }
            } catch (e: NewRoundException) {
                unableToProcess.add(declaration)
                results[actualKey] = declaration to ProcessingState.NewRoundRequired(
                    e.source,
                    e.type,
                    e.tag,
                    e.resolving
                )
            } catch (e: ProcessingErrorException) {
                results[actualKey] = declaration to ProcessingState.Failed(e)
            }
        }
        processedDeclarations.putAll(results)
        for (generatedFile in codeGenerator.generatedFile) {
            kspLogger.info("Generated by extension: ${generatedFile.canonicalPath}")
        }
        return unableToProcess
    }

    private fun processProcessing(processing: ProcessingState.Processing): ProcessingState {
        return GraphBuilder.processProcessing(ctx!!, processing)
    }

    private fun processNone(none: ProcessingState.None): ProcessingState {
        val stack = ArrayDeque<ProcessingState.ResolutionFrame>()
        for (i in 0 until none.rootSet.size) {
            stack.addFirst(ProcessingState.ResolutionFrame.Root(i))
        }
        return ProcessingState.Processing(none.root, none.allModules, none.sourceDeclarations, none.templateDeclarations, none.rootSet, ArrayList(), stack)
    }

    private fun parseNone(declaration: KSClassDeclaration): ProcessingState {
        if (declaration.classKind != ClassKind.INTERFACE) {
            return ProcessingState.Failed(ProcessingErrorException("@KoraApp is only applicable to interfaces", declaration))
        }
        try {
            val rootErasure = declaration.asStarProjectedType()
            val rootModule = ModuleDeclaration.MixedInModule(declaration)
            val filterObjectMethods: (KSFunctionDeclaration) -> Boolean = {
                val name = it.simpleName.asString()
                name != "equals" && name != "hashCode" && name != "toString"// todo find out a better way to filter object methods
            }
            val mixedInComponents = declaration.getAllFunctions()
                .filter(filterObjectMethods)
                .toMutableList()

            val submodules = declaration.superTypes.map { it.resolve() }
                .map { it.declaration as KSClassDeclaration }
                .filter { it.findAnnotation(CommonClassNames.koraSubmodule) != null }
                .map { resolver.getKSNameFromString(it.qualifiedName!!.asString() + "SubmoduleImpl") }
                .map { resolver.getClassDeclarationByName(it)!! }
                .toList()
            val allModules = (submodules + annotatedModules)
                .flatMap { it.getAllSuperTypes().map { it.declaration as KSClassDeclaration } + it }
                .filter { it.qualifiedName?.asString() != "kotlin.Any" }
                .toSet()
                .toList()

            val annotatedModules = allModules
                .filter { !it.asStarProjectedType().isAssignableFrom(rootErasure) }
                .map { ModuleDeclaration.AnnotatedModule(it) }
            val annotatedModuleComponentsTmp = annotatedModules
                .flatMap { it.element.getAllFunctions().filter(filterObjectMethods).map { f -> ComponentDeclaration.fromModule(ctx!!, it, f) } }
            val annotatedModuleComponents = ArrayList(annotatedModuleComponentsTmp)
            for (annotatedComponent in annotatedModuleComponentsTmp) {
                if (annotatedComponent.method.modifiers.contains(Modifier.OVERRIDE)) {
                    val overridee = annotatedComponent.method.findOverridee()
                    annotatedModuleComponents.removeIf { it.method == overridee }
                    mixedInComponents.remove(overridee)
                }
            }
            val allComponents = ArrayList<ComponentDeclaration>(annotatedModuleComponents.size + mixedInComponents.size + 200)
            for (componentClass in components) {
                allComponents.add(ComponentDeclaration.fromAnnotated(ctx!!, componentClass))
            }
            allComponents.addAll(mixedInComponents.asSequence().map { ComponentDeclaration.fromModule(ctx!!, rootModule, it) })
            allComponents.addAll(annotatedModuleComponents)
            allComponents.sortedBy { it.toString() }
            // todo modules from kora app part
            val templateComponents = ArrayList<ComponentDeclaration>(allComponents.size)
            val components = ArrayList<ComponentDeclaration>(allComponents.size)
            for (component in allComponents) {
                if (component.isTemplate()) {
                    templateComponents.add(component)
                } else {
                    components.add(component)
                }
            }
            val rootSet = components.filter {
                it.source.isAnnotationPresent(CommonClassNames.root)
                    || ctx!!.serviceTypesHelper.isLifecycle(it.type)
                    || it is ComponentDeclaration.AnnotatedComponent && it.classDeclaration.isAnnotationPresent(CommonClassNames.root)
            }
            return ProcessingState.None(declaration, allModules, components, templateComponents, rootSet)
        } catch (e: ProcessingErrorException) {
            return ProcessingState.Failed(e)
        }
    }

    private fun processAppParts(resolver: Resolver) {
        resolver.getSymbolsWithAnnotation(CommonClassNames.koraSubmodule.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .forEach { appParts.add(it) }
    }

    private fun processGenerated(resolver: Resolver) {
        log.info("Generated from prev round:{}", resolver.getSymbolsWithAnnotation(CommonClassNames.generated.canonicalName)
            .joinToString("\n") { obj -> obj.location.toString() }
            .trimIndent()
        )
    }

    private fun processModules(resolver: Resolver): Boolean {
        val moduleOfSymbols = resolver.getSymbolsWithAnnotation(CommonClassNames.module.canonicalName).toList()
        for (moduleSymbol in moduleOfSymbols) {
            moduleSymbol.visitClass { moduleDeclaration ->
                if (moduleDeclaration.classKind == ClassKind.INTERFACE) {
                    log.info("Module found: {}", moduleDeclaration.toClassName().canonicalName)
                    annotatedModules.add(moduleDeclaration)
                }
            }
        }
        return moduleOfSymbols.isNotEmpty()
    }

    private fun processComponents(resolver: Resolver): Boolean {
        val componentOfSymbols = resolver.getSymbolsWithAnnotation(CommonClassNames.component.canonicalName).toList()
        for (componentSymbol in componentOfSymbols) {
            componentSymbol.visitClass { componentDeclaration ->
                if (componentDeclaration.classKind == ClassKind.CLASS) {
                    if (hasAopAnnotations(resolver, componentSymbol)) {
                        kspLogger.info("Component found, waiting for aop proxy: ${componentSymbol.location}", componentSymbol)
                    } else {
                        kspLogger.info("Component found: ${componentDeclaration.toClassName().canonicalName}", componentSymbol)
                        components.add(componentDeclaration)
                    }
                }
            }
        }
        return componentOfSymbols.isNotEmpty()
    }


    private fun findSinglePublicConstructor(declaration: KSClassDeclaration): KSFunctionDeclaration {
        val primaryConstructor = declaration.primaryConstructor
        if (primaryConstructor != null && primaryConstructor.isPublic()) return primaryConstructor

        val constructors = declaration.getConstructors()
            .filter { c -> c.isPublic() }
            .toList()
        if (constructors.isEmpty()) {
            throw ProcessingErrorException(
                "Type annotated with @Component has no public constructors", declaration
            )
        }
        if (constructors.size > 1) {
            throw ProcessingErrorException(
                "Type annotated with @Component has more then one public constructor", declaration
            )
        }
        return constructors[0]
    }

    private fun write(declaration: KSClassDeclaration, allModules: List<KSClassDeclaration>, components: List<ResolvedComponent>) {
        val interceptors: ComponentInterceptors = ComponentInterceptors.parseInterceptors(ctx!!, components)
        kspLogger.logging("Found interceptors: $interceptors")
        val applicationImplFile = this.generateImpl(declaration, allModules)
        val applicationGraphFile = this.generateApplicationGraph(resolver, declaration, allModules, components, interceptors)
        applicationImplFile.writeTo(codeGenerator = codeGenerator, aggregating = true)
        applicationGraphFile.writeTo(codeGenerator = codeGenerator, aggregating = true)
    }

    private fun generateImpl(declaration: KSClassDeclaration, modules: List<KSClassDeclaration>): FileSpec {
        val containingFile = declaration.containingFile!!
        val packageName = containingFile.packageName.asString()
        val moduleName = "${declaration.toClassName().simpleName}Impl"

        val fileSpec = FileSpec.builder(
            packageName = packageName,
            fileName = moduleName
        )
        val classBuilder = TypeSpec.classBuilder(moduleName)
            .addOriginatingKSFile(containingFile)
            .generated(KoraAppProcessor::class)
            .addModifiers(KModifier.PUBLIC, KModifier.OPEN)
            .addSuperinterface(declaration.toClassName())


        for ((index, module) in modules.withIndex()) {
            val moduleClass = module.toClassName()
            if (module.containingFile != null) {
                classBuilder.addOriginatingKSFile(module.containingFile!!)
            }
            classBuilder.addProperty(
                PropertySpec.builder("module$index", moduleClass)
                    .initializer("object : %T {}", moduleClass)
                    .build()
            )
        }
        for (component in components) {
            classBuilder.addOriginatingKSFile(component.containingFile!!)
        }
        return fileSpec.addType(classBuilder.build()).build()
    }

    private fun generateAppParts() {
        for (appPart in this.appParts) {
            val packageName = appPart.packageName.asString()
            val b = TypeSpec.interfaceBuilder(appPart.simpleName.asString() + "SubmoduleImpl")
                .addSuperinterface(appPart.toClassName())
                .generated(KoraAppProcessor::class)
            var componentCounter = 0
            for (component in components) {
                b.addOriginatingKSFile(component.containingFile!!)
                val constructor = findSinglePublicConstructor(component)
                val mb = FunSpec.builder("_component" + componentCounter++)
                    .returns(component.toClassName())
                mb.addCode("return %T(", component.toClassName())
                for (i in constructor.parameters.indices) {
                    val parameter = constructor.parameters[i]
                    val tag = parameter.parseTags()
                    val ps = ParameterSpec.builder(parameter.name!!.asString(), parameter.type.toTypeName())
                    if (tag.isNotEmpty()) {
                        ps.addAnnotation(tag.makeTagAnnotationSpec())
                    }
                    mb.addParameter(ps.build())
                    if (i > 0) {
                        mb.addCode(", ")
                    }
                    mb.addCode("%N", parameter)
                }
                val tag = component.parseTags()
                if (tag.isNotEmpty()) {
                    mb.addAnnotation(tag.makeTagAnnotationSpec())
                }
                mb.addCode(")\n")
                b.addFunction(mb.build())
            }
            val companion = TypeSpec.companionObjectBuilder()
            for ((moduleCounter, module) in annotatedModules.withIndex()) {
                val moduleName = "_module$moduleCounter"
                val type = module.toClassName()
                companion.addProperty(PropertySpec.builder(moduleName, type).initializer("object : %T {}", type).build())
                for (component in module.getDeclaredFunctions()) {
                    val componentType = component.returnType!!.toTypeName()
                    val mb = FunSpec.builder("_component" + componentCounter++)
                        .returns(componentType)
                    mb.addCode("return %N.%N(", moduleName, component.simpleName.asString())
                    for (i in component.parameters.indices) {
                        val parameter = component.parameters[i]
                        val tag = parameter.parseTags()
                        val ps = ParameterSpec.builder(parameter.name!!.asString(), parameter.type.toTypeName())
                        if (tag.isNotEmpty()) {
                            ps.addAnnotation(tag.makeTagAnnotationSpec())
                        }
                        mb.addParameter(ps.build())
                        if (i > 0) {
                            mb.addCode(", ")
                        }
                        mb.addCode("%N", parameter.name?.asString())
                    }
                    val tag = component.parseTags()
                    if (tag.isNotEmpty()) {
                        mb.addAnnotation(tag.makeTagAnnotationSpec())
                    }
                    if (component.findAnnotation(CommonClassNames.defaultComponent) != null) {
                        mb.addAnnotation(CommonClassNames.defaultComponent)
                    }
                    mb.addCode(")\n")
                    b.addFunction(mb.build())
                }
            }
            val typeSpec = b.addType(companion.build()).build()
            val fileSpec = FileSpec.builder(packageName, typeSpec.name!!).addType(typeSpec).build()
            fileSpec.writeTo(codeGenerator, false)
        }
    }

    private fun generateApplicationGraph(
        resolver: Resolver,
        declaration: KSClassDeclaration,
        allModules: List<KSClassDeclaration>,
        graph: List<ResolvedComponent>,
        interceptors: ComponentInterceptors
    ): FileSpec {
        val supplier: KSClassDeclaration = resolver.getClassDeclarationByName(Supplier::class.qualifiedName.toString())!!
        val functionDeclaration: KSClassDeclaration = resolver.getClassDeclarationByName(Function::class.qualifiedName.toString())!!

        val containingFile = declaration.containingFile!!
        val packageName = containingFile.packageName.asString()
        val graphName = "${declaration.simpleName.asString()}Graph"

        val fileSpec = FileSpec.builder(
            packageName = packageName,
            fileName = graphName
        )

        val implClass = ClassName(packageName, "${declaration.simpleName.asString()}Impl")
        val supplierSuperInterface = supplier.toClassName().parameterizedBy(CommonClassNames.applicationGraphDraw)
        val functionSuperInterface = functionDeclaration.toClassName().parameterizedBy(implClass, CommonClassNames.applicationGraphDraw)
        val classBuilder = TypeSpec.classBuilder(graphName)
            .addOriginatingKSFile(containingFile)
            .generated(KoraAppProcessor::class)
            .addSuperinterface(supplierSuperInterface)
            .addSuperinterface(functionSuperInterface)
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(CommonClassNames.applicationGraphDraw)
                    .addStatement("return %N.graph(%T())", "${declaration.simpleName.asString()}Graph", implClass)
                    .build()
            ).addFunction(
                FunSpec.builder("apply")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("impl", implClass)
                    .returns(CommonClassNames.applicationGraphDraw)
                    .addStatement("return %N.graph(impl)", "${declaration.simpleName.asString()}Graph")
                    .build()
            )

        for (component in this.components) {
            classBuilder.addOriginatingKSFile(component.containingFile!!)
        }
        for (module in annotatedModules) {
            classBuilder.addOriginatingKSFile(module.containingFile!!)
        }
        val companion = TypeSpec.companionObjectBuilder()
        val functionMethodBuilder = FunSpec.builder("graph")
            .addParameter("impl", implClass)
            .returns(CommonClassNames.applicationGraphDraw)
            .addStatement("val graphDraw =  %T(%T::class.java)", ApplicationGraphDraw::class, declaration.toClassName())
        val promisedComponents = TreeSet<Int>()
        for (component in graph) {
            for (dependency in component.dependencies) {
                if (dependency is ComponentDependency.PromiseOfDependency) {
                    dependency.component?.let {
                        promisedComponents.add(it.index)
                    }
                }
                if (dependency is ComponentDependency.PromisedProxyParameterDependency) {
                    val d = GraphResolutionHelper.findDependency(ctx!!, dependency.declaration, graph, dependency.claim)
                    promisedComponents.add(d!!.component!!.index)
                }
                if (dependency is ComponentDependency.AllOfDependency && dependency.claim.claimType == DependencyClaim.DependencyClaimType.ALL_OF_PROMISE) {
                    for (d in GraphResolutionHelper.findDependenciesForAllOf(ctx!!, dependency.claim, graph)) {
                        promisedComponents.add(d.component!!.index)
                    }
                }
            }
        }
        for (promisedComponent in promisedComponents) {
            functionMethodBuilder.addStatement(
                "val component%L = %T<%T<%T>>()",
                promisedComponent,
                AtomicReference::class.asClassName(),
                CommonClassNames.node,
                graph[promisedComponent].type.toTypeName()
            )
        }
        functionMethodBuilder.addCode("\n")


        for (component in graph) {
            val statement = this.generateComponentStatement(allModules, interceptors, graph, promisedComponents, component)
            functionMethodBuilder.addCode(statement)
        }
        functionMethodBuilder.addStatement("\n")
        functionMethodBuilder.addStatement("return graphDraw")

        val supplierMethodBuilder = FunSpec.builder("graph")
            .returns(ApplicationGraphDraw::class)
            .addCode("\nval impl = %T()", implClass)
            .addCode("\nreturn %N.graph(impl)\n", declaration.simpleName.asString() + "Graph")
        return fileSpec.addType(
            classBuilder
                .addType(
                    companion
                        .addFunction(supplierMethodBuilder.build())
                        .addFunction(functionMethodBuilder.build())
                        .build()
                )
                .addFunction(supplierMethodBuilder.build())
                .build()
        ).build()
    }

    private fun generateComponentStatement(
        allModules: List<KSClassDeclaration>,
        interceptors: ComponentInterceptors,
        components: List<ResolvedComponent>,
        promisedComponents: Set<Int>,
        component: ResolvedComponent
    ): CodeBlock {
        val statement = CodeBlock.builder()
        val declaration = component.declaration
        val isPromised = promisedComponents.contains(component.index)
        if (isPromised) {
            statement.add("%L.set(graphDraw.addNode0(", component.name)
        } else {
            statement.add("val %L = graphDraw.addNode0(", component.name)
        }
        statement.indent().add("\n")
        statement.add("arrayOf(")
        for (tag in component.tags) {
            statement.add("%L::class.java, ", tag)
        }
        statement.add("),\n")
        statement.add("{ g -> ")

        when (declaration) {
            is ComponentDeclaration.AnnotatedComponent -> {
                statement.add("%T", declaration.classDeclaration.toClassName())
                if (declaration.typeVariables.isNotEmpty()) {
                    statement.add("<")
                    for ((i, tv) in declaration.typeVariables.withIndex()) {
                        if (i > 0) {
                            statement.add(", ")
                        }
                        statement.add("%L", tv.type!!.toTypeName())
                    }
                    statement.add(">")
                }
                statement.add("(")
            }

            is ComponentDeclaration.FromModuleComponent -> {
                if (declaration.module is ModuleDeclaration.AnnotatedModule) {
                    statement.add("impl.module%L.", allModules.indexOf(declaration.module.element))
                } else {
                    statement.add("impl.")
                }
                statement.add("%N", declaration.method.simpleName.asString())
                if (declaration.typeVariables.isNotEmpty()) {
                    statement.add("<")
                    for ((i, tv) in declaration.typeVariables.withIndex()) {
                        if (i > 0) {
                            statement.add(", ")
                        }
                        statement.add("%L", tv.type!!.toTypeName())
                    }
                    statement.add(">")
                }
                statement.add("(")
            }

            is ComponentDeclaration.FromExtensionComponent -> {
                val elem = declaration.sourceMethod
                if (elem.isConstructor()) {
                    val clazz = elem.closestClassDeclaration()!!
                    statement.add("%T(", clazz.toClassName())
                } else {
                    val parent = elem.parentDeclaration
                    if (parent is KSClassDeclaration) {
                        statement.add("%M(", MemberName(parent.toClassName(), elem.simpleName.asString()))
                    } else {
                        statement.add("%M(", MemberName(elem.packageName.asString(), elem.simpleName.asString()))
                    }
                }
            }

            is ComponentDeclaration.DiscoveredAsDependencyComponent -> {
                statement.add("%T(", declaration.classDeclaration.toClassName())
            }

            is ComponentDeclaration.PromisedProxyComponent -> {
                statement.add("%T(", declaration.className)
            }

            is ComponentDeclaration.OptionalComponent -> {
                statement.add("%T.ofNullable(", Optional::class.asClassName())
            }
        }
        if (component.dependencies.isNotEmpty()) {
            statement.indent().add("\n")
        }
        for ((i, dependency) in component.dependencies.withIndex()) {
            if (i > 0) {
                statement.add(",\n")
            }
            statement.add(dependency.write(ctx!!, components, promisedComponents))
        }
        if (component.dependencies.isNotEmpty()) {
            statement.unindent().add("\n")
        }
        statement.add(") },\n")
        statement.add("listOf(")
        for ((i, interceptor) in interceptors.interceptorsFor(declaration).withIndex()) {
            if (i > 0) {
                statement.add(", ")
            }
            statement.add("%L", interceptor.component.name)
        }
        statement.add(")")

        var rn = false
        for (dependency in component.dependencies) {
            if (dependency is ComponentDependency.AllOfDependency) {
                if (dependency.claim.claimType != DependencyClaim.DependencyClaimType.ALL_OF_PROMISE) {
                    val dependencies = GraphResolutionHelper.findDependenciesForAllOf(ctx!!, dependency.claim, components)
                    for (d in dependencies) {
                        if (!rn) {
                            rn = true
                            statement.add(",\n")
                        } else {
                            statement.add(", ")
                        }
                        statement.add("%L", d.component!!.name)
                        if (promisedComponents.contains(d.component!!.index)) {
                            statement.add(".get()")
                        }
                        if (dependency.claim.claimType == DependencyClaim.DependencyClaimType.ALL_OF_VALUE) {
                            statement.add(".valueOf()")
                        }
                    }
                }
                continue
            }
            if (dependency is ComponentDependency.PromiseOfDependency) {
                continue
            }
            if (dependency is ComponentDependency.PromisedProxyParameterDependency) {
                continue
            }
            if (dependency is ComponentDependency.SingleDependency && dependency.component != null) {
                if (!rn) {
                    rn = true
                    statement.add(",\n")
                } else {
                    statement.add(", ")
                }
                statement.add("%L", dependency.component!!.name)
                if (promisedComponents.contains(dependency.component!!.index)) {
                    statement.add(".get()")
                }
                if (dependency is ComponentDependency.ValueOfDependency) {
                    statement.add(".valueOf()")
                }
            }
        }
        statement.unindent()
        statement.add("\n)")
        if (isPromised) {
            statement.add(")")
        }
        return statement.add("\n").build()
    }
}
