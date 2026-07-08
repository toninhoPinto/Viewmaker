package io.github.toninhopinto;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;

import com.google.auto.service.AutoService;

@SupportedAnnotationTypes("io.github.toninhopinto.Project")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class ProjectionProcessor extends AbstractProcessor {

        private Messager messager;
        private Filer filer;
        private Elements utils;
        private Types typeUtils;

        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
                super.init(processingEnv);
                messager = processingEnv.getMessager();
                utils = processingEnv.getElementUtils();
                typeUtils = processingEnv.getTypeUtils();
                filer = processingEnv.getFiler();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                for (Element e : roundEnv.getElementsAnnotatedWith(Project.class)) {
                        if (e.getKind() != ElementKind.RECORD) {
                                messager.printMessage(Diagnostic.Kind.ERROR, "@Project can only be used on records", e);
                                continue;
                        }
                        printer(createFieldProjectionClasses(utils.getPackageOf(e).getQualifiedName().toString()));
                        printer(List.of(projectionClassCreator((TypeElement) e)));
                }
                return true;
        }

        private JavaClassSource projectionClassCreator(TypeElement e) {
                var annotatedClass = e.getSimpleName().toString();
                var originalEntity = getProjectType(e);
                var packageName = utils.getPackageOf(e).getQualifiedName().toString();

                var constructorArguments = e.getRecordComponents().stream()
                                .map(this::tupleConstructorArgument)
                                .collect(Collectors.joining(", "));
                var selectArguments = e.getRecordComponents().stream()
                                .map(this::selectTuple)
                                .collect(Collectors.joining(", "));

                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName(annotatedClass + "Projection");

                src.addAnnotation().setName("ApplicationScoped");

                src.addImport(List.class);
                src.addImport("jakarta.persistence.Tuple");
                src.addImport("jakarta.persistence.criteria.CriteriaQuery");
                src.addImport("jakarta.persistence.criteria.CriteriaBuilder");
                src.addImport("jakarta.enterprise.context.ApplicationScoped");
                src.addImport("jakarta.persistence.criteria.Root");
                src.addImport("jakarta.persistence.criteria.Path");

                src.addField("private Root<%s> root;".formatted(originalEntity));
                src.addField("private CriteriaBuilder builder;");

                var fields = e.getRecordComponents();
                for (int i = 0; i < fields.size(); i++) {
                        var field = fields.get(i);

                        var type = boxedType(field);
                        var simpleName = typeUtils.asElement(type).getSimpleName().toString();
                        src.addMethod()
                                        .setPublic()
                                        .setReturnType("FieldProjection<%s>".formatted(boxedType(field)))
                                        .setName(field.getSimpleName().toString())
                                        .setBody("""
                                                        return new %sFieldProjection(builder, root.<%s>get("%s"));
                                                        """.formatted(simpleName,
                                                        type,
                                                        field.getSimpleName().toString()));
                }

                src.addMethod()
                                .setConstructor(false)
                                .setStatic(true)
                                .setPublic()
                                .setReturnType("List<%s>".formatted(annotatedClass))
                                .setBody("""
                                                return tuples.stream()
                                                .map(tuple -> new %s(%s))
                                                .toList();
                                                """.formatted(annotatedClass, constructorArguments))
                                .setName("projectResult")
                                .addParameter("List<Tuple>", "tuples");

                src.addMethod()
                                .setReturnType("MovieShortProjection")
                                .setName("root")
                                .setBody("""
                                                root = this.root;
                                                return this;
                                                """)
                                .addParameter("(Root<Movie>", "root");

                src.addMethod()
                                .setPublic()
                                .setReturnType("CriteriaQuery<Tuple>")
                                .setBody("""
                                                builder = cb;
                                                var query = builder.createTupleQuery();
                                                return query.select(builder.tuple(%s));
                                                """.formatted(selectArguments))
                                .setName("buildProjectedQuery")
                                .addParameter("CriteriaBuilder", "cb");

                return src;
        }

        private TypeMirror boxedType(RecordComponentElement component) {
                var type = component.asType();

                if (type.getKind().isPrimitive()) {
                        return typeUtils.boxedClass((PrimitiveType) type).asType();
                }

                return typeUtils.erasure(type);
        }

        private List<JavaClassSource> createFieldProjectionClasses(String packageName) {

                var rootType = utils.getTypeElement("jakarta.persistence.criteria.CriteriaBuilder");
                var rootMethods = ElementFilter.methodsIn(utils.getAllMembers(rootType));

                var ignoreMethods = List.of(
                                "Query",
                                "wait",
                                "createCriteria",
                                "getClass",
                                "hashCode",
                                "equals",
                                "toString",
                                "tuple",
                                "construct",
                                "array",
                                "notify",
                                "selectCase" // not sure
                );

                var potentialNewMethods = rootMethods
                                .stream()
                                .filter(method -> method.getModifiers().contains(Modifier.PUBLIC))
                                .filter(m -> ignoreMethods.stream()
                                                .noneMatch(im -> m.getSimpleName().toString().contains(im)))
                                .toList();

                var comparableMethodsAndOverrides = findMethodsOfType(potentialNewMethods,
                                "Expression<java.util.Comparable<", "Comparable");
                var numberMethodsAndOverrides = findMethodsOfType(potentialNewMethods, "Number",
                                "Number");
                var stringMethodsAndOverrides = findMethodsOfType(potentialNewMethods, "Expression<java.lang.String>",
                                "String");
                var intMethodsAndOverrides = findMethodsOfType(potentialNewMethods, "Expression<java.lang.Integer>",
                                "Integer");
                var floatMethodsAndOverrides = findMethodsOfType(potentialNewMethods, "Expression<java.lang.Float>",
                                "Float");
                var booleanMethodsAndOverrides = findMethodsOfType(potentialNewMethods, "Expression<java.lang.Boolean>",
                                "Boolean");

                var excludeFromBase = new HashSet<>();
                excludeFromBase.addAll(comparableMethodsAndOverrides);
                excludeFromBase.addAll(stringMethodsAndOverrides);
                excludeFromBase.addAll(stringMethodsAndOverrides);
                excludeFromBase.addAll(intMethodsAndOverrides);
                excludeFromBase.addAll(floatMethodsAndOverrides);
                excludeFromBase.addAll(booleanMethodsAndOverrides);
                excludeFromBase.addAll(numberMethodsAndOverrides);
                var excludeFromBaseMethods = excludeFromBase.stream().collect(Collectors.toSet());
                var main = fieldProjections(packageName,
                                potentialNewMethods.stream().filter(m -> !excludeFromBaseMethods.contains(m)).toList());

                var comparable = fieldComparableProjections(packageName, main, comparableMethodsAndOverrides);
                var comparableNumberClass = fieldNumberComparableProjections(packageName, comparable,
                                numberMethodsAndOverrides);
                var numberFieldClass = fieldNumberProjections(packageName, main, numberMethodsAndOverrides);
                var stringFieldClass = stringFieldProjection(packageName, main, stringMethodsAndOverrides);
                var intFieldClass = intFieldProjection(packageName, main, intMethodsAndOverrides);
                var floatFieldClass = floatFieldProjection(packageName, main, floatMethodsAndOverrides);
                var boolFieldClass = boolFieldProjection(packageName, main, booleanMethodsAndOverrides);

                return List.of(
                                main,
                                comparable,
                                comparableNumberClass,
                                numberFieldClass,
                                stringFieldClass,
                                intFieldClass,
                                floatFieldClass,
                                boolFieldClass);
        }

        private List<ExecutableElement> findMethodsOfType(List<ExecutableElement> potentialNewMethods,
                        String typeOfFirstArgument, String innerType) {
                var stringMethods = potentialNewMethods
                                .stream()
                                .filter(m -> (!m.getParameters().isEmpty() &&
                                                m.getParameters().get(0).asType().toString()
                                                                .contains(typeOfFirstArgument))
                                                ||
                                                (!m.getTypeParameters().isEmpty() &&
                                                                m.getTypeParameters().get(0).getBounds().toString()
                                                                                .contains(innerType))

                                )
                                .map(m -> m.getSimpleName().toString())
                                .collect(Collectors.toSet());
                return potentialNewMethods
                                .stream()
                                .filter(m -> stringMethods.contains(m.getSimpleName().toString()))
                                .toList();
        }

        private JavaClassSource fieldProjections(String packageName, List<ExecutableElement> potentialNewMethods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("FieldProjection");
                src.addTypeVariable("Y");

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<Y> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<Y>", "expr");
                constructor.setBody("""
                                this.builder = builder;
                                this.expr = expr;
                                """);

                potentialNewMethods.forEach(m -> {
                        var params = getWrappedCallParameters(m);

                        var newM = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(m.getReturnType().toString())
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));
                        m.getTypeParameters()
                                        .forEach(tp -> addTypeVariable(newM, tp));

                        params.wrapper.forEach(
                                        p -> newM.addParameter(p.asType().toString(), p.getSimpleName().toString()));

                });

                src.addMethod().setName("get").setPublic().setReturnType("Expression").setBody("""
                                return this.expr;
                                """);

                return src;
        }

        private JavaClassSource fieldNumberComparableProjections(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("NumberComparableFieldProjection")
                                .extendSuperType(parent);
                var typeVar = src.addTypeVariable("N");
                typeVar.setBounds("java.lang.Number", "java.lang.Comparable<? super N>");

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<N> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<N>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<N>");
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(retType)
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource fieldNumberProjections(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("NumberFieldProjection")
                                .extendSuperType(parent);
                var typeVar = src.addTypeVariable("N");
                typeVar.setBounds("java.lang.Number");

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<N> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<N>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<N>");
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(retType)
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource fieldComparableProjections(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("ComparableFieldProjection")
                                .extendSuperType(parent);
                var typeVar = src.addTypeVariable("Y");
                typeVar.setBounds("java.lang.Comparable<? super Y>");

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<Y> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<Y>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<Y>");
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(retType)
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource boolFieldProjection(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("BooleanFieldProjection")
                                .extendSuperType(parent);

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<Boolean> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<Boolean>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(m.getReturnType().toString())
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource intFieldProjection(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("IntegerFieldProjection")
                                .extendSuperType(parent);

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<Integer> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<Integer>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(m.getReturnType().toString())
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource floatFieldProjection(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("FloatFieldProjection")
                                .extendSuperType(parent);

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<Float> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<Float>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(m.getReturnType().toString())
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        private JavaClassSource stringFieldProjection(String packageName, JavaClassSource parent,
                        List<ExecutableElement> methods) {
                JavaClassSource src = Roaster.create(JavaClassSource.class);
                src.setPackage(packageName)
                                .setPublic()
                                .setName("StringFieldProjection")
                                .extendSuperType(parent);

                src.addImport("jakarta.persistence.criteria.*");

                src.addField("private CriteriaBuilder builder");
                src.addField("private Expression<String> expr");

                var constructor = src.addMethod().setConstructor(true);
                constructor.addParameter("CriteriaBuilder", "builder");
                constructor.addParameter("Expression<String>", "expr");
                constructor.setBody("""
                                super(builder, expr);
                                """);

                methods.forEach(m -> {
                        var params = getWrappedCallParameters(m);
                        var newMethod = src.addMethod()
                                        .setName(m.getSimpleName().toString())
                                        .setReturnType(m.getReturnType().toString())
                                        .setBody("""
                                                        return builder.%s(%s);
                                                        """.formatted(m.getSimpleName(),
                                                        params.wrapped.stream().collect(Collectors.joining(", "))));

                        params.wrapper.forEach(p -> {
                                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                        });
                });

                return src;
        }

        record ParameterHelper(List<? extends VariableElement> wrapper, List<String> wrapped) {
        }

        private ParameterHelper getWrappedCallParameters(ExecutableElement m) {
                var parameters = new ArrayList<>(m.getParameters()
                                .stream()
                                .toList());

                var handlesFirstExpression = false;
                if (!parameters.isEmpty()) {
                        var firstType = parameters.get(0).asType();
                        if (firstType.toString().matches("^jakarta\\.persistence\\.criteria\\.Expression.*")) {
                                handlesFirstExpression = true;
                                parameters.remove(0);
                        }
                }

                var parameterNames = new ArrayList<String>(parameters
                                .stream()
                                .map(p -> p.getSimpleName().toString())
                                .toList());
                if (handlesFirstExpression) {
                        parameterNames.add(0, "expr");
                }
                return new ParameterHelper(parameters, parameterNames);
        }

        private void addTypeVariable(MethodSource<JavaClassSource> method, TypeParameterElement tp) {
                var typeVariable = method.addTypeVariable(tp.getSimpleName().toString());

                var bounds = tp.getBounds().stream()
                                .filter(bound -> !"java.lang.Object".equals(bound.toString()))
                                .map(Object::toString)
                                .collect(Collectors.toList());

                if (!bounds.isEmpty()) {
                        typeVariable.setBounds(bounds.toArray(String[]::new));
                }
        }

        private String selectTuple(RecordComponentElement component) {
                return "root.get(\"%s\").alias(\"%s\")".formatted(component.getSimpleName(), component.getSimpleName());
        }

        private String getProjectType(Element e) {
                try {
                        return e.getAnnotation(Project.class).type().getCanonicalName();
                } catch (MirroredTypeException ex) {
                        return ex.getTypeMirror().toString();
                }
        }

        private String tupleConstructorArgument(RecordComponentElement component) {
                var type = typeUtils.erasure(component.asType());
                return "tuple.get(\"%s\", %s.class)".formatted(component.getSimpleName(), type);
        }

        private void printer(List<JavaClassSource> futureFiles) {
                futureFiles
                                .forEach(ff -> {
                                        JavaFileObject file;
                                        try {
                                                file = filer.createSourceFile(ff.getCanonicalName());
                                                try (Writer w = file.openWriter()) {
                                                        w.write(ff.toString());
                                                }
                                        } catch (IOException e) {
                                                messager.printMessage(Diagnostic.Kind.ERROR,
                                                                "Failed to generate source code");
                                        }
                                });
        }

}
