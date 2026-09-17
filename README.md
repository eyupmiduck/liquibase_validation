# liquibase_validation

[![codecov](https://codecov.io/gh/eyupmiduck/liquibase_validation/branch/main/graph/badge.svg)](https://codecov.io/gh/eyupmiduck/liquibase_validation)

A collection of test logic to validate Liquibase changelogs and changesets.

## Contents

- `io.github.eyupmiduck.changelogvalidator.ChangelogValidator` — traverses a
  changelog graph from its master file, following nested `<include>` elements,
  and finds invalidly named changeSets and SQL files as well as orphaned SQL
  files.

## Using the library

The library is published to GitHub Packages from a `v*` tag (for example
`v0.1.0`), and each artifact version is immutable:

```xml
<dependency>
    <groupId>io.github.eyupmiduck</groupId>
    <artifactId>liquibase-validation</artifactId>
    <version>0.1.0</version>
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

## Releasing

1. Bump `<version>` in `pom.xml` (no `-SNAPSHOT` suffix).
2. Merge to `main`.
3. Push a matching tag, e.g. `git tag v0.1.0 && git push origin v0.1.0`.

The `Publish` workflow fails if the tag does not equal `v` + the POM version.
Consumer POMs must then be updated to the new version explicitly.

## Building

```sh
./mvnw verify
```
