# Viewmaker 

Create projections for your JPA Entities at compile time.

So while working I kept trying to generate projections with quarkus and spring boot.
And while there are some good options like .project(...), "select new ProjectionClass(...", these are all using reflection so I tried tackling compile time generation of projection mapping. 
I also generate some wrappers to functions of JPA Criteria API in order to get more fluent API.

This basically is a learning project for me to understand how some libraries generate code at compile time (like avro, mapstruct, etc)

## How it looks with/without

Let's say we have this class:
```java
@Entity
@Table(name = "movie")
public class Movie {
    @Id
    private int id;
    private String name;
    private int score;
    @Column(name="is_animated")
    private boolean isAnimated;
    @Column(name="duration_seconds")
    private float durationSeconds;

    public String getName() {
        return name;
    }
}
```
By creating a record and annotating it with `@Projection`
```java
@Projection(type = Movie.class)
public record MovieShort(String name, boolean animated){

    public static String findAll = "select m.name as name, m.is_animated as animated from Movie m";
}
```


Then you can do a query and receive as a Tuple (instead of directly MovieShort which would use reflection when using Hibernate), pass the result to projectResult and it will map the fields onto MovieShort.
```java
    @GET()
    @Path("/generate")
    public MovieDTO generate() {
        var raw = em.createQuery(findAll, Tuple.class)
                .getResultList();
        return MovieShortProjection.projectResult(raw).stream()
                .findFirst()
                .map(b -> new MovieDTO(b.name()))
                .orElseThrow(NotFoundException::new);
    }
```

In case you don't want to use a string for a query, you can leverage JPA Criteria API

```java
// Where before you would have to create several objects like root, criteriaBuilder, etc
    @GET()
    @Path("/criteriaOriginal")
    public MovieDTO criteriaOG() {
        var cb = em.getCriteriaBuilder();

        var criteriaQuery = cb.createQuery(MovieShort.class);
        var root = criteriaQuery.from(Movie.class);

        var name = root.get("name");
        var animated = root.<Boolean>get("isAnimated");
        var query = criteriaQuery
                .select(cb.construct(MovieShort.class, name, animated))
                .where(animated.equalTo(true))
                .orderBy(cb.asc(name));

        var result = em.createQuery(query).getResultList();

        return result
                .stream()
                .findFirst()
                .map(b -> new MovieDTO(b.name()))
                .orElseThrow(NotFoundException::new);
    }
  // with the generated classes you can inject MovieShortProjection
    private final EntityManager em;
    private final MovieShortProjection movieProj;

    public MovieController(EntityManager em, MovieShortProjection movieProj) {
        this.em = em;
        this.movieProj = movieProj;
    }

    @GET()
    @Path("/criteriaGenerated")
    public MovieDTO criteriaGen() {
        var query = movieProj.buildProjectedQuery(em.getCriteriaBuilder()) //buildProjectedQuery will have the proper select 
                .where(movieProj.animated().isTrue()) // you can use an fluent API without having to manage the instantiated Path fields
                .orderBy(movieProj.name().asc());

        var result = em.createQuery(query).getResultList(); //goes back to normal here

        return projectResult(result)
                .stream()
                .findFirst()
                .map(b -> new MovieDTO(b.name()))
                .orElseThrow(NotFoundException::new);
    }
```


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


## todo

Need to add tests (don't really want to compare strings, need to see best way to do this)
Will add a module with an example project
