# liquibase_validation

A collection of test logic to validate Liquibase changelogs and changesets.

## Contents

- `io.github.eyupmiduck.changelogvalidator.ChangelogValidator` — finds
  invalidly named changelog files and orphaned SQL files in a changelog
  directory.

## Using the library

The library is published to GitHub Packages on every push to `main`:

```xml
<dependency>
    <groupId>io.github.eyupmiduck</groupId>
    <artifactId>liquibase-validation</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Consuming builds must be authenticated to GitHub Packages even for public
packages. Add the repository to the consuming POM:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/eyupmiduck/liquibase_validation</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

And provide credentials in `~/.m2/settings.xml` (a classic personal access
token with the `read:packages` scope is required):

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_CLASSIC_PAT</password>
        </server>
    </servers>
</settings>
```

Before a consuming repository's `GITHUB_TOKEN` can read the package, grant
that repository read access under the package's **Manage Actions access**
settings.

## Building

```sh
./mvnw verify
```
