# Viewmaker 

Create projections for your JPA Entities at compile time.

This basically is a learning project for me to understand how some libraries generate code at compile time (like avro, mapstruct, etc)

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


