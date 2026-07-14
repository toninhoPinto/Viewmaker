# Viewmaker 

Create projections for your JPA Entities at compile time.

This basically is a learning project for me to understand how some libraries generate code at compile time (like avro, mapstruct, etc)

So while working I kept trying to generate projections with quarkus and spring boot.
And while there are some good options like .project(...), "select new ProjectionClass(...", these are all using reflection so I tried tackling compile time generation. 
I also generate some wrappers to functions of JPA Criteria API in order to get more fluent API.

## How it looks with/without



## How it works / Making your own Processor

Created two separate modules inside a single project, so that the user can import the api dependency (to get access to the annotation), and use the processor module on the compile maven plugin.

### Annotation

Simple, just like all other annotations, you create an @interface, put the fields you think are important, done.

For this project 
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Projection {
    Class type();
}
```
I wanted the user to annotate the projection record, and tell me which entity he is trying to view.
@Target you define type because I want to annotate records and classes.
@Retention is that this annotation should exist up until runtime.

### Processor

Create a class that extends AbstractProcessor:

```java
@SupportedAnnotationTypes("io.github.toninhopinto.Projection")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class ProjectionProcessor extends AbstractProcessor {
```

AutoService is an annotation from google auto service, that is a processor in of itself and generates some META-INF files to avoid having to create it myself

From here it is just implement process(), I also used Roaster to give me an API to help generate classes and methods without having to manipulate strings for everything.
Another alternative would be templating.


