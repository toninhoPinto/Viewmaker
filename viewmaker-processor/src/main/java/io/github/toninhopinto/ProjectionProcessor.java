package io.github.toninhopinto;

import static io.github.toninhopinto.PerProjectionClassCreator.projectionClassCreator;

import com.google.auto.service.AutoService;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;

@SupportedAnnotationTypes("io.github.toninhopinto.Projection")
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
    for (Element e : roundEnv.getElementsAnnotatedWith(Projection.class)) {
      if (e.getKind() != ElementKind.RECORD) {
        messager.printMessage(Diagnostic.Kind.ERROR, "@Project can only be used on records", e);
        continue;
      }
      printer(createFieldProjectionClasses(utils.getPackageOf(e).getQualifiedName().toString()));
      printer(List.of(projectionClassCreator((TypeElement) e, utils, typeUtils)));
    }
    return true;
  }

  private void printer(List<JavaClassSource> futureFiles) {
    futureFiles.forEach(
        ff -> {
          try {
            var file = filer.createSourceFile(ff.getCanonicalName());
            try (Writer w = file.openWriter()) {
              w.write(ff.toString());
            }
          } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to generate source code");
          }
        });
  }

  private List<JavaClassSource> createFieldProjectionClasses(String packageName) {

    var rootType = utils.getTypeElement("jakarta.persistence.criteria.CriteriaBuilder");
    var rootMethods = ElementFilter.methodsIn(utils.getAllMembers(rootType));

    var ignoreMethods =
        List.of(
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
            "treat",
            "selectCase" // not
            // sure
            );

    var potentialNewMethods =
        rootMethods.stream()
            .filter(method -> method.getModifiers().contains(Modifier.PUBLIC))
            .filter(
                m ->
                    ignoreMethods.stream()
                        .noneMatch(im -> m.getSimpleName().toString().contains(im)))
            .toList();

    var collectionMethods =
        findMethodsOfType(potentialNewMethods, "Collection", "Collection");
    var comparableMethods =
        findMethodsOfType(potentialNewMethods, "Expression<java.util.Comparable<", "Comparable");
    var numberMethods = findMethodsOfType(potentialNewMethods, "Number", "Number");
    var stringMethods =
        findMethodsOfType(potentialNewMethods, "Expression<java.lang.String>", "String");
    var intMethods =
        findMethodsOfType(potentialNewMethods, "Expression<java.lang.Integer>", "Integer");
    var floatMethods =
        findMethodsOfType(potentialNewMethods, "Expression<java.lang.Float>", "Float");
    var booleanMethods =
        findMethodsOfType(potentialNewMethods, "Expression<java.lang.Boolean>", "Boolean");

    var excludeFromBase = new HashSet<>();
    excludeFromBase.addAll(comparableMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(stringMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(intMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(floatMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(booleanMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(numberMethods.stream().map(n->n.getSimpleName().toString()).toList());
    excludeFromBase.addAll(collectionMethods.stream().map(n->n.getSimpleName().toString()).toList());
    var excludeFromBaseMethods = new HashSet<>(excludeFromBase);

    var main =
        fieldProjections(
            packageName,
            potentialNewMethods.stream().filter(m -> !excludeFromBaseMethods.contains(m.getSimpleName().toString())).toList());

    var comparable = fieldComparableProjections(packageName, main, comparableMethods);
    var collection = fieldCollectionProjections(packageName, main, collectionMethods);
    var comparableNumberClass =
        fieldNumberComparableProjections(packageName, comparable, numberMethods);
    var numberFieldClass = fieldNumberProjections(packageName, main, numberMethods);
    var stringFieldClass = stringFieldProjection(packageName, main, stringMethods);
    var intFieldClass = intFieldProjection(packageName, main, intMethods);
    var floatFieldClass = floatFieldProjection(packageName, main, floatMethods);
    var boolFieldClass = boolFieldProjection(packageName, main, booleanMethods);

    return List.of(
        main,
        comparable,
        collection,
        comparableNumberClass,
        numberFieldClass,
        stringFieldClass,
        intFieldClass,
        floatFieldClass,
        boolFieldClass);
  }

  private List<ExecutableElement> findMethodsOfType(
      List<ExecutableElement> potentialNewMethods, String typeOfFirstArgument, String innerType) {
        return potentialNewMethods.stream()
            .filter(
                m ->
                    (!m.getParameters().isEmpty()
                            && m.getParameters()
                                .stream()
                                .anyMatch(p -> p.asType().toString().contains(typeOfFirstArgument)))
                        || (!m.getTypeParameters().isEmpty()
                            && m.getTypeParameters()
                                .stream()
                                .anyMatch(p -> p.getBounds().toString().contains(innerType))))
            .toList();
  }

  private JavaClassSource fieldProjections(
      String packageName, List<ExecutableElement> potentialNewMethods) {
    JavaClassSource src = Roaster.create(JavaClassSource.class);
    src.setPackage(packageName).setPublic().setName("FieldProjection");
    src.addTypeVariable("Y");

    src.addImport("jakarta.persistence.criteria.*");

    src.addField("private CriteriaBuilder builder");
    src.addField("private Expression<Y> expr");

    var constructor = src.addMethod().setConstructor(true);
    constructor.addParameter("CriteriaBuilder", "builder");
    constructor.addParameter("Expression<Y>", "expr");
    constructor.setBody(
        """
                                                this.builder = builder;
                                                this.expr = expr;
                                                """);

    potentialNewMethods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, null);
          if (params.wrapped.stream().anyMatch(p -> p.contains("expr"))) {
            var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<Y>");
              var newM =
                  src.addMethod()
                  .setName(m.getSimpleName().toString())
                  .setReturnType(retType)
                  .setBody(
                          """
                          return builder.%s(%s);
                          """
                          .formatted(
                              m.getSimpleName(),
                              params.wrapped.stream().collect(Collectors.joining(", "))));

              params.wrapper.forEach(
                      p -> newM.addParameter(p.asType().toString(), p.getSimpleName().toString()));
          }
        });

    src.addMethod()
        .setName("get")
        .setPublic()
        .setReturnType("Expression")
        .setBody(
            """
                                                                return this.expr;
                                                                """);

    return src;
  }

  private JavaClassSource fieldNumberComparableProjections(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    var seenParams = new HashSet<>();
    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Number");
          if (!seenParams.contains(m.getSimpleName().toString() + params.wrapped.toString())) {
            seenParams.add(m.getSimpleName().toString() + params.wrapped.toString());
            var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<N>");
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(retType)
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(
                                m.getSimpleName(),
                                params.wrapped.stream().collect(Collectors.joining(", "))));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  private JavaClassSource fieldNumberProjections(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    var seenParams = new HashSet<>();
    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Number");
          if (!seenParams.contains(m.getSimpleName().toString() + params.wrapped.toString())) {
            seenParams.add(m.getSimpleName().toString() + params.wrapped.toString());
            var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<N>");
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(retType)
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(m.getSimpleName(), String.join(", ", params.wrapped)));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  private JavaClassSource fieldCollectionProjections(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
    JavaClassSource src = Roaster.create(JavaClassSource.class);
    src.setPackage(packageName).setPublic().setName("CollectionFieldProjection");
    src.addTypeVariable("E");
    src.setSuperType(parent.getCanonicalName().toString() + "<Collection<E>>");

    src.addImport("jakarta.persistence.criteria.*");
    src.addImport("java.util.Collection");

    src.addField("private CriteriaBuilder builder");
    src.addField("private Expression<Collection<E>> expr");

    var constructor = src.addMethod().setConstructor(true);
    constructor.addParameter("CriteriaBuilder", "builder");
    constructor.addParameter("Expression<Collection<E>>", "expr");
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Collection");
          if (params.wrapped.stream().anyMatch(n -> n.contains("expr"))) {
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(m.getReturnType().toString())
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(m.getSimpleName(), String.join(", ", params.wrapped)));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  private JavaClassSource fieldComparableProjections(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Comparable");
          var retType = m.getReturnType().toString().replaceFirst("\\<[A-Z]\\>", "<Y>");
          var newMethod =
              src.addMethod()
                  .setName(m.getSimpleName().toString())
                  .setReturnType(retType)
                  .setBody(
                      """
                      return builder.%s(%s);
                      """
                          .formatted(
                              m.getSimpleName(),
                              params.wrapped.stream().collect(Collectors.joining(", "))));

          params.wrapper.forEach(
              p -> {
                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
              });
        });

    return src;
  }

  private JavaClassSource boolFieldProjection(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Boolean");
          if (params.wrapped.stream().anyMatch(n -> n.contains("expr"))) {
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(m.getReturnType().toString())
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(
                                m.getSimpleName(),
                                params.wrapped.stream().collect(Collectors.joining(", "))));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  private JavaClassSource intFieldProjection(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    var seenParams = new HashSet<>();
    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Integer");
          if (!seenParams.contains(m.getSimpleName().toString() + params.wrapped.toString())) {
            seenParams.add(m.getSimpleName().toString() + params.wrapped.toString());
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(m.getReturnType().toString())
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(
                                m.getSimpleName(),
                                params.wrapped.stream().collect(Collectors.joining(", "))));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  private JavaClassSource floatFieldProjection(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
    JavaClassSource src = Roaster.create(JavaClassSource.class);
    src.setPackage(packageName).setPublic().setName("FloatFieldProjection").extendSuperType(parent);

    src.addImport("jakarta.persistence.criteria.*");

    src.addField("private CriteriaBuilder builder");
    src.addField("private Expression<Float> expr");

    var constructor = src.addMethod().setConstructor(true);
    constructor.addParameter("CriteriaBuilder", "builder");
    constructor.addParameter("Expression<Float>", "expr");
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "Float");
          var newMethod =
              src.addMethod()
                  .setName(m.getSimpleName().toString())
                  .setReturnType(m.getReturnType().toString())
                  .setBody(
                      """
                      return builder.%s(%s);
                      """
                          .formatted(
                              m.getSimpleName(),
                              params.wrapped.stream().collect(Collectors.joining(", "))));

          params.wrapper.forEach(
              p -> {
                newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
              });
        });

    return src;
  }

  private JavaClassSource stringFieldProjection(
      String packageName, JavaClassSource parent, List<ExecutableElement> methods) {
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
    constructor.setBody(
        """
                                                super(builder, expr);
                                                """);

    var seenParams = new HashSet<>();
    methods.forEach(
        m -> {
          var params = getWrappedCallParameters(m, "String");
          if (!seenParams.contains(m.getSimpleName() + params.wrapped.toString())) {
            seenParams.add(m.getSimpleName() + params.wrapped.toString());
            var newMethod =
                src.addMethod()
                    .setName(m.getSimpleName().toString())
                    .setReturnType(m.getReturnType().toString())
                    .setBody(
                        """
                        return builder.%s(%s);
                        """
                            .formatted(
                                m.getSimpleName(),
                                params.wrapped.stream().collect(Collectors.joining(", "))));

            params.wrapper.forEach(
                p -> {
                  newMethod.addParameter(p.asType().toString(), p.getSimpleName().toString());
                });
          }
        });

    return src;
  }

  record ParameterHelper(List<? extends VariableElement> wrapper, List<String> wrapped) {}

  private ParameterHelper getWrappedCallParameters(ExecutableElement m, String boundType) {
    var parameters = new ArrayList<>(m.getParameters().stream().toList());

    var handlesExpression = -1;
    if (!parameters.isEmpty()) {
      var firstType = parameters.get(0).asType();
      var lastType = parameters.get(parameters.size() - 1).asType();
      if (isReplaceableExpression(firstType, m, boundType)) {
        handlesExpression = 0;
        parameters.remove(0);
      } else if (boundType != null
          && lastType.toString().matches(replaceableExpressionPattern(boundType))) {
        handlesExpression = parameters.size() - 1;
        parameters.remove(handlesExpression);
      }
    }

    var parameterNames =
        new ArrayList<String>(parameters.stream().map(p -> p.getSimpleName().toString()).toList());
    if (handlesExpression > -1) {
      parameterNames.add(handlesExpression, "expr");
    }
    return new ParameterHelper(parameters, parameterNames);
  }

  private boolean isReplaceableExpression(
      TypeMirror type, ExecutableElement method, String boundType) {
    var typeName = type.toString();
    if (boundType == null) {
      return typeName.matches("^jakarta\\.persistence\\.criteria\\.Expression.*");
    }

    return typeName.matches(replaceableExpressionPattern(boundType))
        || (typeName.contains("Expression")
            && !method.getTypeParameters().isEmpty()
            && method.getTypeParameters().get(0).getBounds().stream()
                .map(Object::toString)
                .anyMatch(bound -> bound.contains(boundType)));
  }

  private String replaceableExpressionPattern(String boundType) {
    return "^jakarta\\.persistence\\.criteria\\.Expression<.*%s>".formatted(boundType);
  }

  private void addTypeVariable(MethodSource<JavaClassSource> method, TypeParameterElement tp) {
      var typeVariable = method.addTypeVariable(tp.getSimpleName().toString());

      var bounds =
          tp.getBounds().stream()
          .filter(bound -> !"java.lang.Object".equals(bound.toString()))
          .map(Object::toString)
          .collect(Collectors.toList());
  }
}
