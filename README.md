# Customized Java wrapper around AwsS3Client #

This library is used by AwsMirror and AwsUpdate.

This project was sponsored by [Micronautics Research Corporation](http://www.micronauticsresearch.com/)

## Building ##

 1. Java 7 or later is required.
 1. Point `JAVA_HOME` to a Java 7 JDK.
 1. Type the following into a bash console:
````
git clone git@github.com:mslinn/AwsS3.git
cd AwsS3
sbt publish-local

## Installation ##
Add this to your project's `build.sbt` (remember that file requires double-spacing):

````
libraryDependencies += "com.micronautics" % "awss3" % "0.1.0-SNAPSHOT" withSources()

// The sbt-plugin-releases resolver should not be required for SBT 0.12, but it is required for SBT 0.11.3:

//resolvers += Resolver.url("sbt-plugin-releases", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

// The following resolver will continue to be required for SNAPSHOTS:

resolvers += Resolver.url("sbt-plugin-snapshots", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns)
````

## Notes ##
When web site access is enabled, AWS content is accessed by paths constructed by concatentating the URL, a slash (/),
and the keyed data.
The keys must therefore consist of relative paths (relative directory name followed by a file name),
and must not start with a leading slash.
This program stores each file name (referred to by AWS as a key) without a leading slash.
For example, assuming that the default file name is `index.html`,
`http://domain.com` and `http://domain.com/` are translated to `http://domain.com/index.html`.

As another example, AwsMirror defines the key for a file in a directory called `{WEBROOT}/blah/ick/yuck.html` to `blah/ick/yuck.html`.

For each directory, AWS creates a file of the same name, with the suffix `_$folder$`.
If one of those files are deleted, the associated directory becomes unreachable. Don't mess with them.
These hidden files are ignored by this program; users never see them because they are for AWS S3 internal use only.
