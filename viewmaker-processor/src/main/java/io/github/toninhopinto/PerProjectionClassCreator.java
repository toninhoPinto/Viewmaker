package io.github.toninhopinto;

import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.lang.model.element.Element;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.Elements;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PerProjectionClassCreator {

    public static JavaClassSource projectionClassCreator(TypeElement e, Elements utils, Types typeUtils) {
        var annotatedClass = e.getSimpleName().toString();
        var originalEntity = getClassOfAnnotatedProjection(e);
        var packageName = utils.getPackageOf(e).getQualifiedName().toString();

        var constructorArguments = e.getRecordComponents().stream()
                .map(r -> tupleConstructorArgument(r, typeUtils))
                .collect(Collectors.joining(", "));
        var selectArguments = e.getRecordComponents().stream()
                .map(PerProjectionClassCreator::selectTuple)
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
        for (RecordComponentElement field : fields) {
            var type = boxedType(field, typeUtils);
            var simpleName = typeUtils.asElement(type).getSimpleName().toString();
            src.addMethod()
                    .setPublic()
                    .setReturnType("%sFieldProjection"
                            .formatted(simpleName))
                    .setName(field.getSimpleName().toString())
                    .setBody("""
                            return new %sFieldProjection(builder, root.<%s>get("%s"));"""
                            .formatted(simpleName,
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
                        """
                        .formatted(selectArguments))
                .setName("buildProjectedQuery")
                .addParameter("CriteriaBuilder", "cb");

        return src;
    }

    private static String getClassOfAnnotatedProjection(Element e) {
        try {
            return Objects.requireNonNull(e.getAnnotation(Projection.class)).type().getCanonicalName();
        } catch (MirroredTypeException ex) {
            return ex.getTypeMirror().toString();
        }
    }

    private static TypeMirror boxedType(RecordComponentElement component, Types typeUtils) {
        var type = component.asType();

        if (type.getKind().isPrimitive()) {
            return typeUtils.boxedClass((PrimitiveType) type).asType();
        }

        return typeUtils.erasure(type);
    }

    private static String selectTuple(RecordComponentElement component) {
        return "root.get(\"%s\").alias(\"%s\")".formatted(component.getSimpleName(),
                component.getSimpleName());
    }

    private static String tupleConstructorArgument(RecordComponentElement component, Types typeUtils) {
        var type = typeUtils.erasure(component.asType());
        return "tuple.get(\"%s\", %s.class)".formatted(component.getSimpleName(), type);
    }

}
