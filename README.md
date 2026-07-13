# Viewmaker 

Create projections for your JPA Entities at compile time.

This basically is a learning project for me to understand how some libraries generate code at compile time (like avro, mapstruct, etc)

## How it looks with/without



## How it works / Making your own Processor

Created two separate modules inside a single project, so that the user can import the api dependency (to get access to the annotation), and use the processor module on the compile maven plugin.
